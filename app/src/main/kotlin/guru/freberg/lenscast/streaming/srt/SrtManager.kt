package guru.freberg.lenscast.streaming.srt

import android.content.Context
import android.media.MediaRecorder
import android.util.Log
import android.util.Size
import android.view.Surface
import guru.freberg.lenscast.prefs.Lens
import guru.freberg.lenscast.prefs.MicSource
import guru.freberg.lenscast.streaming.rtsp.AacEncoder
import guru.freberg.lenscast.streaming.rtsp.H264Encoder
import guru.freberg.lenscast.streaming.rtsp.RtspCameraDriver

/**
 * Orchestrates the SRT streaming path:
 *
 *  Camera2 (via [RtspCameraDriver]) → MediaCodec H.264 → MpegTsMuxer → SrtPublisher
 *  AudioRecord → MediaCodec AAC ─────────────────────────┘
 *
 * Mirrors what [RtspManager] does for the RTSP path but emits MPEG-TS over an SRT socket
 * instead of RTP/RTSP. The Camera2 driver and encoders are reused as-is — the only new
 * bits are the TS muxer and the SRT publisher.
 */
class SrtManager(private val context: Context) {

    data class Config(
        val lens: Lens,
        val resolution: Size,
        val fps: Int,
        val videoBitrateBps: Int,
        val audioEnabled: Boolean,
        val deviceRotationDegrees: Int = 0,
        val micSource: MicSource = MicSource.VOICE_RECOGNITION,
        val audioGainDb: Int = 0,
        val noiseSuppress: Boolean = false,
        val echoCancel: Boolean = false,
        // SRT transport
        val srtMode: SrtPublisher.Mode,
        val srtHost: String,
        val srtPort: Int,
        val srtPassphrase: String,
        val srtLatencyMs: Int,
        val srtStreamId: String,
    )

    private var videoEncoder: H264Encoder? = null
    private var audioEncoder: AacEncoder? = null
    private var camera: RtspCameraDriver? = null
    private var muxer: MpegTsMuxer? = null
    private var publisher: SrtPublisher? = null
    @Volatile private var state: SrtPublisher.State = SrtPublisher.State.IDLE

    fun state(): SrtPublisher.State = state

    suspend fun start(config: Config, previewSurface: Surface?): RtspCameraDriver.Plan? {
        stop()
        val camDriver = RtspCameraDriver(context).also { camera = it }
        val plan = camDriver.plan(config.lens, config.resolution, config.fps) ?: run {
            Log.e(TAG, "No camera plan for SRT path")
            return null
        }
        val encoderRotation = ((plan.sensorOrientation - config.deviceRotationDegrees) + 360) % 360

        // Forward-declared so the publisher's onState callback can reset the muxer +
        // request a fresh keyframe the moment a new receiver connects.
        var lateMuxer: MpegTsMuxer? = null
        val pub = SrtPublisher(
            SrtPublisher.Config(
                mode = config.srtMode,
                host = if (config.srtMode == SrtPublisher.Mode.CALLER) config.srtHost else "0.0.0.0",
                port = config.srtPort,
                passphrase = config.srtPassphrase,
                latencyMs = config.srtLatencyMs,
                streamId = config.srtStreamId,
            ),
            onState = { st ->
                state = st
                if (st == SrtPublisher.State.CONNECTED) {
                    // Re-arm: the next emitted video PES must be a keyframe with
                    // SPS+PPS, and we request a fresh IDR from the encoder so we don't
                    // have to wait up to the natural GOP for the receiver to start
                    // decoding. PSI is also re-sent immediately so PAT/PMT lands
                    // ahead of the first PES.
                    lateMuxer?.resetForNewClient()
                    try { videoEncoder?.requestKeyframe() } catch (_: Throwable) {}
                }
            },
        ).also { publisher = it; it.start() }

        val tsMuxer = MpegTsMuxer { pkt -> pub.send(pkt) }.also { muxer = it; lateMuxer = it }

        // The H.264 and AAC encoders deliver PTS values in DIFFERENT clock domains:
        //   - H.264 PTS comes from the Camera2 input surface — elapsedRealtimeNanos / 1000,
        //     which is microseconds since boot (can be 70 000 s+ on a phone that's been up
        //     a while).
        //   - AAC PTS comes from AudioRecord — microseconds since AudioRecord.startRecording,
        //     so much smaller absolute values.
        // ffplay saw `A-V:-70832 s` on first connect — totally unplayable. Both streams need
        // a single shared wall-clock origin. We capture the first PTS each encoder emits and
        // anchor it to the wall-clock interval since stream start, preserving frame-to-frame
        // deltas inside each stream and aligning audio + video at receipt time.
        val streamStartNs = System.nanoTime()
        // AtomicLong instead of @Volatile vars — @Volatile only applies to fields, not
        // captured locals. AtomicLong gives us the same volatile-write semantics inside
        // the encoder callbacks, which run on a MediaCodec worker thread.
        val videoPtsOffsetUs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        val audioPtsOffsetUs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)

