package guru.freberg.lenscast.streaming.webrtc

import android.content.Context
import android.util.Log
import guru.freberg.lenscast.prefs.Lens
import org.webrtc.Camera2Capturer
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Browser-facing WebRTC sender. One factory + one capture pipeline; per-peer PeerConnections
 * are spun up on demand by [createAnswer], driven by the `/webrtc/offer` HTTP endpoint.
 *
 * Camera ownership: this path owns Camera2 via [Camera2Capturer]. It is mutually exclusive
 * with the MJPEG (CameraX) and RTSP (custom Camera2 driver) paths — [StreamingService]
 * tears down whichever is active before starting the WebRTC manager.
 *
 * Audio: optional — when [start] is called with `audioEnabled=true`, a JavaAudioDeviceModule
 * is wired up and an audio track is added to each PeerConnection alongside the video track.
 *
 * DataChannel: every PC opens an outbound "lenscast" data channel and listens for inbound
 * ones; runtime control commands (lens switch, torch, zoom, EV, mirror, snapshot) ride that
 * channel as one-line JSON instead of an HTTP roundtrip. Browser-side parity comes from the
 * standalone viewer page.
 *
 * Signalling: half-trickle. The peer's offer is set as remote description, we generate the
 * answer, then block (up to [iceGatherTimeoutMs]) waiting for ICE gathering to finish so the
 * returned SDP carries all candidates inline. Avoids needing a separate trickle endpoint
 * for the MVP.
 */
