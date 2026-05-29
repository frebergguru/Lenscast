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
        val channelConfig = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        // Use the minimum buffer — anything larger just adds capture latency without
        // affecting throughput on a mic that already delivers samples in real time.
        val minBuf = AudioRecord.getMinBufferSize(sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT)
            .coerceAtLeast(2 * 1024)
        val ar = AudioRecord(
            audioSource,
            sampleRateHz, channelConfig, AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
        )
        if (ar.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord init failed (state=${ar.state})")
            try { ar.release() } catch (_: Throwable) {}
            return
        }
        recorder = ar
        AudioUtils.attachAudioEffects(ar.audioSessionId, enableNoiseSuppress, enableEchoCancel)
            .let { (noiseSuppressor, echoCanceler) -> ns = noiseSuppressor; aec = echoCanceler }
        ar.startRecording()
        running = true
        // Emit in ~10 ms chunks (441 samples mono) for a tight latency profile. Chosen
        // by reading at half minBuf — any smaller and we waste CPU on syscalls, any
        // larger and we add audible delay.
        val readSize = minOf(minBuf / 2, 882 * channels)
        captureThread = Thread({
            val buf = ByteArray(readSize)
            while (running) {
                val n = try { ar.read(buf, 0, buf.size) } catch (_: Throwable) { -1 }
                if (n <= 0) continue
                if (muted) java.util.Arrays.fill(buf, 0, n, 0)
                peakDbfs.set(AudioUtils.applyGainAndMeter(buf, n, gainLinear))
                if (n == buf.size) onPcm(buf.copyOf())
                else onPcm(buf.copyOf(n))
            }
        }, "LenscastPcmCapture").also { it.start() }
    }

    fun stop() {
        running = false
        try { captureThread?.join(200) } catch (_: Throwable) {}
        captureThread = null
        try { ns?.release() } catch (_: Throwable) {}
        ns = null
        try { aec?.release() } catch (_: Throwable) {}
        aec = null
        try { recorder?.stop() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        peakDbfs.set(-90f)
    }

    companion object {
        private const val TAG = "PcmCapture"
    }
}
