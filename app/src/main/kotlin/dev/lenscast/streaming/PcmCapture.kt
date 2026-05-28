package dev.lenscast.streaming

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

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
 * Mirrors [dev.lenscast.streaming.rtsp.AacEncoder] for the gain / NS / AEC / mic-source
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

    private var recorder: AudioRecord? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    @Volatile private var running = false
    @Volatile private var captureThread: Thread? = null

    fun setGainDb(db: Int) { gainLinear = 10.0.pow(db / 20.0).toFloat() }

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
        attachAudioEffects(ar.audioSessionId)
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
                applyGainAndMeter(buf, n, gainLinear)
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

    /** Same shape as the version inside AacEncoder — see it for the bit-twiddling notes. */
    private fun applyGainAndMeter(buf: ByteArray, validBytes: Int, linear: Float) {
        var peak = 0
        val unity = linear == 1f
        var i = 0
        while (i + 1 < validBytes) {
            val lo = buf[i].toInt() and 0xFF
            val hi = buf[i + 1].toInt()
            var sample = (hi shl 8) or lo
            if (sample > 32767) sample -= 65536
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
        val dbfs = if (peak == 0) -90f else max(-90f, 20f * log10(peak / 32768f))
        peakDbfs.set(dbfs)
    }

    companion object {
        private const val TAG = "PcmCapture"
    }
}