class WebRtcManager(
    private val context: Context,
    /** Bridge for DataChannel-delivered control commands. Same surface the web control HTTP
     *  endpoints already hit, so the browser doesn't need a separate code path. */
    private val control: ControlBridge? = null,
) {

    /** Subset of the MjpegControl surface that DataChannel commands route to. */
    interface ControlBridge {
        fun switchLens()
        fun toggleTorch()
        fun toggleMirror()
        fun toggleContinuousAf()
        fun zoomBy(factor: Float)
        fun nudgeExposure(delta: Int)
        fun snapshot(): Boolean
    }

    @Volatile private var factory: PeerConnectionFactory? = null
    @Volatile private var videoSource: VideoSource? = null
    @Volatile private var videoTrack: VideoTrack? = null
    @Volatile private var audioSource: AudioSource? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var audioDeviceModule: JavaAudioDeviceModule? = null
    @Volatile private var capturer: CameraVideoCapturer? = null
    // Owned by us, NOT by Camera2Capturer — the capturer's dispose() does not release it, so it
    // must be disposed in stop() or each session leaks a HandlerThread + GL texture.
    @Volatile private var surfaceTextureHelper: SurfaceTextureHelper? = null
    @Volatile private var eglBase: EglBase? = null
    /** Lens the capturer is currently bound to — tracked so [switchLens] can no-op a same-lens
     *  request and so a switch knows which Camera2 device to target next. */
    @Volatile private var currentLens: Lens? = null
    private val peers = CopyOnWriteArrayList<PeerConnection>()
    private val peerDataChannels = java.util.concurrent.ConcurrentHashMap<PeerConnection, DataChannel>()
    /** WHEP resource id → PeerConnection, so a `DELETE /whep/<id>` can tear down the exact
     *  session the player created. Distinct from [peers] (which also holds legacy
     *  `/webrtc/offer` peers that have no addressable resource). */
    private val whepSessions = java.util.concurrent.ConcurrentHashMap<String, PeerConnection>()

    fun start(lens: Lens, width: Int, height: Int, fps: Int, audioEnabled: Boolean = false): Boolean {
        if (factory != null) return true
        try {
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context)
                    .setEnableInternalTracer(false)
                    .createInitializationOptions()
            )
            val egl = EglBase.create().also { eglBase = it }
            val encoderFactory = DefaultVideoEncoderFactory(egl.eglBaseContext, true, true)
            val decoderFactory = DefaultVideoDecoderFactory(egl.eglBaseContext)
            val adm = if (audioEnabled) {
                JavaAudioDeviceModule.builder(context)
                    .setUseHardwareAcousticEchoCanceler(true)
                    .setUseHardwareNoiseSuppressor(true)
                    .createAudioDeviceModule()
                    .also { audioDeviceModule = it }
            } else null
            val builder = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
            if (adm != null) builder.setAudioDeviceModule(adm)
            val f = builder.createPeerConnectionFactory()
            factory = f

            val enumerator = Camera2Enumerator(context)
            val targetName = pickCamera(enumerator, lens) ?: run {
                Log.e(TAG, "no Camera2 device matched lens=$lens")
                stop()
                return false
            }
            val cap = Camera2Capturer(context, targetName, null)
            capturer = cap
            currentLens = lens

            val src = f.createVideoSource(false)
            videoSource = src
            val sth = SurfaceTextureHelper.create("LenscastWebRtcCapture", egl.eglBaseContext)
            surfaceTextureHelper = sth
            cap.initialize(sth, context, src.capturerObserver)
            cap.startCapture(width, height, fps)

            val track = f.createVideoTrack("lenscast-video", src)
            track.setEnabled(true)
            videoTrack = track

            if (audioEnabled) {
                // MediaConstraints applies the WebRTC default audio processing (AGC, NS,
                // high-pass filter). Hardware AEC/NS are already enabled on the ADM above —
                // belt-and-braces because OEM implementations vary in quality.
                val audioConstraints = MediaConstraints().apply {
                    mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                    mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
                }
                val asrc = f.createAudioSource(audioConstraints).also { audioSource = it }
                val atrack = f.createAudioTrack("lenscast-audio", asrc).also { audioTrack = it }
                atrack.setEnabled(true)
            }

            Log.i(TAG, "WebRTC started: lens=$lens ${width}x$height @ ${fps}fps, audio=$audioEnabled")
            return true
        } catch (t: Throwable) {
            Log.e(TAG, "WebRTC start failed", t)
            stop()
            return false
        }
    }

    fun stop() {
        for ((_, ch) in peerDataChannels) try { ch.close() } catch (_: Throwable) {}
        peerDataChannels.clear()
        whepSessions.clear()
        for (pc in peers) try { pc.close() } catch (_: Throwable) {}
        peers.clear()
        currentLens = null
        try { capturer?.stopCapture() } catch (_: Throwable) {}
        try { capturer?.dispose() } catch (_: Throwable) {}
        capturer = null
        // Dispose after the capturer (which uses it) to avoid leaking its HandlerThread + texture.
        try { surfaceTextureHelper?.dispose() } catch (_: Throwable) {}
        surfaceTextureHelper = null
        try { videoTrack?.dispose() } catch (_: Throwable) {}
        videoTrack = null
        try { videoSource?.dispose() } catch (_: Throwable) {}
        videoSource = null
        try { audioTrack?.dispose() } catch (_: Throwable) {}
        audioTrack = null
        try { audioSource?.dispose() } catch (_: Throwable) {}
        audioSource = null
        try { audioDeviceModule?.release() } catch (_: Throwable) {}
        audioDeviceModule = null
        try { factory?.dispose() } catch (_: Throwable) {}
        factory = null
        try { eglBase?.release() } catch (_: Throwable) {}
        eglBase = null
    }

    /** Number of peers currently connected (any state above NEW). */
    fun clientCount(): Int = peers.count { it.connectionState() != PeerConnection.PeerConnectionState.NEW }

    fun clientAddresses(): List<String> = peers.mapNotNull { peer ->
        // The PC doesn't expose the remote address directly; the offer endpoint stashes the
        // peer's HTTP remote address in `clientAddrMap` at creation time.
        clientAddrMap[peer]
    }

    private val clientAddrMap = java.util.concurrent.ConcurrentHashMap<PeerConnection, String>()

    /**
     * Seamless mid-stream lens switch. [CameraVideoCapturer.switchCamera] swaps the underlying
     * Camera2 device while keeping the same [VideoSource]/[VideoTrack], so every connected peer
     * keeps streaming with no renegotiation and no receiver drop. Capture is re-negotiated to
     * the closest size the new camera supports (WebRTC receivers absorb mid-stream resolution
     * changes, unlike RTSP). Returns false when WebRTC isn't running or no device matches the
     * requested facing; the actual switch completes asynchronously.
     */
    fun switchLens(lens: Lens): Boolean {
        val cap = capturer ?: return false
        if (lens == currentLens) return true
        val target = pickCamera(Camera2Enumerator(context), lens) ?: run {
            Log.w(TAG, "switchLens: no Camera2 device for lens=$lens")
            return false
        }
        currentLens = lens
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontFacing: Boolean) {
                Log.i(TAG, "WebRTC lens switched (front=$isFrontFacing)")
            }
            override fun onCameraSwitchError(error: String?) {
                Log.w(TAG, "WebRTC lens switch failed: $error")
            }
        }, target)
        return true
    }

    /** Result of a WHEP session create: the resource id (for the `Location` header / later
     *  DELETE) plus the SDP answer to ship back in the `201 Created` body. */
    data class WhepSession(val id: String, val answerSdp: String)

    /**
     * WHEP (draft-ietf-wish-whep) session create. Same handshake as [createAnswer], but the
     * resulting PeerConnection is registered under a generated id so the player can later
     * `DELETE /whep/<id>` to tear it down. Returns null if WebRTC isn't running.
     */
    fun createWhepSession(offerSdp: String, remoteHostPort: String): WhepSession? {
        val (pc, answer) = buildPeer(offerSdp, remoteHostPort) ?: return null
        val id = UUID.randomUUID().toString()
        whepSessions[id] = pc
        Log.i(TAG, "WHEP session $id created for $remoteHostPort")
        return WhepSession(id, answer)
    }

    /** WHEP resource teardown. Closes the PeerConnection behind [id] and forgets it. Returns
     *  false when no live session matches (already gone, or a stale id). */
    fun closeWhepSession(id: String): Boolean {
        val pc = whepSessions.remove(id) ?: return false
        try { peerDataChannels.remove(pc)?.close() } catch (_: Throwable) {}
        clientAddrMap.remove(pc)
        peers.remove(pc)
        try { pc.close() } catch (_: Throwable) {}
        Log.i(TAG, "WHEP session $id deleted")
        return true
    }

    /**
     * Builds a PeerConnection for an incoming SDP offer and returns the SDP answer (with
     * all ICE candidates inlined). Returns null if creation fails or the factory isn't up.
     */
    fun createAnswer(offerSdp: String, remoteHostPort: String): String? =
        buildPeer(offerSdp, remoteHostPort)?.second

    private fun buildPeer(offerSdp: String, remoteHostPort: String): Pair<PeerConnection, String>? {
        val f = factory ?: return null
        val track = videoTrack ?: return null

        val rtcConfig = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // LAN-only deployment: no ICE servers configured.
        }

        val gathered = CompletableFuture<Unit>()
        // The observer fires for its own PeerConnection, but the callback has no `this`
        // handle to it (the PC is the createPeerConnection return value). Capture it in a
        // holder so the disconnect handler removes *exactly* the gone peer — matching by
        // state instead would drop the wrong PC when several share a state.
        val pcHolder = arrayOfNulls<PeerConnection>(1)
        val pc = f.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                Log.i(TAG, "ICE: $newState")
                if (newState == PeerConnection.IceConnectionState.CLOSED ||
                    newState == PeerConnection.IceConnectionState.FAILED ||
                    newState == PeerConnection.IceConnectionState.DISCONNECTED) {
                    val gone = pcHolder[0] ?: return
                    // Remove from the list so /status reflects it, and drop any WHEP resource
                    // that pointed at it so a later DELETE 404s cleanly. Gate the teardown on
                    // the atomic list removal so a concurrent closeWhepSession() (which also
                    // triggers a CLOSED callback) can't double-close the native PC.
                    if (peers.remove(gone)) {
                        clientAddrMap.remove(gone)
                        try { peerDataChannels.remove(gone)?.close() } catch (_: Throwable) {}
                        whepSessions.entries.removeIf { e -> e.value === gone }
                        try { gone.close() } catch (_: Throwable) {}
                    }
                }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) {
                if (newState == PeerConnection.IceGatheringState.COMPLETE) gathered.complete(Unit)
            }
            override fun onIceCandidate(c: IceCandidate?) {}
            override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
            override fun onAddStream(p0: org.webrtc.MediaStream?) {}
            override fun onRemoveStream(p0: org.webrtc.MediaStream?) {}
            override fun onDataChannel(channel: org.webrtc.DataChannel?) {
                // Browser-initiated channel (e.g. via `pc.createDataChannel('lenscast')`).
                // Plug the same observer in so commands flow either direction.
                if (channel != null) wireControlChannel(channel)
            }
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(p0: org.webrtc.RtpReceiver?, p1: Array<out org.webrtc.MediaStream>?) {}
        }) ?: return null
        pcHolder[0] = pc

        pc.addTrack(track, listOf("lenscast"))
        audioTrack?.let { pc.addTrack(it, listOf("lenscast")) }

        // Phone-initiated DataChannel. The browser's standalone viewer + embedded preview
        // listen for "lenscast" and treat each JSON line as a control command.
        runCatching {
            val ch = pc.createDataChannel("lenscast", DataChannel.Init().apply { ordered = true })
            if (ch != null) {
                peerDataChannels[pc] = ch
                wireControlChannel(ch)
            }
        }
        peers.add(pc)
        clientAddrMap[pc] = remoteHostPort

        val offer = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        val setRemoteDone = CompletableFuture<Boolean>()
        pc.setRemoteDescription(NoOpObserver { ok -> setRemoteDone.complete(ok) }, offer)
        if (!setRemoteDone.get(5, TimeUnit.SECONDS)) {
            pc.close(); peers.remove(pc); return null
        }

        val answerDone = CompletableFuture<SessionDescription?>()
        pc.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) { answerDone.complete(sdp) }
            override fun onCreateFailure(p0: String?) { answerDone.complete(null) }
            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, MediaConstraints())
        val answer = answerDone.get(5, TimeUnit.SECONDS) ?: run {
            pc.close(); peers.remove(pc); return null
        }

        val setLocalDone = CompletableFuture<Boolean>()
        pc.setLocalDescription(NoOpObserver { ok -> setLocalDone.complete(ok) }, answer)
        if (!setLocalDone.get(5, TimeUnit.SECONDS)) {
            pc.close(); peers.remove(pc); return null
        }

        // Wait for ICE gathering to complete so the SDP we ship has every candidate.
        // Browsers will accept a half-gathered SDP and trickle the rest via WebRTC events,
        // but our endpoint only returns once so we need the complete picture.
        try { gathered.get(iceGatherTimeoutMs, TimeUnit.MILLISECONDS) } catch (_: Throwable) {
            Log.w(TAG, "ICE gathering didn't complete within ${iceGatherTimeoutMs}ms — returning what we have")
        }
        val answerSdp = pc.localDescription?.description ?: run {
            pc.close(); peers.remove(pc); return null
        }
        return pc to answerSdp
    }

    /**
     * Attach an observer that parses one-line JSON commands (`{"cmd":"torch"}`) and dispatches
     * them through [control]. Commands silently no-op when no bridge was supplied. We don't
     * push state back over the channel today — the browser polls `/status` for that — but
     * the same observer would handle bidirectional updates if we ever do.
     */
    private fun wireControlChannel(channel: DataChannel) {
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(prev: Long) {}
            override fun onStateChange() {}
            override fun onMessage(buf: DataChannel.Buffer?) {
                val bridge = control ?: return
                val bb = buf?.data ?: return
                val bytes = ByteArray(bb.remaining()).also { bb.get(it) }
                val text = String(bytes, Charsets.UTF_8).trim()
                handleControlMessage(text, bridge)
            }
        })
    }

    private fun handleControlMessage(text: String, bridge: ControlBridge) {
        // Minimal hand-rolled parse — accepts {"cmd":"X"} or {"cmd":"X","arg":N}. JSON in,
        // no library dep. Anything we can't decode is silently dropped.
        val cmd = Regex("\"cmd\"\\s*:\\s*\"([a-zA-Z_]+)\"").find(text)?.groupValues?.get(1) ?: return
        val argNum = Regex("\"arg\"\\s*:\\s*(-?[0-9.]+)").find(text)?.groupValues?.get(1)?.toDoubleOrNull()
        try {
            when (cmd) {
                "lens"      -> bridge.switchLens()
                "torch"     -> bridge.toggleTorch()
                "mirror"    -> bridge.toggleMirror()
                "af"        -> bridge.toggleContinuousAf()
                "zoom_in"   -> bridge.zoomBy(1.1f)
                "zoom_out"  -> bridge.zoomBy(1f / 1.1f)
                "ev_up"     -> bridge.nudgeExposure(1)
                "ev_down"   -> bridge.nudgeExposure(-1)
                "ev"        -> bridge.nudgeExposure(argNum?.toInt() ?: 0)
                "snapshot"  -> bridge.snapshot()
                else -> Log.w(TAG, "unknown DataChannel cmd: $cmd")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "DataChannel cmd '$cmd' failed", t)
        }
    }

    private fun pickCamera(enumerator: Camera2Enumerator, lens: Lens): String? {
        val names = enumerator.deviceNames
        // Prefer a device matching the requested facing; fall back to the first.
        val match = names.firstOrNull { name ->
            val front = enumerator.isFrontFacing(name)
            (lens == Lens.FRONT && front) || (lens == Lens.BACK && !front)
        }
        return match ?: names.firstOrNull()
    }

    private class NoOpObserver(val done: (Boolean) -> Unit) : SdpObserver {
        override fun onCreateSuccess(p0: SessionDescription?) {}
        override fun onSetSuccess() { done(true) }
        override fun onCreateFailure(p0: String?) {}
        override fun onSetFailure(p0: String?) { done(false) }
    }

    companion object {
        private const val TAG = "WebRtcManager"
        private const val iceGatherTimeoutMs = 1_500L
    }
}
