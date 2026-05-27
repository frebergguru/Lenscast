package dev.lenscast.streaming.rtsp

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference

/**
 * MediaCodec H.264 encoder wrapped behind a small interface tailored to RTSP needs:
 *
 *  - An [inputSurface] is provided to the caller — wire it as a Camera2 session output.
 *  - SPS and PPS are captured from the encoder's codec-specific data and exposed via
 *    [parameterSets] so the RTSP layer can build the SDP `sprop-parameter-sets` field
 *    and prepend them to the first frame of every new client connection.
 *  - Each subsequent output buffer can contain one or more NAL units separated by
 *    Annex-B start codes (00 00 00 01 or 00 00 01); we split and feed them individually
 *    to [onNal] minus the start code (matching RTP's expectation per RFC 6184).
 *
 *  No assumption is made about specific hardware encoders — only `MIMETYPE_VIDEO_AVC`
 *  is requested and the platform picks the best encoder.
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val bitrateBps: Int,
    /** Display rotation in degrees (0/90/180/270). Written to the bitstream as rotation
     *  metadata; decoders honoring it (FFmpeg, OBS, modern players) rotate on render. */
    private val rotationDegrees: Int = 0,
    private val iFrameIntervalSeconds: Int = 2,
) {
    /** SPS, PPS as the encoder produced them (no Annex-B start codes), or null until prepared. */
    data class ParameterSets(val sps: ByteArray, val pps: ByteArray)

    @Volatile private var parameterSetsRef = AtomicReference<ParameterSets?>(null)
    val parameterSets: ParameterSets? get() = parameterSetsRef.get()

    private val framesEncoded = java.util.concurrent.atomic.AtomicLong(0)
    /** Total VCL NAL frames the encoder has produced so far. */
    fun framesProduced(): Long = framesEncoded.get()

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private val codecThread = HandlerThread("LenscastH264Codec").also { it.start() }
    private val codecHandler = Handler(codecThread.looper)

    @Volatile private var onNal: ((nal: ByteArray, ptsUs: Long, isKey: Boolean) -> Unit)? = null

    /**
     * Configure the encoder and return the input Surface to feed frames into.
     * Call [start] after the camera session is producing frames.
     */
    fun prepare(): Surface {
        if (codec != null) return inputSurface!!
        val format = MediaFormat.createVideoFormat(MIMETYPE, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, iFrameIntervalSeconds)
            // Embed orientation so the decoder (OBS/FFmpeg/ffplay/VLC) rotates the frame
            // on render. Sensors are mounted landscape on phones, so without this the
            // stream is always landscape regardless of how the user holds the device.
            if (rotationDegrees != 0) {
                setInteger(MediaFormat.KEY_ROTATION, rotationDegrees)
            }
            // Baseline profile yields the broadest decoder compatibility — important since OBS
            // (FFmpeg-based) handles it well and some media players are picky.
            setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
            // Latency hints: tell the encoder we want real-time output, not best-quality.
            // Combined these cut typical encoder buffering from ~50–200 ms to ~10–30 ms.
            setInteger(MediaFormat.KEY_LATENCY, 1)
            setInteger(MediaFormat.KEY_PRIORITY, 0)                          // 0 = realtime priority
            setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt()) // "go as fast as you can"
            // Some Qualcomm encoders need an explicit bitrate-mode hint for stable CBR.
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
        }
        val c = MediaCodec.createEncoderByType(MIMETYPE)
        c.setCallback(callback, codecHandler)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = c.createInputSurface()
        codec = c
        inputSurface = surface
        return surface
    }

    fun start(onNal: (nal: ByteArray, ptsUs: Long, isKey: Boolean) -> Unit) {
        this.onNal = onNal
        codec?.start() ?: error("H264Encoder.prepare() must be called before start()")
    }

    /** Force an instantaneous keyframe; used when a new RTSP client connects. */
    fun requestKeyframe() {
        val c = codec ?: return
        val params = android.os.Bundle().apply {
            putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
        try { c.setParameters(params) } catch (t: Throwable) { Log.w(TAG, "requestKeyframe failed: ${t.message}") }
    }

    fun stop() {
        onNal = null
        val c = codec ?: return
        try { c.signalEndOfInputStream() } catch (_: Throwable) {}
        try { c.stop() } catch (_: Throwable) {}
        try { c.release() } catch (_: Throwable) {}
        codec = null
        try { inputSurface?.release() } catch (_: Throwable) {}
        inputSurface = null
    }

    fun shutdown() {
        stop()
        codecThread.quitSafely()
    }

    private val callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            // Surface-fed encoder — no input buffers from us.
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            val buf = try { codec.getOutputBuffer(index) } catch (_: Throwable) { null }
            if (buf == null) {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
                return
            }
            try {
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    // Codec config buffer: contains SPS+PPS concatenated with Annex-B start codes.
                    val csd = ByteArray(info.size).also {
                        buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(it)
                    }
                    val nals = splitAnnexB(csd)
                    var sps: ByteArray? = null
                    var pps: ByteArray? = null
                    for (n in nals) {
                        val type = n[0].toInt() and 0x1f
                        if (type == 7) sps = n
                        if (type == 8) pps = n
                    }
                    if (sps != null && pps != null) parameterSetsRef.set(ParameterSets(sps, pps))
                } else if (info.size > 0) {
                    val data = ByteArray(info.size).also {
                        buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(it)
                    }
                    val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    val nals = splitAnnexB(data)
                    val cb = onNal
                    if (cb != null) {
                        for (n in nals) cb(n, info.presentationTimeUs, isKey)
                    }
                    // Count VCL slices (NAL types 1-5) as "frames"; SPS/PPS/SEI don't count.
                    if (nals.any { (it[0].toInt() and 0x1F) in 1..5 }) {
                        framesEncoded.incrementAndGet()
                    }
                }
            } finally {
                try { codec.releaseOutputBuffer(index, false) } catch (_: Throwable) {}
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Encoder error: ${e.diagnosticInfo} (${e.errorCode})", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            // SPS/PPS may also arrive here via "csd-0"/"csd-1" — capture them as a fallback
            // in case onOutputBufferAvailable for the codec-config never fires (some devices).
            val csd0 = format.getByteBuffer("csd-0")
            val csd1 = format.getByteBuffer("csd-1")
            if (csd0 != null && csd1 != null) {
                val sps = stripStartCode(toByteArray(csd0))
                val pps = stripStartCode(toByteArray(csd1))
                if (sps.isNotEmpty() && pps.isNotEmpty()) parameterSetsRef.set(ParameterSets(sps, pps))
            }
        }
    }

    companion object {
        private const val TAG = "H264Encoder"
        const val MIMETYPE = MediaFormat.MIMETYPE_VIDEO_AVC

        /**
         * Splits an Annex-B byte stream (NALs separated by 00 00 00 01 or 00 00 01)
         * into raw NAL byte arrays (no start codes).
         */
        fun splitAnnexB(data: ByteArray): List<ByteArray> {
            val result = mutableListOf<ByteArray>()
            var i = 0
            var lastNalStart = -1
            while (i < data.size - 2) {
                val is3 = data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1
                val is4 = i + 3 < data.size && data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1
                if (is3 || is4) {
                    if (lastNalStart >= 0) {
                        val end = i
                        result.add(data.copyOfRange(lastNalStart, end))
                    }
                    lastNalStart = if (is4) i + 4 else i + 3
                    i = lastNalStart
                } else {
                    i++
                }
            }
            if (lastNalStart >= 0 && lastNalStart < data.size) {
                result.add(data.copyOfRange(lastNalStart, data.size))
            }
            return result
        }

        private fun stripStartCode(data: ByteArray): ByteArray {
            // CSD often has Annex-B start code prefix; strip it.
            if (data.size >= 4 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 0 && data[3].toInt() == 1) {
                return data.copyOfRange(4, data.size)
            }
            if (data.size >= 3 && data[0].toInt() == 0 && data[1].toInt() == 0 && data[2].toInt() == 1) {
                return data.copyOfRange(3, data.size)
            }
            return data
        }

        private fun toByteArray(b: ByteBuffer): ByteArray {
            val out = ByteArray(b.remaining())
            b.get(out)
            return out
        }
    }
}
