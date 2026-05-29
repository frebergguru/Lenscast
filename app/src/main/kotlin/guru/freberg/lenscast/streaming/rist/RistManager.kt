package guru.freberg.lenscast.streaming.rist

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import guru.freberg.lenscast.prefs.Lens
import guru.freberg.lenscast.prefs.MicSource
import guru.freberg.lenscast.streaming.AudioUtils
import guru.freberg.lenscast.streaming.ImageControls
import guru.freberg.lenscast.streaming.RecordingMuxer
import guru.freberg.lenscast.streaming.rtsp.AacEncoder
import guru.freberg.lenscast.streaming.rtsp.H264Encoder
import guru.freberg.lenscast.streaming.rtsp.RtspCameraDriver
import guru.freberg.lenscast.streaming.srt.MpegTsMuxer

/**
 * Orchestrates the RIST (Simple Profile) streaming path:
 *
 *  Camera2 (via [RtspCameraDriver]) → MediaCodec H.264 → MpegTsMuxer → RistPublisher
 *  AudioRecord → MediaCodec AAC ─────────────────────────┘
 *
 * Structurally identical to [guru.freberg.lenscast.streaming.srt.SrtManager] — same camera
 * driver, same GL rotation stage, same encoders, same hand-rolled [MpegTsMuxer]. The only
 * difference is the egress: RTP/MP2T over UDP with RTCP-NACK retransmit ([RistPublisher])
 * instead of an SRT socket. Mid-stream rotation and lens switching reuse the SRT path's
 * "rebuild video only, keep the transport + muxer + audio alive" approach.
 */
