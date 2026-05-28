package guru.freberg.lenscast.streaming

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import android.media.MediaRecorder
import guru.freberg.lenscast.prefs.Lens
import guru.freberg.lenscast.prefs.MicSource
import guru.freberg.lenscast.streaming.rtsp.AacEncoder
import guru.freberg.lenscast.streaming.rtsp.AacRtpPacketizer
import guru.freberg.lenscast.streaming.rtsp.H264Encoder
import guru.freberg.lenscast.streaming.rtsp.H264RtpPacketizer
import guru.freberg.lenscast.streaming.rtsp.Rtcp
import guru.freberg.lenscast.streaming.rtsp.RtpStream
import guru.freberg.lenscast.streaming.rtsp.RtspCameraDriver
import guru.freberg.lenscast.streaming.rtsp.RtspServer
import guru.freberg.lenscast.streaming.rtsp.Sdp
import kotlin.random.Random

/**
 * Orchestrates the RTSP streaming path: camera → MediaCodec encoder → RTP packetizer →
 * RTSP server. Mirrors what [MjpegServer] does for the MJPEG path at the
 * [StreamingService] boundary.
 *
 * Lifecycle: prepare encoders → start RTSP server → start camera (which begins encoding) →
 * when an RTSP client PLAYs, packets start flowing through the server's interleaved
 * channel writer.
 */
