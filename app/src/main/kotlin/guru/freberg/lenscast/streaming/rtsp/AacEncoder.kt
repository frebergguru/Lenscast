package guru.freberg.lenscast.streaming.rtsp

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.max

/**
 * AAC-LC encoder fed by a mic [AudioRecord]. Output is a stream of raw AAC access units
 * (no ADTS header) suitable for RTP packetization per RFC 3640. The first config buffer
 * gives us `AudioSpecificConfig` which the RTSP layer needs for the SDP `config=` field.
 *
 * Uses MediaCodec in **synchronous** mode: a single capture thread reads PCM from the
 * mic, queues input buffers, and drains output buffers in the same tick. Mixing async
 * callbacks with `dequeueInputBuffer` is illegal and would silently drop input frames.
 *
 * Caller must hold `RECORD_AUDIO` permission before instantiating this class.
 */
class AacEncoder(
    private val sampleRateHz: Int = 44_100,
    private val channels: Int = 1,
    private val bitrateBps: Int = 96_000,
    /** One of `MediaRecorder.AudioSource.*`. Default = VOICE_RECOGNITION (the historical pick). */
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    /** Linear gain factor (1.0 = unity). Applied to each PCM sample before encode. */
    @Volatile private var gainLinear: Float = 1f,
    private val enableNoiseSuppress: Boolean = false,
    private val enableEchoCancel: Boolean = false,
) {
    /** AudioSpecificConfig bytes from the encoder (typically 2 bytes for AAC-LC). */
    @Volatile var asc: ByteArray? = null
        private set

    val sampleRate: Int get() = sampleRateHz
    val channelCount: Int get() = channels

    /**
     * Last buffer's peak amplitude as dBFS (-∞..0). Clamped at -90 so the UI doesn't
     * have to worry about negative-infinity rendering. Updated every captured PCM buffer.
     */
    private val peakDbfs = AtomicReference(-90f)
    fun lastPeakDbfs(): Float = peakDbfs.get()

    /** Hard-mute (true silence on the wire) — used by the privacy / call-handling path. */
    @Volatile var muted: Boolean = false

    private var codec: MediaCodec? = null
    private var recorder: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    @Volatile private var running = false
    @Volatile private var captureThread: Thread? = null
    @Volatile private var onSample: ((data: ByteArray, ptsUs: Long) -> Unit)? = null
    @Volatile private var samplesEnqueued: Long = 0

    /** Update gain live without restarting the encoder. */
    fun setGainDb(db: Int) { gainLinear = dbToLinear(db) }

    @SuppressLint("MissingPermission")
    fun start(onSample: (data: ByteArray, ptsUs: Long) -> Unit) {
        if (running) return
        this.onSample = onSample

        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4 * 1024)
        val ar = AudioRecord(
            audioSource,
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed (state=${ar.state})")
            try { ar.release() } catch (_: Throwable) {}
            return
        }
        recorder = ar
        attachAudioEffects(ar.audioSessionId)

        val format = MediaFormat.createAudioFormat(MIMETYPE, sampleRateHz, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val c = MediaCodec.createEncoderByType(MIMETYPE)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec = c
        c.start()
        ar.startRecording()
        running = true

        captureThread = Thread({ captureLoop(ar, minBuf) }, "LenscastAacCapture").also { it.start() }
    }

    private fun captureLoop(ar: AudioRecord, bufSize: Int) {
        val pcm = ByteArray(bufSize)
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break

            // Drain any encoded output buffers first.
            while (running) {
                val outIdx = try { c.dequeueOutputBuffer(info, 0) } catch (_: Throwable) { break }
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val fmt = c.outputFormat
                    val csd0 = fmt.getByteBuffer("csd-0")
                    if (csd0 != null) {
                        asc = ByteArray(csd0.remaining()).also { csd0.get(it) }
                    }
                    continue
                }
                if (outIdx < 0) break
                try {
                    val buf = c.getOutputBuffer(outIdx)
                    if (buf != null) {
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            asc = ByteArray(info.size).also {
                                buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(it)
                            }
                        } else if (info.size > 0) {
                            val data = ByteArray(info.size).also {
                                buf.position(info.offset); buf.limit(info.offset + info.size); buf.get(it)
                            }
                            onSample?.invoke(data, info.presentationTimeUs)
                        }
                    }
                } finally {
                    try { c.releaseOutputBuffer(outIdx, false) } catch (_: Throwable) {}
                }
            }

            // Read PCM and queue input.
            val n = try { ar.read(pcm, 0, pcm.size) } catch (_: Throwable) { -1 }
            if (n <= 0) continue
            if (muted) java.util.Arrays.fill(pcm, 0, n, 0)
            applyGainAndMeter(pcm, n, gainLinear)
            val inIdx = try { c.dequeueInputBuffer(10_000) } catch (_: Throwable) { -1 }
            if (inIdx < 0) continue
            try {
                val buf = c.getInputBuffer(inIdx) ?: continue
                buf.clear()
                buf.put(pcm, 0, n)
                val ptsUs = (samplesEnqueued * 1_000_000L) / sampleRateHz
                c.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                samplesEnqueued += (n / 2) / channels
            } catch (t: Throwable) {
                Log.w(TAG, "queueInputBuffer failed: ${t.message}")
            }
        }
    }

    fun stop() {
        running = false
        onSample = null
        try { captureThread?.join(200) } catch (_: Throwable) {}
        captureThread = null
        try { ns?.release() } catch (_: Throwable) {}
        ns = null
        try { aec?.release() } catch (_: Throwable) {}
        aec = null
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        peakDbfs.set(-90f)
    }

    private fun attachAudioEffects(sessionId: Int) {
        if (enableNoiseSuppress && NoiseSuppressor.isAvailable()) {
            try { ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true } }
            catch (t: Throwable) { Log.w(TAG, "NS attach failed: ${t.message}") }
        }
        if (enableEchoCancel && AcousticEchoCanceler.isAvailable()) {
            try { aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }
            catch (t: Throwable) { Log.w(TAG, "AEC attach failed: ${t.message}") }
        }
    }

    /**
     * Multiply each PCM-16LE sample by [linear], hard-clamping to [-32768, 32767], and
     * record the buffer's peak amplitude to [peakDbfs] for the UI VU meter. Skips the
     * scaling pass entirely when [linear] is unity (the common case) — only the peak
     * scan still runs.
     */
    private fun applyGainAndMeter(buf: ByteArray, validBytes: Int, linear: Float) {
        var peak = 0
        val unity = linear == 1f
        var i = 0
        // PCM-16LE pairs: low byte then high byte. Read as signed short, scale, write back.
        while (i + 1 < validBytes) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()  // signed extension wanted
            var sample = (hi shl 8) or lo
            if (sample > 32767) sample -= 65536  // sign-extend the int from a 16-bit pattern
            if (!unity) {
                val scaled = (sample * linear).toInt()
                sample = when {
                    scaled > 32767  -> 32767
                    scaled < -32768 -> -32768
                    else            -> scaled
                }
                buf[i] = (sample and 0xFF).toByte()
                buf[i + 1] = ((sample ushr 8) and 0xFF).toByte()
            }
            val abs = if (sample < 0) -sample else sample
            if (abs > peak) peak = abs
            i += 2
        }
        // 32767 → 0 dBFS; floor at -90 to keep the meter readable.
        val dbfs = if (peak == 0) -90f
                   else max(-90f, 20f * log10(peak / 32768f))
        peakDbfs.set(dbfs)
    }

    private fun dbToLinear(db: Int): Float = 10.0.pow(db / 20.0).toFloat()

    /** Shutdown alias kept for symmetry with [H264Encoder]; no thread to quit here. */
    fun shutdown() {
        stop()
    }

    companion object {
        private const val TAG = "AacEncoder"
        const val MIMETYPE = MediaFormat.MIMETYPE_AUDIO_AAC
    }
}
