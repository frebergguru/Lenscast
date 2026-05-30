package guru.freberg.lenscast.streaming

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Direct PCM-16LE capture for the MJPEG audio sidecar. Skips MediaCodec entirely —
 * AAC's codec lookahead (~20 ms) plus receiver-side AAC buffering (browsers and VLC
 * stockpile half a second of AUs before starting playback) added up to multi-hundred-ms
 * lag that was unacceptable for a live monitor.
 *
 * PCM-16LE is universally understood and players treat WAV (the container we wrap it in
 * at the HTTP layer) as a live stream with near-zero buffer. 44.1 kHz mono = ~88 KB/s,
 * trivial next to the MJPEG video bandwidth.
 *
 * Mirrors [guru.freberg.lenscast.streaming.rtsp.AacEncoder] for the gain / NS / AEC / mic-source
 * knobs so the same Settings UI applies. Caller must hold `RECORD_AUDIO`.
 */
class PcmCapture(
    private val sampleRateHz: Int = 44_100,
    private val channels: Int = 1,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    @Volatile private var gainLinear: Float = 1f,
    private val enableNoiseSuppress: Boolean = false,
    private val enableEchoCancel: Boolean = false,
) {
    val sampleRate: Int get() = sampleRateHz
    val channelCount: Int get() = channels

    private val peakDbfs = AtomicReference(-90f)
    fun lastPeakDbfs(): Float = peakDbfs.get()

    /**
     * Hard-mute toggle used by the call-handling path. When true the captured PCM buffer
     * is zeroed before gain + meter, so the broadcasted stream is true silence (not just
     * inaudibly quiet); the VU drops to -90 dBFS for the duration.
     */
    @Volatile var muted: Boolean = false

    private var recorder: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    @Volatile private var running = false
    @Volatile private var captureThread: Thread? = null

    fun setGainDb(db: Int) { gainLinear = AudioUtils.dbToLinear(db) }

    @SuppressLint("MissingPermission")
    fun start(onPcm: (ByteArray) -> Unit) {
        if (running) return
        running = true
        // The mic is opened inside the capture thread and reopened if lost, so a mic that's
        // unavailable at start — or grabbed mid-stream by a phone call — degrades to silence and
        // recovers on its own instead of throwing.
        captureThread = Thread({ captureLoop(onPcm) }, "LenscastPcmCapture").also { it.start() }
    }

    /** Open (and start) the mic. Returns null if it's unavailable (e.g. a call holds it); the
     *  capture loop retries so audio resumes once it's released. */
    @SuppressLint("MissingPermission")
    private fun openRecorder(): AudioRecord? {
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        // Use the minimum buffer — anything larger just adds capture latency without
        // affecting throughput on a mic that already delivers samples in real time.
        val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(2 * 1024)
        return try {
            val ar = AudioRecord(audioSource, sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT, minBuf)
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

    private fun captureLoop(onPcm: (ByteArray) -> Unit) {
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(2 * 1024)
        // Emit in ~10 ms chunks (441 samples mono) for a tight latency profile — read at half
        // minBuf: smaller wastes CPU on syscalls, larger adds audible delay.
        val readSize = minOf(minBuf / 2, 882 * channels)
        val buf = ByteArray(readSize)
        var readFailures = 0
        while (running) {
            val ar = recorder ?: openRecorder()
            if (ar == null) {
                // Mic unavailable (e.g. a call holds it). Wait and retry; audio resumes by itself.
                peakDbfs.set(-90f)
                try { Thread.sleep(200) } catch (_: InterruptedException) {}
                continue
            }
            val n = try { ar.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
            if (n <= 0) {
                // Sustained failure means the mic was taken — drop + reopen on the next iteration.
                if (++readFailures >= READ_FAIL_REOPEN) { releaseRecorder(); readFailures = 0 }
                else try { Thread.sleep(20) } catch (_: InterruptedException) {}
                continue
            }
            readFailures = 0
            if (muted) java.util.Arrays.fill(buf, 0, n, 0)
            peakDbfs.set(AudioUtils.applyGainAndMeter(buf, n, gainLinear))
            if (n == buf.size) onPcm(buf.copyOf())
            else onPcm(buf.copyOf(n))
        }
    }

    fun stop() {
        running = false
        // Join first so the loop can't reopen the mic after we release it (it may be sleeping up
        // to 200 ms on the mic-unavailable backoff).
        try { captureThread?.join(300) } catch (_: Throwable) {}
        captureThread = null
        releaseRecorder()
        peakDbfs.set(-90f)
    }

    companion object {
        private const val TAG = "PcmCapture"
        /** Consecutive failed mic reads before the recorder is dropped + reopened (~0.5 s). */
        private const val READ_FAIL_REOPEN = 25
    }
}