class RtspManager(
    private val context: Context,
) {
    data class Config(
        val lens: Lens,
        val resolution: Size,
        val fps: Int,
        val videoBitrateBps: Int,
        val audioEnabled: Boolean,
        val port: Int,
        /** Display rotation in degrees (0/90/180/270) at stream-start time. */
        val deviceRotationDegrees: Int = 0,
        val micSource: MicSource = MicSource.VOICE_RECOGNITION,
        val audioGainDb: Int = 0,
        val noiseSuppress: Boolean = false,
        val echoCancel: Boolean = false,
        val recordLocally: Boolean = false,
        val authUsername: String = "Lenscast",
        val authPassword: String = "",
        val sslContext: javax.net.ssl.SSLContext? = null,
    )

    private var videoEncoder: H264Encoder? = null
    private var audioEncoder: AacEncoder? = null
    private var camera: RtspCameraDriver? = null
    private var server: RtspServer? = null
    private var recorder: RecordingMuxer? = null
    @Volatile private var lastRecordedUri: android.net.Uri? = null

    private val videoStream = RtpStream(payloadType = 96, ssrc = Random.nextInt(), clockHz = 90_000)
    private var audioStream: RtpStream? = null

    private val activeSinks = java.util.concurrent.CopyOnWriteArrayList<RtspServer.OutgoingPacketSink>()

    // Adaptive bitrate state — see [adaptiveLoop]. Floor is 250 kbps so the picture doesn't
    // dissolve entirely under bad conditions; ceiling is whatever the user / heuristic asked
    // for via Config.videoBitrateBps.
    @Volatile private var configuredBitrate: Int = 0
    @Volatile private var currentBitrate: Int = 0
    @Volatile private var worstRecentLoss: Double = 0.0
    @Volatile private var lastLossReportAtMs: Long = 0
    private var adaptiveThread: Thread? = null
    private val adaptiveStop = java.util.concurrent.atomic.AtomicBoolean(false)
    private val abrFloorBps = 250_000
    private val abrLossHigh = 0.05
    private val abrLossLow = 0.01

    /** Plans + starts the entire pipeline. Returns the negotiated plan for diagnostics. */
    suspend fun start(
        config: Config,
        previewSurface: Surface?,
        sidecarSurface: Surface? = null,
    ): RtspCameraDriver.Plan? {
        stop()
        val camDriver = RtspCameraDriver(context).also { camera = it }
        val plan = camDriver.plan(config.lens, config.resolution, config.fps) ?: run {
            Log.e(TAG, "No camera plan for ${config.lens} ${config.resolution} @ ${config.fps}fps")
            return null
        }
        Log.i(TAG, "Plan: size=${plan.size}, fps=${plan.fpsRange}, high-speed=${plan.highSpeed}, bitrate=${config.videoBitrateBps} bps")

        // RTSP video is encoded in the sensor's native (landscape) orientation. Rotating
        // the frame inside a Compose-driven GL pipeline interacts unpredictably with
        // SurfaceTexture transforms across Camera2 vendors, so we stream as-is and
        // recommend OBS-side rotation. See Docs/Roadmap.md for the trade-off.
        val encoderRotation = ((plan.sensorOrientation - config.deviceRotationDegrees) + 360) % 360
        Log.i(TAG, "Sensor orientation: ${plan.sensorOrientation}°, device: ${config.deviceRotationDegrees}°, " +
            "would-need rotation ${encoderRotation}° (not applied — rotate at the receiver)")

        val ve = H264Encoder(
            width = plan.size.width,
            height = plan.size.height,
            frameRate = plan.fpsRange.upper,
            bitrateBps = config.videoBitrateBps,
            // Keep KEY_ROTATION metadata as a *hint* for decoders that honour it. Most
            // live-stream decoders don't, so OBS-side rotation is still recommended.
            rotationDegrees = encoderRotation,
        ).also { videoEncoder = it }
        val encoderInputSurface = ve.prepare()

        val cameraTargetSurface = encoderInputSurface

        // Set up local recording before encoders start so we don't miss SPS/PPS arrival.
        if (config.recordLocally) {
            recorder = RecordingMuxer.create(context, expectAudio = config.audioEnabled)
            if (recorder == null) {
                Log.w(TAG, "Local recording requested but MediaStore insert failed; stream continues without recording")
            }
        }

        ve.start { nal, ptsUs, isKey ->
            // Capture SPS/PPS for the muxer the moment they're available — the encoder
            // exposes them via parameterSets after the first config callback, which is
            // delivered before any VCL NAL.
            val rec = recorder
            if (rec != null) {
                val ps = ve.parameterSets
                if (ps != null) rec.addVideoTrack(plan.size.width, plan.size.height, ps.sps, ps.pps)
                val nalType = nal[0].toInt() and 0x1F
                if (nalType in 1..5) rec.writeVideo(nal, ptsUs, isKey)
            }

            val ts = videoStream.timestampFromUs(ptsUs)
            val nalType = nal[0].toInt() and 0x1F
            // SPS/PPS NALs do not advance frames; mark non-VCL with no marker, VCL last NAL gets marker.
            val markerOnLast = nalType in 1..5
            val packets = H264RtpPacketizer.packetize(videoStream, nal, ts, markerOnLast = markerOnLast)
            if (activeSinks.isEmpty()) return@start
            for (sink in activeSinks) for (p in packets) sink.sendVideo(p)
            if (isKey) Log.v(TAG, "Sent IDR (${packets.size} pkts, ${nal.size}B NAL) to ${activeSinks.size} client(s)")
        }

        if (config.audioEnabled) {
            val ae = AacEncoder(
                audioSource = audioSourceFor(config.micSource),
                gainLinear = dbToLinear(config.audioGainDb),
                enableNoiseSuppress = config.noiseSuppress,
                enableEchoCancel = config.echoCancel,
            ).also { audioEncoder = it }
            audioStream = RtpStream(payloadType = 97, ssrc = Random.nextInt(), clockHz = ae.sampleRate)
            ae.start { au, ptsUs ->
                val rec = recorder
                if (rec != null) {
                    val asc = ae.asc
                    if (asc != null) rec.addAudioTrack(ae.sampleRate, ae.channelCount, asc)
                    rec.writeAudio(au, ptsUs)
                }
                val stream = audioStream ?: return@start
                val ts = stream.timestampFromUs(ptsUs)
                val packets = AacRtpPacketizer.packetize(stream, au, ts)
                if (activeSinks.isEmpty()) return@start
                for (sink in activeSinks) for (p in packets) sink.sendAudio(p)
            }
        }

        camDriver.start(plan, cameraTargetSurface, previewSurface, sidecarSurface)

        val srv = RtspServer(
            config.port,
            streamProvider,
            authUsername = config.authUsername,
            authPassword = config.authPassword,
            sslContext = config.sslContext,
        ).also { it.start() }
        server = srv

        configuredBitrate = config.videoBitrateBps
        currentBitrate = config.videoBitrateBps
        startAdaptiveLoop()

        return plan
    }

    /**
     * Simple AIMD on the H.264 bitrate driven by per-stream RTCP RR fraction-lost.
     *
     *  - When worst-case loss across all clients in the last window exceeds [abrLossHigh],
     *    multiply the bitrate by 0.75 (down to the [abrFloorBps] floor).
     *  - When worst-case loss drops below [abrLossLow] and we're under the configured
     *    ceiling, add a fixed [abrFloorBps] / 4 step (≈ 62 kbps) so recovery isn't snappy
     *    enough to oscillate.
     *
     * 2-second cadence picks up changes within ~1 RR cycle without thrashing the encoder.
     */
    private fun startAdaptiveLoop() {
        adaptiveStop.set(false)
        adaptiveThread = Thread({
            var stableSeconds = 0
            while (!adaptiveStop.get()) {
                try { Thread.sleep(2_000) } catch (_: InterruptedException) { break }
                val now = System.currentTimeMillis()
                // No RR in the last 6 s — encoder is either idle (no clients PLAYing) or
                // running TCP-only. Snap back to the configured ceiling; no point penalising.
                if (now - lastLossReportAtMs > 6_000) {
                    if (currentBitrate != configuredBitrate) {
                        currentBitrate = configuredBitrate
                        videoEncoder?.setBitrate(currentBitrate)
                    }
                    worstRecentLoss = 0.0
                    continue
                }
                val loss = worstRecentLoss
                worstRecentLoss = 0.0
                when {
                    loss > abrLossHigh && currentBitrate > abrFloorBps -> {
                        currentBitrate = (currentBitrate * 3 / 4).coerceAtLeast(abrFloorBps)
                        videoEncoder?.setBitrate(currentBitrate)
                        Log.i(TAG, "ABR ↓ loss=${"%.1f".format(loss * 100)}% → ${currentBitrate / 1000} kbps")
                        stableSeconds = 0
                    }
                    loss < abrLossLow && currentBitrate < configuredBitrate -> {
                        stableSeconds += 2
                        if (stableSeconds >= 8) {
                            val step = (abrFloorBps / 4).coerceAtLeast(32_000)
                            currentBitrate = (currentBitrate + step).coerceAtMost(configuredBitrate)
                            videoEncoder?.setBitrate(currentBitrate)
                            Log.i(TAG, "ABR ↑ loss=${"%.2f".format(loss * 100)}% → ${currentBitrate / 1000} kbps")
                            stableSeconds = 0
                        }
                    }
                    else -> { /* hold */ }
                }
            }
        }, "LenscastAbr").apply { isDaemon = true; start() }
    }

    fun setTorch(on: Boolean) { camera?.setTorch(on) }
    fun setAudioGainDb(db: Int) { audioEncoder?.setGainDb(db) }
    fun setAudioMuted(muted: Boolean) { audioEncoder?.muted = muted }
    fun audioPeakDbfs(): Float = audioEncoder?.lastPeakDbfs() ?: -90f

    /** Total H.264 VCL frames produced since [start]. */
    fun framesProduced(): Long = videoEncoder?.framesProduced() ?: 0L

    /** Total bytes shipped over RTSP since [start]. */
    fun bytesSent(): Long = server?.bytesSent() ?: 0L

    fun clientAddresses(): List<String> = server?.clientAddresses() ?: emptyList()
    fun kickClient(remote: String): Boolean = server?.kickClient(remote) ?: false

    /** Number of RTSP clients currently in PLAY state. */
    fun clientCount(): Int = activeSinks.size

    fun stop() {
        adaptiveStop.set(true)
        adaptiveThread?.interrupt()
        adaptiveThread = null
        activeSinks.clear()
        try { server?.stop() } catch (_: Throwable) {}
        server = null
        try { camera?.stop() } catch (_: Throwable) {}
        camera = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.shutdown() } catch (_: Throwable) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.shutdown() } catch (_: Throwable) {}
        audioEncoder = null
        audioStream = null
        // Finalise the MP4 only after the encoders have stopped feeding samples.
        lastRecordedUri = try { recorder?.stop() } catch (_: Throwable) { null }
        recorder = null
    }

    /** Uri of the last completed MP4 (after the most recent stop), or null if none. */
    fun lastRecordingUri(): android.net.Uri? = lastRecordedUri

    private val streamProvider = object : RtspServer.StreamProvider {
        override fun videoTrack(): Sdp.VideoTrack? {
            val ve = videoEncoder ?: return null
            val ps = ve.parameterSets ?: return null
            return Sdp.VideoTrack(payloadType = 96, sps = ps.sps, pps = ps.pps)
        }

        override fun audioTrack(): Sdp.AudioTrack? {
            val ae = audioEncoder ?: return null
            val asc = ae.asc ?: return null
            return Sdp.AudioTrack(payloadType = 97, sampleRate = ae.sampleRate, channels = ae.channelCount, asc = asc)
        }

        override fun videoRtpInfo(): RtspServer.RtpInfo =
            RtspServer.RtpInfo(seq = videoStream.peekSeq(), rtpTime = videoStream.lastTimestamp(), ssrc = videoStream.ssrc)

        override fun audioRtpInfo(): RtspServer.RtpInfo {
            val s = audioStream ?: return RtspServer.RtpInfo(0, 0, 0)
            return RtspServer.RtpInfo(seq = s.peekSeq(), rtpTime = s.lastTimestamp(), ssrc = s.ssrc)
        }

        override fun onClientPlay(sink: RtspServer.OutgoingPacketSink) {
            activeSinks.add(sink)
            // Force a keyframe so the new client can start decoding immediately.
            videoEncoder?.requestKeyframe()
        }

        override fun onClientTeardown(sink: RtspServer.OutgoingPacketSink) {
            activeSinks.remove(sink)
        }

        override fun onReceiverReport(report: Rtcp.ReceiverReport) {
            // Only consider reports about our video stream (audio loss can spike without
            // hurting picture quality and would falsely starve the video encoder).
            if (report.sourceSsrc != videoStream.ssrc) return
            val loss = report.fractionLostFraction
            // Track the worst loss across all reporting clients in the current window;
            // the loop resets this every tick.
            if (loss > worstRecentLoss) worstRecentLoss = loss
            lastLossReportAtMs = System.currentTimeMillis()
        }
    }

    private fun audioSourceFor(src: MicSource): Int = when (src) {
        MicSource.CAMCORDER           -> MediaRecorder.AudioSource.CAMCORDER
        MicSource.MIC                 -> MediaRecorder.AudioSource.MIC
        MicSource.VOICE_RECOGNITION   -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        MicSource.VOICE_COMMUNICATION -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
        MicSource.UNPROCESSED         -> if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            MediaRecorder.AudioSource.UNPROCESSED
        } else {
            MediaRecorder.AudioSource.MIC
        }
    }

    private fun dbToLinear(db: Int): Float = Math.pow(10.0, db / 20.0).toFloat()

    companion object {
        private const val TAG = "RtspManager"
    }
}
