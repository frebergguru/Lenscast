package guru.freberg.lenscast.streaming

import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import guru.freberg.lenscast.prefs.MicSource
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Shared mic / PCM helpers used by every audio path (the MJPEG sidecar [PcmCapture] plus
 * the AAC encoder feeding RTSP and SRT). These were copy-pasted across each capture class;
 * keeping one copy means the gain math and the VU meter stay in lock-step everywhere.
 */
object AudioUtils {
    private const val TAG = "AudioUtils"

    /** Convert a dB gain setting to a linear multiplier (0 dB → 1.0). */
    fun dbToLinear(db: Int): Float = 10.0.pow(db / 20.0).toFloat()

    /** Map the user-facing [MicSource] enum to a `MediaRecorder.AudioSource.*` constant. */
    fun audioSourceFor(src: MicSource): Int = when (src) {
        MicSource.CAMCORDER           -> MediaRecorder.AudioSource.CAMCORDER
        MicSource.MIC                 -> MediaRecorder.AudioSource.MIC
        MicSource.VOICE_RECOGNITION   -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        MicSource.UNPROCESSED         -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    }

    /**
     * Multiply each PCM-16LE sample in [buf] (first [validBytes] bytes) by [linear],
     * hard-clamping to [-32768, 32767], and return the buffer's peak amplitude as dBFS
     * (-90..0). Skips the scaling pass entirely when [linear] is unity (the common case) —
     * only the peak scan still runs. The NV21-style copy isn't needed here; we mutate in place.
     */
    fun applyGainAndMeter(buf: ByteArray, validBytes: Int, linear: Float): Float {
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
        return if (peak == 0) -90f else max(-90f, 20f * log10(peak / 32768f))
    }

    /**
     * Best-effort attach of NoiseSuppressor / AcousticEchoCanceler to a capture session.
     * Returns the created effects (either may be null) so the caller can release them on stop.
     */
    fun attachAudioEffects(
        sessionId: Int,
        enableNoiseSuppress: Boolean,
        enableEchoCancel: Boolean,
    ): Pair<NoiseSuppressor?, AcousticEchoCanceler?> {
        var ns: NoiseSuppressor? = null
        var aec: AcousticEchoCanceler? = null
        if (enableNoiseSuppress && NoiseSuppressor.isAvailable()) {
            try { ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true } }
            catch (t: Throwable) { Log.w(TAG, "NS attach failed: ${t.message}") }
        }
        if (enableEchoCancel && AcousticEchoCanceler.isAvailable()) {
            try { aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }
            catch (t: Throwable) { Log.w(TAG, "AEC attach failed: ${t.message}") }
        }
        return ns to aec
    }
}