class RistManager(private val context: Context) {

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
        val recordLocally: Boolean = false,
        // RIST transport
        val ristMode: RistPublisher.Mode,
        val ristHost: String,
        val ristPort: Int,
        val ristBufferMs: Int,
        val ristProfile: RistPublisher.Profile = RistPublisher.Profile.SIMPLE,
        val ristPassphrase: String = "",
        val ristKeyBits: Int = 128,
        val imageControls: ImageControls = ImageControls(),
    )

    private var videoEncoder: H264Encoder? = null
    private var audioEncoder: AacEncoder? = null
    private var camera: RtspCameraDriver? = null
    private var muxer: MpegTsMuxer? = null
    private var publisher: RistPublisher? = null
    private var rotator: guru.freberg.lenscast.streaming.GlRotator? = null
    private var recorder: RecordingMuxer? = null
    @Volatile private var lastRecordedUri: android.net.Uri? = null
    @Volatile private var state: RistPublisher.State = RistPublisher.State.IDLE

    @Volatile private var currentConfig: Config? = null
    @Volatile private var currentPlan: RtspCameraDriver.Plan? = null
    @Volatile private var heldPreviewSurface: Surface? = null
    // Optional MJPEG-sidecar output. Re-applied on every camera (re)start so a mid-stream
    // rotation / lens switch keeps feeding the browser preview. Owned by StreamingService.
    @Volatile private var heldSidecarSurface: Surface? = null

    // Shared wall-clock origin for both encoders' PTS — see the long note in SrtManager.start.
    private var streamStartNs: Long = 0L
    private val videoPtsOffsetUs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private val audioPtsOffsetUs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)

    fun state(): RistPublisher.State = state

    suspend fun start(config: Config, previewSurface: Surface?, sidecarSurface: Surface? = null): RtspCameraDriver.Plan? {
        stop()
        val camDriver = RtspCameraDriver(context).also { camera = it }
        val plan = camDriver.plan(config.lens, config.resolution, config.fps) ?: run {
            Log.e(TAG, "No camera plan for RIST path")
            return null
        }
        currentConfig = config
        currentPlan = plan
        heldPreviewSurface = previewSurface
        heldSidecarSurface = sidecarSurface

        var lateMuxer: MpegTsMuxer? = null
        val pub = RistPublisher(
            RistPublisher.Config(
                mode = config.ristMode,
                host = if (config.ristMode == RistPublisher.Mode.CALLER) config.ristHost else "0.0.0.0",
                port = config.ristPort,
                bufferMs = config.ristBufferMs,
                profile = config.ristProfile,
                passphrase = config.ristPassphrase,
                keyBits = config.ristKeyBits,
            ),
            onState = { st ->
                state = st
                if (st == RistPublisher.State.CONNECTED) {
                    // Re-arm so the next emitted PES is an SPS+PPS keyframe and PAT/PMT lands
                    // ahead of it; request a fresh IDR so the receiver decodes without waiting
                    // a whole GOP. Same handshake-completion logic as the SRT path.
                    lateMuxer?.resetForNewClient()
                    try { videoEncoder?.requestKeyframe() } catch (_: Throwable) {}
                }
            },
        ).also { publisher = it; it.start() }

        val tsMuxer = MpegTsMuxer { pkt -> pub.send(pkt) }.also { muxer = it; lateMuxer = it }

        streamStartNs = System.nanoTime()
        videoPtsOffsetUs.set(Long.MIN_VALUE)
        audioPtsOffsetUs.set(Long.MIN_VALUE)

        // Set up local recording before the encoders start so we don't miss SPS/PPS arrival.
        // The MP4 muxer is fed the SAME wall-clock-anchored PTS as the MPEG-TS muxer (audio and
        // video share the streamStartNs origin), so the recording preserves the true A/V offset
        // — RecordingMuxer rebases both tracks against one shared origin, not per-track.
        if (config.recordLocally) {
            recorder = RecordingMuxer.create(context, expectAudio = config.audioEnabled)
            if (recorder == null) {
                Log.w(TAG, "Local recording requested but MediaStore insert failed; stream continues without recording")
            }
        }

        if (config.audioEnabled) {
            val ae = AacEncoder(
                audioSource = AudioUtils.audioSourceFor(config.micSource),
                gainLinear = AudioUtils.dbToLinear(config.audioGainDb),
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
                val anchoredPtsUs = ptsUs + audioPtsOffsetUs.get()
                recorder?.let { rec ->
                    ae.asc?.let { asc -> rec.addAudioTrack(ae.sampleRate, ae.channelCount, asc) }
                    rec.writeAudio(au, anchoredPtsUs)
                }
                tsMuxer.writeAudioAu(au, anchoredPtsUs)
            }
        }

        startVideoPipeline(config.deviceRotationDegrees)
        return plan
    }

    /** See [guru.freberg.lenscast.streaming.srt.SrtManager.startVideoPipeline] — identical GL
     *  rotation maths and encoder setup; only the muxer's downstream sink differs. */
    private suspend fun startVideoPipeline(deviceRotationDegrees: Int) {
        val plan = currentPlan ?: return
        val config = currentConfig ?: return
        val tsMuxer = muxer ?: return
        val cam = camera ?: return

        val glRotation = (360 - deviceRotationDegrees) % 360
        val portraitOutput = deviceRotationDegrees == 0 || deviceRotationDegrees == 180
        val encoderW = if (portraitOutput) plan.size.height else plan.size.width
        val encoderH = if (portraitOutput) plan.size.width else plan.size.height

        val ve = H264Encoder(
            width = encoderW,
            height = encoderH,
            frameRate = plan.fpsRange.upper,
            bitrateBps = config.videoBitrateBps,
            rotationDegrees = 0,
            iFrameIntervalSeconds = 1,
        ).also { videoEncoder = it }
        val encoderSurface = ve.prepare()
        val glRotator = guru.freberg.lenscast.streaming.GlRotator(
            encoderSurface = encoderSurface,
            encoderWidth = encoderW,
            encoderHeight = encoderH,
            cameraBufferWidth = plan.size.width,
            cameraBufferHeight = plan.size.height,
            rotationDegrees = glRotation,
            mirror = config.imageControls.mirror,
        ).also { rotator = it }
        ve.start { nal, ptsUs, isKey ->
            val ps = ve.parameterSets
            if (ps != null) {
                tsMuxer.sps = ps.sps
                tsMuxer.pps = ps.pps
            }
            val nalType = nal[0].toInt() and 0x1F
            if (nalType in 1..5) {
                videoPtsOffsetUs.compareAndSet(
                    Long.MIN_VALUE,
                    (System.nanoTime() - streamStartNs) / 1000L - ptsUs,
                )
                val anchoredPtsUs = ptsUs + videoPtsOffsetUs.get()
                recorder?.let { rec ->
                    if (ps != null) rec.addVideoTrack(encoderW, encoderH, ps.sps, ps.pps)
                    rec.writeVideo(nal, anchoredPtsUs, isKey)
                }
                tsMuxer.writeVideoAu(listOf(nal), anchoredPtsUs, isKey)
            }
        }

        cam.start(plan, glRotator.cameraSurface, heldPreviewSurface, heldSidecarSurface, imageControls = config.imageControls)
        try { videoEncoder?.requestKeyframe() } catch (_: Throwable) {}
    }

    /** Mid-stream rotation without dropping the receiver — see the SRT path for the rationale. */
    suspend fun reconfigureVideo(deviceRotationDegrees: Int) {
        val config = currentConfig ?: return
        if (camera == null || muxer == null) return
        if (config.deviceRotationDegrees == deviceRotationDegrees) return
        // While recording, keep the start orientation — an MP4 track's geometry is fixed once
        // written, so a portrait↔landscape turn can't be applied mid-file. See SrtManager.
        if (recorder != null) {
            Log.i(TAG, "Ignoring mid-stream rotation while recording (MP4 dimensions are locked)")
            return
        }
        Log.i(TAG, "Seamless rotation → device=$deviceRotationDegrees")
        try { camera?.stop() } catch (_: Throwable) {}
        try { rotator?.release() } catch (_: Throwable) {}
        rotator = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.shutdown() } catch (_: Throwable) {}
        videoEncoder = null
        videoPtsOffsetUs.set(Long.MIN_VALUE)
        muxer?.resetForNewClient()
        currentConfig = config.copy(deviceRotationDegrees = deviceRotationDegrees)
        startVideoPipeline(deviceRotationDegrees)
    }

    /** Mid-stream lens switch without dropping the receiver — see the SRT path for the rationale. */
    suspend fun switchLens(newLens: Lens, newResolution: Size, newFps: Int): Boolean {
        val config = currentConfig ?: return false
        val cam = camera ?: return false
        if (muxer == null) return false
        // A lens swap can change the encoded resolution, which an open MP4 track can't absorb.
        // Bail so the caller does a full restart (finalising this file, starting a fresh one).
        if (recorder != null) return false
        val newPlan = cam.plan(newLens, newResolution, newFps) ?: return false
        Log.i(TAG, "Seamless RIST lens switch → $newLens ${newPlan.size}")
        try { cam.stop() } catch (_: Throwable) {}
        try { rotator?.release() } catch (_: Throwable) {}
        rotator = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.shutdown() } catch (_: Throwable) {}
        videoEncoder = null
        videoPtsOffsetUs.set(Long.MIN_VALUE)
        muxer?.resetForNewClient()
        currentPlan = newPlan
        currentConfig = config.copy(lens = newLens, resolution = newResolution, fps = newFps)
        startVideoPipeline(config.deviceRotationDegrees)
        return true
    }

    fun setTorch(on: Boolean) { camera?.setTorch(on) }

    /** Apply image controls live: ISP keys via the camera request, mirror via the GL stage.
     *  Stored in [currentConfig] so a rotation / lens swap rebuilds with them. */
    fun setImageControls(controls: ImageControls) {
        currentConfig = currentConfig?.copy(imageControls = controls)
        camera?.setImageControls(controls)
        rotator?.setMirror(controls.mirror)
    }

    fun setAudioMuted(muted: Boolean) { audioEncoder?.muted = muted }
    fun audioPeakDbfs(): Float = audioEncoder?.lastPeakDbfs() ?: -90f
    fun framesProduced(): Long = videoEncoder?.framesProduced() ?: 0L
    fun bytesSent(): Long = publisher?.bytesSent() ?: 0L
    fun droppedPackets(): Long = publisher?.droppedPackets() ?: 0L
    fun connectionState(): RistPublisher.State = state

    fun stop() {
        try { camera?.stop() } catch (_: Throwable) {}
        camera = null
        try { rotator?.release() } catch (_: Throwable) {}
        rotator = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.shutdown() } catch (_: Throwable) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.shutdown() } catch (_: Throwable) {}
        audioEncoder = null
        try { publisher?.stop() } catch (_: Throwable) {}
        publisher = null
        muxer = null
        // Finalise the MP4 only after the encoders have stopped feeding samples.
        lastRecordedUri = try { recorder?.stop() } catch (_: Throwable) { null }
        recorder = null
        currentConfig = null
        currentPlan = null
        heldPreviewSurface = null
        heldSidecarSurface = null
        state = RistPublisher.State.IDLE
    }

    /** Uri of the last completed MP4 (after the most recent stop), or null if none. */
    fun lastRecordingUri(): android.net.Uri? = lastRecordedUri

    companion object {
        private const val TAG = "RistManager"
    }
}
