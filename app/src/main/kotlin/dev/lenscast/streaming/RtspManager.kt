package dev.lenscast.streaming

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.Surface
import dev.lenscast.prefs.Lens
import dev.lenscast.streaming.rtsp.AacEncoder
import dev.lenscast.streaming.rtsp.AacRtpPacketizer
import dev.lenscast.streaming.rtsp.GlRotationPipeline
import dev.lenscast.streaming.rtsp.H264Encoder
import dev.lenscast.streaming.rtsp.H264RtpPacketizer
import dev.lenscast.streaming.rtsp.RtpStream
import dev.lenscast.streaming.rtsp.RtspCameraDriver
import dev.lenscast.streaming.rtsp.RtspServer
import dev.lenscast.streaming.rtsp.Sdp
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
    )

    private var videoEncoder: H264Encoder? = null
    private var audioEncoder: AacEncoder? = null
    private var camera: RtspCameraDriver? = null
    private var server: RtspServer? = null
    private var glPipeline: GlRotationPipeline? = null

    private val videoStream = RtpStream(payloadType = 96, ssrc = Random.nextInt(), clockHz = 90_000)
    private var audioStream: RtpStream? = null

    @Volatile private var activeSink: RtspServer.OutgoingPacketSink? = null

    /** Plans + starts the entire pipeline. Returns the negotiated plan for diagnostics. */
    suspend fun start(config: Config, previewSurface: Surface?): RtspCameraDriver.Plan? {
        stop()
        val camDriver = RtspCameraDriver(context).also { camera = it }
        val plan = camDriver.plan(config.lens, config.resolution, config.fps) ?: run {
            Log.e(TAG, "No camera plan for ${config.lens} ${config.resolution} @ ${config.fps}fps")
            return null
        }
        Log.i(TAG, "Plan: size=${plan.size}, fps=${plan.fpsRange}, high-speed=${plan.highSpeed}, bitrate=${config.videoBitrateBps} bps")

        // Combine sensor orientation with device rotation to produce the display rotation
        // value embedded in the H.264 bitstream. For back cameras the convention is
        // `(sensorOrientation - deviceRotation + 360) % 360`. (Front camera mirroring is a
        // separate concern not handled here — KEY_ROTATION only does rotation, not mirror.)
        val encoderRotation = ((plan.sensorOrientation - config.deviceRotationDegrees) + 360) % 360
        Log.i(TAG, "Encoder rotation: sensor=${plan.sensorOrientation}° device=${config.deviceRotationDegrees}° → encoded=$encoderRotation°")

        val ve = H264Encoder(
            width = plan.size.width,
            height = plan.size.height,
            frameRate = plan.fpsRange.upper,
            bitrateBps = config.videoBitrateBps,
            // Keep KEY_ROTATION metadata too — costs nothing and helps decoders that
            // happen to honour it. The GL pipeline is the real rotation, though.
            rotationDegrees = encoderRotation,
        ).also { videoEncoder = it }
        val encoderInputSurface = ve.prepare()

        // Insert the GL pipeline between camera and encoder when a rotation is needed.
        // Without this, the encoder bakes whatever the sensor produced (landscape on
        // phones) — KEY_ROTATION metadata alone isn't enough for RTSP players.
        val cameraTargetSurface = if (encoderRotation != 0) {
            val gl = GlRotationPipeline(encoderInputSurface, encoderRotation).also { glPipeline = it }
            gl.prepare(plan.size.width, plan.size.height)
            gl.start()
            gl.cameraInputSurface
        } else {
            encoderInputSurface
        }

        ve.start { nal, ptsUs, isKey ->
            val ts = videoStream.timestampFromUs(ptsUs)
            val nalType = nal[0].toInt() and 0x1F
            // SPS/PPS NALs do not advance frames; mark non-VCL with no marker, VCL last NAL gets marker.
            val markerOnLast = nalType in 1..5
            val packets = H264RtpPacketizer.packetize(videoStream, nal, ts, markerOnLast = markerOnLast)
            val sink = activeSink ?: return@start
            for (p in packets) sink.sendVideo(p)
            if (isKey) Log.v(TAG, "Sent IDR (${packets.size} pkts, ${nal.size}B NAL)")
        }

        if (config.audioEnabled) {
            val ae = AacEncoder().also { audioEncoder = it }
            audioStream = RtpStream(payloadType = 97, ssrc = Random.nextInt(), clockHz = ae.sampleRate)
            ae.start { au, ptsUs ->
                val stream = audioStream ?: return@start
                val ts = stream.timestampFromUs(ptsUs)
                val packets = AacRtpPacketizer.packetize(stream, au, ts)
                val sink = activeSink ?: return@start
                for (p in packets) sink.sendAudio(p)
            }
        }

        camDriver.start(plan, cameraTargetSurface, previewSurface)

        val srv = RtspServer(config.port, streamProvider).also { it.start() }
        server = srv

        return plan
    }

    fun setTorch(on: Boolean) { camera?.setTorch(on) }

    /** Total H.264 VCL frames produced since [start]. */
    fun framesProduced(): Long = videoEncoder?.framesProduced() ?: 0L

    /** 1 if a client is currently in PLAY state, 0 otherwise (single-client server). */
    fun clientCount(): Int = if (activeSink != null) 1 else 0

    fun stop() {
        activeSink = null
        try { server?.stop() } catch (_: Throwable) {}
        server = null
        // Camera before GL pipeline — releasing the pipeline destroys the Surface the
        // camera is still writing to, which can crash the capture session.
        try { camera?.stop() } catch (_: Throwable) {}
        camera = null
        try { glPipeline?.release() } catch (_: Throwable) {}
        glPipeline = null
        try { videoEncoder?.stop() } catch (_: Throwable) {}
        try { videoEncoder?.shutdown() } catch (_: Throwable) {}
        videoEncoder = null
        try { audioEncoder?.stop() } catch (_: Throwable) {}
        try { audioEncoder?.shutdown() } catch (_: Throwable) {}
        audioEncoder = null
        audioStream = null
    }

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
            activeSink = sink
            // Force a keyframe so the new client can start decoding immediately.
            videoEncoder?.requestKeyframe()
        }

        override fun onClientTeardown() {
            activeSink = null
        }
    }

    companion object {
        private const val TAG = "RtspManager"
    }
}
