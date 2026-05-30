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
import guru.freberg.lenscast.streaming.AudioUtils
import java.util.concurrent.atomic.AtomicReference

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
    fun setGainDb(db: Int) { gainLinear = AudioUtils.dbToLinear(db) }

    @SuppressLint("MissingPermission")
    fun start(onSample: (data: ByteArray, ptsUs: Long) -> Unit) {
        if (running) return
        this.onSample = onSample

        // The AAC encoder doesn't need the mic, so start it unconditionally. The mic
        // (AudioRecord) is opened inside the capture loop and reopened if it's lost, so a mic
        // that's unavailable at start — or grabbed mid-stream by a phone call — degrades to
        // silence and recovers on its own instead of throwing and taking down the (video) stream.
        val format = MediaFormat.createAudioFormat(MIMETYPE, sampleRateHz, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192)
        }
        val c = try {
            MediaCodec.createEncoderByType(MIMETYPE).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
        } catch (t: Throwable) {
            Log.e(TAG, "AAC codec start failed: ${t.message}")
            return
        }
        codec = c
        running = true
        captureThread = Thread({ captureLoop() }, "LenscastAacCapture").also { it.start() }
    }

    /** Open (and start) the mic, attaching the configured effects. Returns null if it's
     *  unavailable — e.g. held by a phone call on devices that disallow concurrent capture; the
     *  capture loop retries, so audio resumes by itself once the mic is released. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(4 * 1024)
        return try {
            val ar = AudioRecord(audioSource, sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
            if (ar.state != AudioRecord.STATE_INITIALIZED) {
                try { ar.release() } catch (_: Throwable) {}
                return null
            }
            AudioUtils.attachAudioEffects(ar.audioSessionId, enableNoiseSuppress, enableEchoCancel)
                .let { (noiseSuppressor, echoCanceler) -> ns = noiseSuppressor; aec = echoCanceler }
            ar.startRecording()
            recorder = ar
            ar
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord open failed: ${t.message}")
            null
        }
    }

    private fun releaseRecorder() {
        try { ns?.release() } catch (_: Throwable) {}
        ns = null
        try { aec?.release() } catch (_: Throwable) {}
        aec = null
        val r = recorder
        recorder = null
        try { r?.stop() } catch (_: Throwable) {}
        try { r?.release() } catch (_: Throwable) {}
    }

    private fun captureLoop() {
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val pcm = ByteArray(
            AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
                .coerceAtLeast(4 * 1024),
        )
        val info = MediaCodec.BufferInfo()
        var readFailures = 0
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

            // Read PCM and queue input. Open the mic lazily and reopen it if it was lost, so a
            // phone call grabbing the mic mid-stream becomes silence-then-recovery, never a crash.
            val ar = recorder ?: openRecorder()
            if (ar == null) {
                // Mic unavailable (e.g. a call holds it). Wait and retry; video is unaffected and
                // audio resumes automatically once the mic is released.
                peakDbfs.set(-90f)
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                continue
            }
            val n = try { ar.read(pcm, 0, pcm.size) } catch (_: Throwable) { -1 }
            if (n <= 0) {
                // Sustained read failure means the mic was taken — drop the recorder so the next
                // iteration reopens it; a few transient misses just back off briefly.
                if (++readFailures >= READ_FAIL_REOPEN) { releaseRecorder(); readFailures = 0 }
                else try { Thread.sleep(20) } catch (_: InterruptedException) {}
                continue
            }
            readFailures = 0
            if (muted) java.util.Arrays.fill(pcm, 0, n, 0)
            peakDbfs.set(AudioUtils.applyGainAndMeter(pcm, n, gainLinear))
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
        // Join first so the loop can't reopen the mic after we release it (it may be sleeping up
        // to 200 ms on the mic-unavailable backoff).
        try { captureThread?.join(300) } catch (_: Throwable) {}
        captureThread = null
        releaseRecorder()
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        codec = null
        peakDbfs.set(-90f)
    }

    /** Shutdown alias kept for symmetry with [H264Encoder]; no thread to quit here. */
    fun shutdown() {
        stop()
    }

    companion object {
        private const val TAG = "AacEncoder"
        const val MIMETYPE = MediaFormat.MIMETYPE_AUDIO_AAC
        /** Consecutive failed mic reads before the recorder is dropped + reopened (~0.5 s at the
         *  20 ms backoff). High enough to ride out transient misses, low enough to recover fast. */
        private const val READ_FAIL_REOPEN = 25
    }
}
