package guru.freberg.lenscast.streaming.srt

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
        val recordLocally: Boolean = false,
        // SRT transport
        val srtMode: SrtPublisher.Mode,
        val srtHost: String,
        val srtPort: Int,
        val srtPassphrase: String,
        val srtLatencyMs: Int,
        val srtStreamId: String,
        val imageControls: ImageControls = ImageControls(),
    )

    private var videoEncoder: H264Encoder? = null
    private var audioEncoder: AacEncoder? = null
    private var camera: RtspCameraDriver? = null
    private var muxer: MpegTsMuxer? = null
    private var publisher: SrtPublisher? = null
    private var rotator: guru.freberg.lenscast.streaming.GlRotator? = null
    private var recorder: RecordingMuxer? = null
    @Volatile private var lastRecordedUri: android.net.Uri? = null
    @Volatile private var state: SrtPublisher.State = SrtPublisher.State.IDLE

    // Retained so the video pipeline can be rebuilt in place for a mid-stream rotation
    // (see [reconfigureVideo]) without tearing down the SRT socket / muxer / audio.
    @Volatile private var currentConfig: Config? = null
    @Volatile private var currentPlan: RtspCameraDriver.Plan? = null
    @Volatile private var heldPreviewSurface: Surface? = null
    // Optional MJPEG-sidecar output. Re-applied on every camera (re)start so a mid-stream
    // rotation / lens switch keeps feeding the browser preview. Owned by StreamingService.
    @Volatile private var heldSidecarSurface: Surface? = null

    // Shared wall-clock origin for both encoders' PTS — see the long note in [start]. These
    // are fields (not captured locals) so [reconfigureVideo] can re-anchor video PTS to the
    // same origin after swapping the encoder, keeping audio/video aligned across a rotation.
    private var streamStartNs: Long = 0L
    private val videoPtsOffsetUs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
    private val audioPtsOffsetUs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)

    fun state(): SrtPublisher.State = state

    suspend fun start(config: Config, previewSurface: Surface?, sidecarSurface: Surface? = null): RtspCameraDriver.Plan? {
        stop()
        val camDriver = RtspCameraDriver(context).also { camera = it }
        val plan = camDriver.plan(config.lens, config.resolution, config.fps) ?: run {
            Log.e(TAG, "No camera plan for SRT path")
            return null
        }
        currentConfig = config
        currentPlan = plan
        heldPreviewSurface = previewSurface
        heldSidecarSurface = sidecarSurface

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

    /**
     * Rebuild the camera → GL rotator → H.264 encoder chain for [deviceRotationDegrees] and
     * (re)start the Camera2 session. The SRT socket, muxer, and audio encoder are untouched,
     * so this is used both for the initial [start] and for a mid-stream rotation via
     * [reconfigureVideo]. Must run after the muxer and (optional) audio are set up.
     */
    private suspend fun startVideoPipeline(deviceRotationDegrees: Int) {
        val plan = currentPlan ?: return
        val config = currentConfig ?: return
        val tsMuxer = muxer ?: return
        val cam = camera ?: return

        // Rotation the GL stage must apply to make the stream upright in the world frame.
        //
        // The camera content arriving in the SurfaceTexture is already corrected for the
        // sensor mount (its transform matrix bakes in sensorOrientation). The fully-upright
        // rotation is the usual (sensorOrientation - deviceRotation); subtract the baked-in
        // sensorOrientation and only the device term remains:
        //
        //     glRotation = (sensorOrientation - deviceRotation) - sensorOrientation
        //                = -deviceRotation   (mod 360)
        //
        // sensorOrientation cancels, so this is correct on any phone regardless of how its
        // front/back sensors are mounted — verified on-device by capturing the SRT output
        // for both lenses in portrait and landscape. (The RTSP path still uses the raw
        // sensorOrientation-based value via MediaFormat.KEY_ROTATION, because it does no GL
        // pass and lets the decoder rotate.)
        val glRotation = (360 - deviceRotationDegrees) % 360

        // Output dimensions follow how the phone is held: portrait device orientation
        // (0° / 180°) → portrait frame, landscape (90° / 270°) → landscape frame. The GL
        // renderer rotates the sensor-corrected camera content into this frame; the encoder
        // is configured with the swapped (portrait) dimensions when needed.
        val portraitOutput = deviceRotationDegrees == 0 || deviceRotationDegrees == 180
        val encoderW = if (portraitOutput) plan.size.height else plan.size.width
        val encoderH = if (portraitOutput) plan.size.width else plan.size.height

        val ve = H264Encoder(
            width = encoderW,
            height = encoderH,
            frameRate = plan.fpsRange.upper,
            bitrateBps = config.videoBitrateBps,
            // KEY_ROTATION on a Surface encoder only embeds metadata for MP4 muxers; for
            // raw H.264 in MPEG-TS it's a no-op. GlRotator below does a real pixel-level
            // rotation, so the encoder itself sees an upright (already-rotated) frame and
            // doesn't need to advertise any rotation.
            rotationDegrees = 0,
            // Override the encoder's 2 s default. SRT receivers (ffmpeg/OBS) can't decode
            // until they see an IDR, so worst-case mid-stream sync is one full GOP — and
            // ffmpeg's analyzeduration probe sees codec parameters twice as often at 1 s.
            iFrameIntervalSeconds = 1,
        ).also { videoEncoder = it }
        val encoderSurface = ve.prepare()
        // Insert the GL rotator between Camera2 and the encoder. The camera writes into
        // the rotator's intermediate SurfaceTexture, the rotator draws into the encoder's
        // input Surface with the chosen rotation applied. We hand `rotator.cameraSurface`
        // to the camera driver instead of `encoderSurface`.
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
            // SPS/PPS NALs themselves don't need to be in PES — we prepend them in the
            // muxer on each keyframe — so skip them here. Everything else is a VCL NAL.
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
        // Force an IDR ~immediately so receivers don't have to wait for the encoder's
        // natural GOP boundary (usually 2–5 s). VLC's TSBPD window otherwise starts ticking
        // before the first decodable frame arrives, and the "non-existing PPS 0 referenced"
        // warnings flood the receiver log.
        try { videoEncoder?.requestKeyframe() } catch (_: Throwable) {}
    }

    /**
     * Mid-stream rotation without dropping the receiver. Tears down only the camera + GL
     * rotator + video encoder and rebuilds them for the new orientation, leaving the SRT
     * socket, the connected client, the muxer (so TS continuity is preserved), and the audio
     * encoder running. The muxer is re-armed so it re-emits PAT/PMT and the next keyframe
     * carries the new SPS (new dimensions); a compliant receiver re-initialises its decoder
     * inline. Video PTS is re-anchored to the same wall-clock origin so it stays aligned with
     * the still-running audio.
     *
     * Cost: a brief video gap (camera reopen) + a decoder re-init glitch on the receiver. A
     * portrait↔landscape change also changes the encoded resolution mid-stream, which some
     * players handle better than others — but the connection itself never drops.
     */
    suspend fun reconfigureVideo(deviceRotationDegrees: Int) {
        val config = currentConfig ?: return
        if (camera == null || muxer == null) return
        if (config.deviceRotationDegrees == deviceRotationDegrees) return
        // A portrait↔landscape turn swaps the encoded dimensions, but an MP4 track's geometry
        // is fixed once written. While recording we keep the start orientation (the SRT stream
        // stays upright via the locked encoder) rather than corrupt the file mid-GOP.
        if (recorder != null) {
            Log.i(TAG, "Ignoring mid-stream rotation while recording (MP4 dimensions are locked)")
            return
        }
        Log.i(TAG, "Seamless rotation → device=$deviceRotationDegrees")
        // Tear down video only. Camera first so it stops feeding the rotator's SurfaceTexture
        // before we release the GL context.
        try { camera?.stop() } catch (_: Throwable) {}
        try { rotator?.release() } catch (_: Throwable) {}
        rotator = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.shutdown() } catch (_: Throwable) {}
        videoEncoder = null
        // Re-anchor video PTS for the new encoder (its clock restarts); audio keeps its
        // offset, so both stay pinned to the same streamStartNs origin.
        videoPtsOffsetUs.set(Long.MIN_VALUE)
        // Re-emit PSI + wait for a fresh IDR carrying the new SPS before passing video again.
        muxer?.resetForNewClient()
        currentConfig = config.copy(deviceRotationDegrees = deviceRotationDegrees)
        startVideoPipeline(deviceRotationDegrees)
    }

    /**
     * Switch the camera lens mid-stream without dropping the receiver. Re-plans for [newLens]
     * (its resolution/fps may differ from the current lens), then tears down and rebuilds only
     * the camera + GL rotator + video encoder — the SRT socket, connected client, muxer (TS
     * continuity), and audio keep running. The new SPS ships in-band on the forced keyframe and
     * MPEG-TS lets the receiver re-init inline, so even a resolution change is absorbed without
     * a disconnect. Returns false if [newLens] has no usable plan (caller falls back to a full
     * restart).
     */
    suspend fun switchLens(newLens: Lens, newResolution: Size, newFps: Int): Boolean {
        val config = currentConfig ?: return false
        val cam = camera ?: return false
        if (muxer == null) return false
        // A lens swap can change the encoded resolution, which an open MP4 track can't absorb.
        // Bail so the caller does a full restart (finalising this file, starting a fresh one).
        if (recorder != null) return false
        val newPlan = cam.plan(newLens, newResolution, newFps) ?: return false
        Log.i(TAG, "Seamless SRT lens switch → $newLens ${newPlan.size}")
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
    fun connectionState(): SrtPublisher.State = state

    fun stop() {
        // Tear down camera first so it stops writing into the rotator's SurfaceTexture
        // before we release the GL context.
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
        state = SrtPublisher.State.IDLE
    }

    /** Uri of the last completed MP4 (after the most recent stop), or null if none. */
    fun lastRecordingUri(): android.net.Uri? = lastRecordedUri

    companion object {
        private const val TAG = "SrtManager"
    }
}