        // Video pipeline — pump every NAL into the muxer.
        val ve = H264Encoder(
            width = plan.size.width,
            height = plan.size.height,
            frameRate = plan.fpsRange.upper,
            bitrateBps = config.videoBitrateBps,
            rotationDegrees = encoderRotation,
        ).also { videoEncoder = it }
        val encoderSurface = ve.prepare()
        ve.start { nal, ptsUs, isKey ->
            val ps = ve.parameterSets
            if (ps != null) {
                tsMuxer.sps = ps.sps
                tsMuxer.pps = ps.pps
            }
            val nalType = nal[0].toInt() and 0x1F
            // SPS/PPS NALs themselves don't need to be in PES — we prepend them in the
            // muxer on each keyframe — so skip them here. Everything else is a VCL NAL.
            if (nalType in 1..5) {
                videoPtsOffsetUs.compareAndSet(
                    Long.MIN_VALUE,
                    (System.nanoTime() - streamStartNs) / 1000L - ptsUs,
                )
                tsMuxer.writeVideoAu(listOf(nal), ptsUs + videoPtsOffsetUs.get(), isKey)
            }
        }

        if (config.audioEnabled) {
            val ae = AacEncoder(
                audioSource = audioSourceFor(config.micSource),
                gainLinear = dbToLinear(config.audioGainDb),
                enableNoiseSuppress = config.noiseSuppress,
                enableEchoCancel = config.echoCancel,
            ).also { audioEncoder = it }
            ae.start { au, ptsUs ->
                ae.asc?.let { asc ->
                    tsMuxer.asc = asc
                    tsMuxer.audioSampleRate = ae.sampleRate
                    tsMuxer.audioChannels = ae.channelCount
                }
                audioPtsOffsetUs.compareAndSet(
                    Long.MIN_VALUE,
                    (System.nanoTime() - streamStartNs) / 1000L - ptsUs,
                )
                tsMuxer.writeAudioAu(au, ptsUs + audioPtsOffsetUs.get())
            }
        }

        camDriver.start(plan, encoderSurface, previewSurface)
        // Force an IDR ~immediately so receivers don't have to wait for the encoder's
        // natural GOP boundary (usually 2–5 s). VLC's TSBPD window otherwise starts ticking
        // before the first decodable frame arrives, and the "non-existing PPS 0 referenced"
        // warnings flood the receiver log.
        try { videoEncoder?.requestKeyframe() } catch (_: Throwable) {}
        return plan
    }

    fun setTorch(on: Boolean) { camera?.setTorch(on) }
    fun setAudioMuted(muted: Boolean) { audioEncoder?.muted = muted }
    fun audioPeakDbfs(): Float = audioEncoder?.lastPeakDbfs() ?: -90f
    fun framesProduced(): Long = videoEncoder?.framesProduced() ?: 0L
    fun bytesSent(): Long = publisher?.bytesSent() ?: 0L
    fun connectionState(): SrtPublisher.State = state

    fun stop() {
        try { camera?.stop() } catch (_: Throwable) {}
        camera = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.shutdown() } catch (_: Throwable) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.shutdown() } catch (_: Throwable) {}
        audioEncoder = null
        try { publisher?.stop() } catch (_: Throwable) {}
        publisher = null
        muxer = null
        state = SrtPublisher.State.IDLE
    }

    private fun audioSourceFor(src: MicSource): Int = when (src) {
        MicSource.CAMCORDER           -> MediaRecorder.AudioSource.CAMCORDER
        MicSource.MIC                 -> MediaRecorder.AudioSource.MIC
        MicSource.VOICE_RECOGNITION   -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        MicSource.UNPROCESSED         -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else MediaRecorder.AudioSource.MIC
    }

    private fun dbToLinear(db: Int): Float = Math.pow(10.0, db / 20.0).toFloat()

    companion object {
        private const val TAG = "SrtManager"
    }
}
