package guru.freberg.lenscast.streaming

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import android.view.Surface
import android.content.ContentValues
import guru.freberg.lenscast.camera.YuvToJpeg
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import guru.freberg.lenscast.MainActivity
import guru.freberg.lenscast.LenscastApp
import guru.freberg.lenscast.R
import android.media.MediaRecorder
import guru.freberg.lenscast.camera.CameraCapabilities
import guru.freberg.lenscast.camera.CameraController
import guru.freberg.lenscast.net.NsdAdvertiser
import guru.freberg.lenscast.net.TlsManager
import guru.freberg.lenscast.prefs.CallBehavior
import guru.freberg.lenscast.system.TelephonyMonitor
import guru.freberg.lenscast.prefs.AntiBanding
import guru.freberg.lenscast.prefs.CameraEffect
import guru.freberg.lenscast.prefs.MicSource
import guru.freberg.lenscast.prefs.Protocol
import guru.freberg.lenscast.prefs.SceneMode
import guru.freberg.lenscast.prefs.Settings
import guru.freberg.lenscast.prefs.SettingsCodec
import guru.freberg.lenscast.prefs.SettingsRepository
import guru.freberg.lenscast.prefs.WhiteBalance
import guru.freberg.lenscast.streaming.rtsp.AacEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that owns the camera + streaming server lifecycle.
 *
 * Lives as long as a stream is active, surviving Activity destruction (screen lock,
 * user backgrounding). The Activity binds in & out for preview rendering and live stats;
 * the service itself runs independently via [Intent]s.
 */
class StreamingService : LifecycleService() {

    enum class State { IDLE, STARTING, STREAMING, ERROR }

    data class Status(
        val state: State = State.IDLE,
        val settings: Settings = Settings(),
        val activeProtocol: Protocol = Protocol.MJPEG,
        val errorMessage: String? = null,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    // One-shot transient user messages (shown as a Toast by the UI). SharedFlow rather than a
    // status field so each emission fires exactly once and repeats aren't deduped away.
    private val _messages = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: kotlinx.coroutines.flow.SharedFlow<String> = _messages.asSharedFlow()
    private fun emitMessage(message: String) { _messages.tryEmit(message) }

    val broadcaster = FrameBroadcaster()
    private var cameraController: CameraController? = null
    private var mjpegServer: MjpegServer? = null
    private var mjpegAudioBroadcaster: AudioBroadcaster? = null
    private var mjpegAudioCapture: PcmCapture? = null
    private var rtspManager: RtspManager? = null
    /** Background SFTP queue for finished MP4 recordings — see [stopStreaming]. */
    private val sftpUploader by lazy { guru.freberg.lenscast.upload.SftpUploader(this) }
    private var nsd: NsdAdvertiser? = null
    private var webControlServer: WebControlServer? = null
    private var webControlSettings: Triple<Boolean, Int, Boolean>? = null
    private var telephonyMonitor: TelephonyMonitor? = null
    @Volatile private var currentCallBehavior: CallBehavior = CallBehavior.IGNORE
    /** True while we hold a SPECIAL_USE foreground notification for the web panel. */
    @Volatile private var persistWebForeground: Boolean = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingPreviewProvider: androidx.camera.core.Preview.SurfaceProvider? = null
    // (RTSP path runs without an on-device preview; see startRtsp for the reasoning.)
    /** Tracks the camera config we last bound with, so we know when to rebind. */
    private var previewBoundKey: BindKey? = null

    private data class BindKey(
        val lens: guru.freberg.lenscast.prefs.Lens,
        val w: Int,
        val h: Int,
        val fps: Int,
        // These flow through Camera2Interop at build time, so changing them requires
        // a CameraX rebind. Mirror, EV, zoom and focus are live and not part of the key.
        val whiteBalance: WhiteBalance,
        val antiBanding: AntiBanding,
        val continuousAf: Boolean,
        val effect: CameraEffect,
        val sceneMode: SceneMode,
        val manualFocus: Boolean,
        val manualFocusCd: Int,
        val manualExposure: Boolean,
        val iso: Int,
        val shutterUs: Long,
    )
    /** True while a stream is active. Suppresses [unbindCamera] from killing the stream. */
    private var streamingCamera: Boolean = false

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        val service: StreamingService get() = this@StreamingService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        // Track the web-control setting flow for the lifetime of the service so the user
        // can toggle it on/off (or change the port) without having to restart anything.
        val repo = SettingsRepository(this)
        lifecycleScope.launch {
            repo.flow
                .map { Triple(it.webControlEnabled, it.webControlPort, it.httpsEnabled) }
                .distinctUntilChanged()
                .collect { (enabled, port, https) ->
                    reconfigureWebControl(enabled, port, https)
                }
        }
        // Keep _status.value.settings in lockstep with whatever's persisted in
        // SettingsRepository so app-side edits flow into /status (and from there
        // into the web control panel on its next 1 Hz poll). Without this, the
        // web sees a stale snapshot until the next web-side write.
        lifecycleScope.launch {
            repo.flow
                .distinctUntilChanged()
                .collect { s ->
                    _status.value = _status.value.copy(settings = s)
                    sftpUploader.updateSettings(s)
                }
        }
        // Privacy / call-handling — observe the setting and start/stop the monitor.
        lifecycleScope.launch {
            repo.flow
                .map { it.callBehavior }
                .distinctUntilChanged()
                .collect { configureCallMonitor(it) }
        }
        // Persistent web foreground — promote when the user opts in, demote when off.
        // Only acts while no stream is running (the streaming path manages its own
        // foreground notification with the camera type).
        lifecycleScope.launch {
            repo.flow
                .map { it.persistentWebControl && it.webControlEnabled }
                .distinctUntilChanged()
                .collect { wantPersistent ->
                    if (_status.value.state == State.STREAMING ||
                        _status.value.state == State.STARTING) return@collect
                    if (wantPersistent) showWebControlForeground()
                    else hideWebControlForeground()
                }
        }
    }

    private fun showWebControlForeground() {
        val s = _status.value.settings
        val launchPending = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val ip = guru.freberg.lenscast.net.NetworkUtils.getLocalIpv4(this) ?: "0.0.0.0"
        val notif: Notification = NotificationCompat.Builder(this, LenscastApp.CHANNEL_WEB_CONTROL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title_web_control))
            .setContentText(getString(R.string.notif_text_web_control, ip, s.webControlPort))
            .setContentIntent(launchPending)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                @Suppress("DEPRECATION")
                startForeground(NOTIF_ID, notif)
            }
            persistWebForeground = true
        } catch (t: Throwable) {
            Log.w(TAG, "showWebControlForeground failed: ${t.message}")
        }
    }

    private fun hideWebControlForeground() {
        if (!persistWebForeground) return
        persistWebForeground = false
        if (_status.value.state == State.STREAMING || _status.value.state == State.STARTING) {
            // Streaming path is still running — leave foreground; it owns the notif now.
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun configureCallMonitor(behavior: CallBehavior) {
        currentCallBehavior = behavior
        if (behavior == CallBehavior.IGNORE) {
            try { telephonyMonitor?.stop() } catch (_: Throwable) {}
            telephonyMonitor = null
            applyMute(false)
            return
        }
        if (telephonyMonitor == null) {
            val monitor = TelephonyMonitor(this, mainExecutor) { active ->
                onCallActiveChanged(active)
            }
            try { monitor.start(); telephonyMonitor = monitor } catch (t: Throwable) {
                Log.w(TAG, "TelephonyMonitor.start failed: ${t.message}")
            }
        }
    }

    private fun onCallActiveChanged(active: Boolean) {
        when (currentCallBehavior) {
            CallBehavior.IGNORE       -> applyMute(false)
            CallBehavior.MUTE_STREAM  -> applyMute(active)
            CallBehavior.DROP_CALL    -> {
                if (active) {
                    val dropped = tryEndCall()
                    // If endCall isn't permitted on this device/skin, fall back to mute so
                    // the user still gets *some* protection while the call connects.
                    if (!dropped) applyMute(true) else applyMute(false)
                } else {
                    applyMute(false)
                }
            }
        }
    }

    private fun applyMute(muted: Boolean) {
        mjpegAudioCapture?.muted = muted
        rtspManager?.setAudioMuted(muted)
    }

    /**
     * Best-effort `TelecomManager.endCall()`. Requires `ANSWER_PHONE_CALLS`; if the user
     * hasn't granted it we silently return false and the caller falls back to mute. Some
     * OEM ROMs reject endCall from non-dialer apps even with the permission — we treat
     * that the same as missing permission.
     */
    private fun tryEndCall(): Boolean {
        return try {
            val tm = getSystemService(android.telecom.TelecomManager::class.java)
            if (tm == null) return false
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.ANSWER_PHONE_CALLS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
            tm.endCall()
        } catch (t: Throwable) {
            Log.w(TAG, "endCall failed: ${t.message}")
            false
        }
    }

    private fun reconfigureWebControl(enabled: Boolean, port: Int, httpsEnabled: Boolean) {
        val previous = webControlSettings
        if (previous == Triple(enabled, port, httpsEnabled) && webControlServer != null) return
        try { webControlServer?.stop() } catch (_: Throwable) {}
        webControlServer = null
        if (enabled) {
            val srv = WebControlServer(
                context = this,
                port = port,
                control = mjpegControl,
                mjpegPort = { _status.value.settings.mjpegPort },
                rtspPort = { _status.value.settings.rtspPort },
                sslContext = if (httpsEnabled) TlsManager.forContext(this).sslContext() else null,
                mjpegIsHttps = { _status.value.settings.httpsEnabled },
                appVersion = {
                    runCatching {
                        packageManager.getPackageInfo(packageName, 0).versionName.orEmpty()
                    }.getOrDefault("")
                },
            )
            try { srv.start(); webControlServer = srv } catch (t: Throwable) {
                Log.w(TAG, "WebControlServer start failed on port $port: ${t.message}")
            }
        }
        webControlSettings = Triple(enabled, port, httpsEnabled)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                if (!persistWebForeground) stopSelf()
            }
            ACTION_START_TILE -> {
                lifecycleScope.launch {
                    val saved = SettingsRepository(this@StreamingService).flow.first()
                    startStreaming(saved)
                }
            }
            ACTION_PERSIST_WEB -> {
                // System will reap us within 5 s if startForeground isn't called.
                showWebControlForeground()
            }
        }
        // STREAMING/persistent-web flow promotes itself; keep STICKY here so the system
        // restarts the persistent-web case after low-memory kills.
        return if (persistWebForeground) START_STICKY else START_NOT_STICKY
    }

    fun startStreaming(settings: Settings) {
        if (_status.value.state == State.STREAMING || _status.value.state == State.STARTING) return
        _status.value = Status(state = State.STARTING, settings = settings, activeProtocol = settings.protocol)
        startForegroundWithType(settings)
        acquireWakeLock()

        lifecycleScope.launch {
            try {
                when (settings.protocol) {
                    Protocol.MJPEG  -> startMjpeg(settings)
                    Protocol.RTSP   -> startRtsp(settings)
                    Protocol.WEBRTC -> startWebRtc(settings)
                    Protocol.SRT    -> startSrt(settings)
                }
                _status.value = _status.value.copy(state = State.STREAMING, errorMessage = null)
                _restarting.value = false
            } catch (t: Throwable) {
                Log.e(TAG, "Start failed", t)
                _restarting.value = false
                // Tear down half-started resources first — stopStreaming() ends with state=IDLE
                // and clears errorMessage. Re-publish the ERROR state afterwards so the UI can
                // observe it and surface the message (otherwise the user sees a brief "Starting"
                // flicker and then nothing, which looks like the app froze).
                stopStreaming()
                _status.value = _status.value.copy(state = State.ERROR, errorMessage = t.message)
            }
        }
    }

    /**
     * Acknowledge an error after the UI has shown it. Returns the state machine to IDLE
     * so the user can attempt Start again with new settings.
     */
    fun clearError() {
        if (_status.value.state == State.ERROR) {
            _status.value = _status.value.copy(state = State.IDLE, errorMessage = null)
        }
    }

    private suspend fun startMjpeg(settings: Settings) {
        streamingCamera = true
        // The activity's LaunchedEffect keeps the CameraX session bound (with ImageAnalysis)
        // whenever the screen is on. We just need to make sure that's true here in case the
        // user tapped Start while the camera wasn't bound yet (e.g., permission just granted),
        // then start the HTTP server.
        bindCameraIfNeeded(
            settings.lens, settings.resolution, settings.fps.value, settings.jpegQuality,
            mirror = settings.mirror,
            watermarkText = settings.watermarkText,
            whiteBalance = settings.whiteBalance,
            antiBanding = settings.antiBanding,
            continuousAf = settings.continuousAf,
            exposureEv = settings.exposureEv,
            effect = settings.effect,
            sceneMode = settings.sceneMode,
            manualFocus = settings.manualFocus,
            manualFocusCentidiopters = settings.manualFocusCentidiopters,
            manualExposure = settings.manualExposure,
            iso = settings.iso,
            shutterUs = settings.shutterUs,
        )
        // Optional audio sidecar for the MJPEG path — raw PCM-16LE via PcmCapture, served
        // by MjpegServer as a streaming WAV. Skipping AAC removes ~20 ms of codec
        // lookahead and dodges receiver-side AAC buffering (browsers/VLC pile up half a
        // second of AUs before playing). Bandwidth is ~88 KB/s mono — trivial.
        val audioPermitted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val audioBroadcaster = if (settings.audioEnabled && audioPermitted) {
            val ab = AudioBroadcaster()
            ab.sampleRate = 44_100
            ab.channels = 1
            val pcm = PcmCapture(
                sampleRateHz = ab.sampleRate,
                channels = ab.channels,
                audioSource = micAudioSourceFor(settings.micSource),
                gainLinear = micDbToLinear(settings.audioGainDb),
                enableNoiseSuppress = settings.noiseSuppress,
                enableEchoCancel = settings.echoCancel,
            )
            pcm.start { chunk -> ab.publish(chunk) }
            mjpegAudioCapture = pcm
            mjpegAudioBroadcaster = ab
            ab
        } else null

        val server = MjpegServer(
            broadcaster, settings.mjpegPort, settings.fps.value,
            username = settings.streamUsername,
            password = settings.streamPassword,
            audioBroadcaster = audioBroadcaster,
            sslContext = if (settings.httpsEnabled) TlsManager.forContext(this).sslContext() else null,
        ).also { it.start() }
        mjpegServer = server
        startNsd(NsdAdvertiser.TYPE_MJPEG, settings.mjpegPort)
    }

    private fun micAudioSourceFor(src: MicSource): Int = when (src) {
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

    private fun micDbToLinear(db: Int): Float =
        Math.pow(10.0, db / 20.0).toFloat()

    /**
     * Bridge handed to [MjpegServer] for the browser control page. Keeps the server
     * decoupled from the service's full API surface — only the four buttons reach back.
     */
    private val mjpegControl: MjpegControl = object : MjpegControl {
        override fun lensIsBack(): Boolean =
            _status.value.settings.lens == guru.freberg.lenscast.prefs.Lens.BACK
        override fun torchIsOn(): Boolean = cameraController?.torchOn == true
        override fun toggleTorch() { setTorch(!torchIsOn()) }
        override fun switchLens() {
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.update {
                    val newLens = if (it.lens == guru.freberg.lenscast.prefs.Lens.BACK)
                        guru.freberg.lenscast.prefs.Lens.FRONT
                    else guru.freberg.lenscast.prefs.Lens.BACK
                    it.copy(lens = newLens)
                }
                val s = repo.flow.first()
                // While WebRTC is live it owns Camera2 via its own capturer — a CameraX rebind
                // here would fight it. Drive the capturer's seamless switch instead. (The DataChannel
                // path bypasses this method; this covers the HTTP /control/lens fallback.)
                if (_status.value.state == State.STREAMING && s.protocol == Protocol.WEBRTC) {
                    webRtcManager?.switchLens(s.lens)
                    _status.value = _status.value.copy(settings = s)
                    return@launch
                }
                // Rebind to the new lens with the current settings, including newLens.
                bindCameraIfNeeded(
                    s.lens, s.resolution, s.fps.value, s.jpegQuality,
                    mirror = s.mirror,
                    whiteBalance = s.whiteBalance,
                    antiBanding = s.antiBanding,
                    continuousAf = s.continuousAf,
                    exposureEv = s.exposureEv,
                    effect = s.effect,
                    sceneMode = s.sceneMode,
                    manualFocus = s.manualFocus,
                    manualFocusCentidiopters = s.manualFocusCentidiopters,
                )
                _status.value = _status.value.copy(settings = s)
            }
        }
        override fun snapshot(): Boolean = saveSnapshot() != null
        override fun stopStream() {
            stopStreaming()
            stopSelf()
        }
        override fun zoomBy(factor: Float) {
            val current = currentZoomRatio()
            setZoomRatio(current * factor)
        }
        override fun nudgeExposure(delta: Int) {
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                var newEv = 0
                repo.update {
                    newEv = it.exposureEv + delta
                    it.copy(exposureEv = newEv)
                }
                setExposureEv(newEv)
                _status.value = _status.value.copy(settings = repo.flow.first())
            }
        }
        override fun toggleMirror() {
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                var nextMirror = false
                repo.update {
                    nextMirror = !it.mirror
                    it.copy(mirror = nextMirror)
                }
                cameraController?.mirror = nextMirror
                _status.value = _status.value.copy(settings = repo.flow.first())
            }
        }
        override fun toggleContinuousAf() {
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.update { it.copy(continuousAf = !it.continuousAf) }
                val s = repo.flow.first()
                // Continuous-AF is in BindKey → rebind to swap the AF_MODE.
                bindCameraIfNeeded(
                    s.lens, s.resolution, s.fps.value, s.jpegQuality,
                    mirror = s.mirror,
                    whiteBalance = s.whiteBalance,
                    antiBanding = s.antiBanding,
                    continuousAf = s.continuousAf,
                    exposureEv = s.exposureEv,
                    effect = s.effect,
                    sceneMode = s.sceneMode,
                    manualFocus = s.manualFocus,
                    manualFocusCentidiopters = s.manualFocusCentidiopters,
                )
                _status.value = _status.value.copy(settings = s)
            }
        }
        override fun setJpegQuality(value: Int) {
            val clamped = value.coerceIn(10, 95)
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.update { it.copy(jpegQuality = clamped) }
                cameraController?.jpegQuality = clamped
                _status.value = _status.value.copy(settings = repo.flow.first())
            }
        }
        override fun jpegQuality(): Int = _status.value.settings.jpegQuality

        override fun setResolutionLabel(label: String): Boolean {
            val target = guru.freberg.lenscast.prefs.Resolution.entries.firstOrNull { it.label == label }
                ?: return false
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.update { current ->
                    val supportedRes = CameraCapabilities.supportedResolutions(
                        this@StreamingService, current.lens,
                    )
                    val newRes = if (target in supportedRes) target
                                 else CameraCapabilities.nextBestResolution(supportedRes, target)
                    val supportedFps = CameraCapabilities.supportedFps(
                        this@StreamingService, current.lens, newRes, current.protocol,
                    )
                    val newFps = CameraCapabilities.nextBestFps(supportedFps, current.fps)
                    current.copy(resolution = newRes, fps = newFps)
                }
                val s = repo.flow.first()
                rebindCameraFor(s)
                _status.value = _status.value.copy(settings = s)
            }
            return true
        }

        override fun setFpsValue(value: Int): Boolean {
            val target = guru.freberg.lenscast.prefs.Fps.entries.firstOrNull { it.value == value } ?: return false
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.update { current ->
                    val supportedFps = CameraCapabilities.supportedFps(
                        this@StreamingService, current.lens, current.resolution, current.protocol,
                    )
                    val newFps = if (target.value in supportedFps) target
                                 else CameraCapabilities.nextBestFps(supportedFps, target)
                    current.copy(fps = newFps)
                }
                val s = repo.flow.first()
                rebindCameraFor(s)
                _status.value = _status.value.copy(settings = s)
            }
            return true
        }

        override fun setProtocol(value: String): Boolean {
            val target = when (value.lowercase()) {
                "mjpeg"  -> Protocol.MJPEG
                "rtsp"   -> Protocol.RTSP
                "webrtc" -> Protocol.WEBRTC
                "srt"    -> Protocol.SRT
                else     -> return false
            }
            // Can't swap protocols mid-stream — the encoders and server differ.
            if (_status.value.state == State.STREAMING || _status.value.state == State.STARTING) return false
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.update { current ->
                    if (current.protocol == target) return@update current
                    // FPS ranges differ across protocols (RTSP unlocks 60/120/240 via
                    // high-speed sessions, MJPEG caps at 30). Clamp to a value the new
                    // (lens × resolution × protocol) triple actually supports.
                    val supportedFps = CameraCapabilities.supportedFps(
                        this@StreamingService, current.lens, current.resolution, target,
                    )
                    val newFps = CameraCapabilities.nextBestFps(supportedFps, current.fps)
                    current.copy(protocol = target, fps = newFps)
                }
                val s = repo.flow.first()
                _status.value = _status.value.copy(settings = s)
            }
            return true
        }

        override fun updateSetting(key: String, value: String): Boolean {
            val streaming = _status.value.state == State.STREAMING || _status.value.state == State.STARTING
            // Some keys require special-case handling (recompute FPS, rebind, etc.); fall
            // through to a plain Settings.copy for the rest.
            val repo = SettingsRepository(this@StreamingService)
            val (updater, requiresRebind) = updaterFor(key, value, streaming) ?: return false
            lifecycleScope.launch {
                repo.update(updater)
                val s = repo.flow.first()
                if (requiresRebind) rebindCameraFor(s)
                // Live tweakables (mirror, EV, JPEG quality, audio gain) also need a side
                // effect on the running controller — apply them here.
                applyLiveTweakables(key, s)
                _status.value = _status.value.copy(settings = s)
            }
            return true
        }

        private fun applyLiveTweakables(key: String, s: Settings) {
            when (key) {
                "mirror"        -> cameraController?.mirror = s.mirror
                "watermarkText" -> cameraController?.watermarkText = s.watermarkText
                "exposureEv"    -> cameraController?.applyExposureEv(s.exposureEv)
                "jpegQuality"   -> cameraController?.jpegQuality = s.jpegQuality
                "audioGainDb"   -> {
                    mjpegAudioCapture?.setGainDb(s.audioGainDb)
                    rtspManager?.setAudioGainDb(s.audioGainDb)
                }
            }
        }

        /**
         * Returns the (Settings -> Settings) transform for a given key/value pair, plus a
         * flag for whether a CameraX rebind is needed afterwards. Returns null on unknown
         * key or invalid value so the caller can surface a 503 to the browser.
         */
        private fun updaterFor(
            key: String, value: String, streaming: Boolean,
        ): Pair<(Settings) -> Settings, Boolean>? {
            val b = value.equals("true", ignoreCase = true) ||
                    value.equals("1", ignoreCase = true) ||
                    value.equals("on", ignoreCase = true)
            val v = value.lowercase()
            return when (key) {
                "lens" -> {
                    val lens = when (v) { "back" -> guru.freberg.lenscast.prefs.Lens.BACK
                                          "front" -> guru.freberg.lenscast.prefs.Lens.FRONT
                                          else -> return null }
                    Pair({ it.copy(lens = lens) }, true)
                }
                "mirror"        -> Pair({ it.copy(mirror = b) }, false)
                "watermarkText" -> Pair({ it.copy(watermarkText = value.take(120)) }, false)
                "sftpEnabled"    -> Pair({ it.copy(sftpEnabled = b) }, false)
                "sftpHost"       -> Pair({ it.copy(sftpHost = value.trim().take(255)) }, false)
                "sftpPort"       -> {
                    val p = value.toIntOrNull() ?: return null
                    if (p !in 1..65535) return null
                    Pair({ it.copy(sftpPort = p) }, false)
                }
                "sftpUser"       -> Pair({ it.copy(sftpUser = value.trim().take(120)) }, false)
                "sftpPassword"   -> Pair({ it.copy(sftpPassword = value) }, false)
                "sftpRemoteDir"  -> Pair({ it.copy(sftpRemoteDir = value.trim().take(255)) }, false)
                "sftpHostKeyFingerprint" -> Pair({ it.copy(sftpHostKeyFingerprint = value.trim().take(120)) }, false)
                "languageTag"   -> Pair({ it.copy(languageTag = value.trim().take(16)) }, false)
                "mjpegSidecar"  -> Pair({ it.copy(mjpegSidecar = b) }, false)
                "continuousAf"  -> Pair({ it.copy(continuousAf = b) }, true)
                "exposureEv"    -> {
                    val ev = value.toIntOrNull() ?: return null
                    Pair({ it.copy(exposureEv = ev) }, false)
                }
                "whiteBalance" -> {
                    val wb = guru.freberg.lenscast.prefs.WhiteBalance.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(whiteBalance = wb) }, true)
                }
                "antiBanding" -> {
                    val ab = guru.freberg.lenscast.prefs.AntiBanding.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(antiBanding = ab) }, true)
                }
                "effect" -> {
                    val e = guru.freberg.lenscast.prefs.CameraEffect.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(effect = e) }, true)
                }
                "sceneMode" -> {
                    val sm = guru.freberg.lenscast.prefs.SceneMode.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(sceneMode = sm) }, true)
                }
                "manualFocus" -> Pair({ it.copy(manualFocus = b) }, true)
                "manualFocusCentidiopters" -> {
                    val cd = value.toIntOrNull()?.coerceIn(0, 1000) ?: return null
                    Pair({ it.copy(manualFocusCentidiopters = cd) }, true)
                }
                "httpsEnabled" -> {
                    if (streaming) return null
                    Pair({ it.copy(httpsEnabled = b) }, false)
                }
                "callBehavior" -> {
                    val cb = CallBehavior.entries.firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(callBehavior = cb) }, false)
                }
                "persistentWebControl" -> Pair({ it.copy(persistentWebControl = b) }, false)
                "manualExposure" -> Pair({ it.copy(manualExposure = b) }, true)
                "iso" -> {
                    val n = value.toIntOrNull() ?: return null
                    Pair({ it.copy(iso = n) }, true)
                }
                "shutterUs" -> {
                    val n = value.toLongOrNull() ?: return null
                    Pair({ it.copy(shutterUs = n) }, true)
                }
                "audioEnabled" -> {
                    if (streaming) return null
                    Pair({ it.copy(audioEnabled = b) }, false)
                }
                "micSource" -> {
                    if (streaming) return null
                    val src = guru.freberg.lenscast.prefs.MicSource.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(micSource = src) }, false)
                }
                "audioGainDb" -> {
                    val g = value.toIntOrNull()?.coerceIn(-24, 24) ?: return null
                    Pair({ it.copy(audioGainDb = g) }, false)
                }
                "noiseSuppress" -> {
                    if (streaming) return null
                    Pair({ it.copy(noiseSuppress = b) }, false)
                }
                "echoCancel" -> {
                    if (streaming) return null
                    Pair({ it.copy(echoCancel = b) }, false)
                }
                "jpegQuality" -> {
                    val q = value.toIntOrNull()?.coerceIn(10, 95) ?: return null
                    Pair({ it.copy(jpegQuality = q) }, false)
                }
                "rtspBitrateKbps" -> {
                    if (streaming) return null
                    val br = value.toIntOrNull()?.coerceIn(0, 50_000) ?: return null
                    Pair({ it.copy(rtspBitrateKbps = br) }, false)
                }
                "keepScreenOn"   -> Pair({ it.copy(keepScreenOn = b) }, false)
                "blankPreview"   -> Pair({ it.copy(blankPreview = b) }, false)
                "rotationLock" -> {
                    val rl = guru.freberg.lenscast.prefs.RotationLock.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(rotationLock = rl) }, false)
                }
                "autoStart"      -> Pair({ it.copy(autoStart = b) }, false)
                "startOnBoot"    -> Pair({ it.copy(startOnBoot = b) }, false)
                "recordLocally" -> {
                    if (streaming) return null
                    Pair({ it.copy(recordLocally = b) }, false)
                }
                "mjpegPort" -> {
                    if (streaming) return null
                    val p = value.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
                    Pair({ it.copy(mjpegPort = p) }, false)
                }
                "rtspPort" -> {
                    if (streaming) return null
                    val p = value.toIntOrNull()?.takeIf { it in 1024..65535 } ?: return null
                    Pair({ it.copy(rtspPort = p) }, false)
                }
                "streamUsername" -> {
                    if (streaming) return null
                    // Coerce blank back to the default so the user can never lock
                    // themselves out of an auth-gated URL by saving an empty user.
                    val u = value.trim().ifBlank { "Lenscast" }
                    Pair({ it.copy(streamUsername = u) }, false)
                }
                "streamPassword" -> {
                    if (streaming) return null
                    Pair({ it.copy(streamPassword = value) }, false)
                }
                else -> null
            }
        }

        override fun exportSettingsJson(): String = SettingsCodec.toJson(_status.value.settings)

        override fun importSettingsJson(body: String): Boolean {
            if (_status.value.state == State.STREAMING || _status.value.state == State.STARTING) return false
            val parsed = SettingsCodec.fromJson(body) ?: return false
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.replace(parsed)
                _status.value = _status.value.copy(settings = repo.flow.first())
            }
            return true
        }

        override fun kickClient(remote: String): Boolean = this@StreamingService.kickClient(remote)

        override fun webRtcAnswer(offerSdp: String, remoteHostPort: String): String? =
            this@StreamingService.webRtcAnswer(offerSdp, remoteHostPort)
        override fun webRtcWhepCreate(offerSdp: String, remoteHostPort: String): Pair<String, String>? =
            webRtcManager?.createWhepSession(offerSdp, remoteHostPort)?.let { it.id to it.answerSdp }
        override fun webRtcWhepDelete(resourceId: String): Boolean =
            webRtcManager?.closeWhepSession(resourceId) ?: false

        override fun retryLastSftpUpload(): Boolean = this@StreamingService.retryLastSftpUpload()
        override fun sftpStatusJson(): String = this@StreamingService.sftpStatusJson()

        override fun startStream(): Boolean {
            if (_status.value.state == State.STREAMING || _status.value.state == State.STARTING) return false
            // Reuse the QS tile's code path: a startForegroundService kicks off
            // onStartCommand → ACTION_START_TILE → startStreaming with the saved settings.
            // This keeps the foreground-service handshake the OS expects on API 31+.
            val intent = Intent(this@StreamingService, StreamingService::class.java)
                .setAction(ACTION_START_TILE)
            androidx.core.content.ContextCompat.startForegroundService(this@StreamingService, intent)
            return true
        }
        override fun statusJson(): String {
            val s = _status.value.settings
            val st = _status.value.state.name.lowercase()
            val lensLabel = if (s.lens == guru.freberg.lenscast.prefs.Lens.BACK) "back" else "front"
            val protoLabel = s.protocol.name.lowercase()
            val res = CameraCapabilities.supportedResolutions(this@StreamingService, s.lens)
                .joinToString(",") { "\"${it.label}\"" }
            val fps = CameraCapabilities.supportedFps(
                this@StreamingService, s.lens, s.resolution, s.protocol,
            ).joinToString(",")
            fun lower(any: Any) = any.toString().lowercase()
            // Hand-rolled JSON — fixed primitives plus jsonEscape for free-text fields.
            return "{" +
                """"state":"$st","protocol":"$protoLabel","lens":"$lensLabel",""" +
                """"torch":${torchIsOn()},""" +
                """"resolution":"${s.resolution.label}",""" +
                """"availableResolutions":[$res],"availableFps":[$fps],""" +
                """"fps":${framesPerSecondNow()},"targetFps":${s.fps.value},""" +
                """"clients":${connectedClientCount()},"audioPeakDbfs":${audioPeakDbfs()},""" +
                """"clientList":[${clientAddresses().joinToString(",") { "\"${jsonEscape(it)}\"" }}],""" +
                """"txKbps":${txKbpsNow()},""" +
                """"health":${healthJson()},""" +
                // image controls
                """"mirror":${s.mirror},"continuousAf":${s.continuousAf},""" +
                """"zoom":${currentZoomRatio()},"ev":${s.exposureEv},""" +
                """"whiteBalance":"${lower(s.whiteBalance)}",""" +
                """"antiBanding":"${lower(s.antiBanding)}",""" +
                """"effect":"${lower(s.effect)}",""" +
                """"sceneMode":"${lower(s.sceneMode)}",""" +
                """"manualFocus":${s.manualFocus},""" +
                """"manualFocusCentidiopters":${s.manualFocusCentidiopters},""" +
                """"manualExposure":${s.manualExposure},"iso":${s.iso},""" +
                """"shutterUs":${s.shutterUs},""" +
                """"httpsEnabled":${s.httpsEnabled},""" +
                """"tlsFingerprint":"${TlsManager.forContext(this@StreamingService).fingerprintSha256() ?: ""}",""" +
                """"callBehavior":"${lower(s.callBehavior)}",""" +
                """"watermarkText":"${jsonEscape(s.watermarkText)}",""" +
                """"sftpEnabled":${s.sftpEnabled},""" +
                """"sftpHost":"${jsonEscape(s.sftpHost)}",""" +
                """"sftpPort":${s.sftpPort},""" +
                """"sftpUser":"${jsonEscape(s.sftpUser)}",""" +
                """"sftpRemoteDir":"${jsonEscape(s.sftpRemoteDir)}",""" +
                """"sftpHostKeyFingerprint":"${jsonEscape(s.sftpHostKeyFingerprint)}",""" +
                """"languageTag":"${jsonEscape(s.languageTag)}",""" +
                """"mjpegSidecar":${s.mjpegSidecar},""" +
                """"streamUsername":"${jsonEscape(s.streamUsername)}",""" +
                """"persistentWebControl":${s.persistentWebControl},""" +
                """"supportsManualSensor":${CameraCapabilities.supportsManualSensor(this@StreamingService, s.lens)},""" +
                """"isoRange":${CameraCapabilities.isoRange(this@StreamingService, s.lens)?.let { "[${it.lower},${it.upper}]" } ?: "null"},""" +
                """"shutterRangeUs":${CameraCapabilities.exposureTimeRangeNs(this@StreamingService, s.lens)?.let { "[${it.lower / 1000L},${it.upper / 1000L}]" } ?: "null"},""" +
                // audio
                """"audioEnabled":${s.audioEnabled},""" +
                """"micSource":"${lower(s.micSource)}",""" +
                """"audioGainDb":${s.audioGainDb},""" +
                """"noiseSuppress":${s.noiseSuppress},""" +
                """"echoCancel":${s.echoCancel},""" +
                // stream
                """"jpegQuality":${s.jpegQuality},""" +
                """"rtspBitrateKbps":${s.rtspBitrateKbps},""" +
                // UX
                """"keepScreenOn":${s.keepScreenOn},""" +
                """"blankPreview":${s.blankPreview},""" +
                """"rotationLock":"${lower(s.rotationLock)}",""" +
                // automation
                """"autoStart":${s.autoStart},""" +
                """"startOnBoot":${s.startOnBoot},""" +
                """"recordLocally":${s.recordLocally},""" +
                // ports
                """"mjpegPort":${s.mjpegPort},"rtspPort":${s.rtspPort}""" +
            "}"
        }
    }

    /**
     * Rebind the CameraX preview-only session for the MJPEG path after a settings change
     * that affects the camera (resolution, fps, AWB/AF/anti-banding/effect/scene). RTSP
     * mode owns Camera2 directly and won't react to this — that's deliberate; rebinding
     * mid-RTSP-stream tears down the encoder Surface.
     */
    private suspend fun rebindCameraFor(s: Settings) {
        // Both RTSP and SRT lock the camera at stream-start via the Camera2 driver.
        val p = _status.value.activeProtocol
        if (_status.value.state == State.STREAMING && (p == Protocol.RTSP || p == Protocol.SRT)) return
        bindCameraIfNeeded(
            s.lens, s.resolution, s.fps.value, s.jpegQuality,
            mirror = s.mirror,
            watermarkText = s.watermarkText,
            whiteBalance = s.whiteBalance,
            antiBanding = s.antiBanding,
            continuousAf = s.continuousAf,
            exposureEv = s.exposureEv,
            effect = s.effect,
            sceneMode = s.sceneMode,
            manualFocus = s.manualFocus,
            manualFocusCentidiopters = s.manualFocusCentidiopters,
            manualExposure = s.manualExposure,
            iso = s.iso,
            shutterUs = s.shutterUs,
        )
    }

    @Volatile private var lastFpsCount: Long = 0
    @Volatile private var lastFpsT: Long = System.currentTimeMillis()
    @Volatile private var lastBytesSent: Long = 0
    @Volatile private var lastBytesT: Long = System.currentTimeMillis()
    @Volatile private var lastTxKbps: Int = 0

    /** Hand-rolled health JSON snippet for /status — null message when no banner. */
    private fun healthJson(): String {
        val st = guru.freberg.lenscast.system.HealthMonitor.snapshotNow(this)
        val msg = st.message
        val msgJson = if (msg == null) "null" else "\"${jsonEscape(msg)}\""
        return """{"severity":"${st.severity.name.lowercase()}","message":$msgJson}"""
    }

    /** Rolling network tx rate for the web /status endpoint — ≥500 ms window. */
    private fun txKbpsNow(): Int {
        val now = System.currentTimeMillis()
        val dt = now - lastBytesT
        if (dt < 500) return lastTxKbps
        val bytes = bytesSentNow()
        val kbps = (((bytes - lastBytesSent) * 8.0) / dt).toInt() // bytes*8/ms = kbps
        lastBytesSent = bytes
        lastBytesT = now
        lastTxKbps = kbps
        return kbps
    }

    /** Crude rolling FPS for the web /status endpoint — sample-rate aware over ≥500 ms. */
    private fun framesPerSecondNow(): Int {
        val now = System.currentTimeMillis()
        val dt = (now - lastFpsT)
        if (dt < 500) return 0
        val count = framesProducedNow()
        val fps = ((count - lastFpsCount) * 1000.0 / dt).toInt()
        lastFpsCount = count
        lastFpsT = now
        return fps
    }

    /**
     * Lifecycle owner of the optional MJPEG sidecar ImageReader. Held at the service level
     * so [stopStreaming] can release it deterministically when RTSP shuts down.
     */
    @Volatile private var sidecarReader: android.media.ImageReader? = null
    @Volatile private var sidecarHandler: android.os.Handler? = null
    @Volatile private var sidecarThread: android.os.HandlerThread? = null
    @Volatile private var sidecarLastPublishMs: Long = 0

    private suspend fun startRtsp(settings: Settings) {
        // The RTSP path owns Camera2 directly; tear down any CameraX preview-only session.
        try { cameraController?.unbind() } catch (_: Throwable) {}
        previewBoundKey = null
        streamingCamera = true

        // No on-device preview during RTSP streaming — the Compose layout swap on rotation
        // would otherwise tear down the SurfaceView/TextureView and abandon Camera2's
        // BufferQueue. The encoder still gets every frame; OBS still sees the live video.
        val bitrate = if (settings.rtspBitrateKbps > 0) {
            settings.rtspBitrateKbps * 1000
        } else {
            recommendedVideoBitrate(settings.resolution.width, settings.resolution.height, settings.fps.value)
        }
        val mgr = RtspManager(this).also { rtspManager = it }
        val rotationDegrees = when (currentRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        // Optional MJPEG sidecar: a second output Surface on the Camera2 capture session.
        // Only legal for standard sessions (≤30 fps); high-speed sessions reject ImageReader
        // YUV outputs. We also skip it if the user disabled the toggle.
        val sidecarEnabled = settings.mjpegSidecar && settings.fps.value <= 30
        val sidecarSurface: Surface? = if (sidecarEnabled) {
            val reader = android.media.ImageReader.newInstance(
                settings.resolution.width, settings.resolution.height,
                android.graphics.ImageFormat.YUV_420_888,
                2, // double buffer — gives the listener a frame in flight while the next arrives.
            )
            sidecarReader = reader
            val thread = android.os.HandlerThread("LenscastMjpegSidecar").also { it.start() }
            sidecarThread = thread
            val handler = android.os.Handler(thread.looper)
            sidecarHandler = handler
            sidecarLastPublishMs = 0L
            reader.setOnImageAvailableListener(makeSidecarListener(rotationDegrees, settings), handler)
            reader.surface
        } else null

        val rtspAudioPermitted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val plan = mgr.start(
            RtspManager.Config(
                lens = settings.lens,
                resolution = Size(settings.resolution.width, settings.resolution.height),
                fps = settings.fps.value,
                videoBitrateBps = bitrate,
                audioEnabled = settings.audioEnabled && rtspAudioPermitted,
                port = settings.rtspPort,
                deviceRotationDegrees = rotationDegrees,
                micSource = settings.micSource,
                audioGainDb = settings.audioGainDb,
                noiseSuppress = settings.noiseSuppress,
                echoCancel = settings.echoCancel,
                recordLocally = settings.recordLocally,
                authUsername = settings.streamUsername,
                authPassword = settings.streamPassword,
                sslContext = if (settings.httpsEnabled) TlsManager.forContext(this).sslContext() else null,
            ),
            previewSurface = null,
            sidecarSurface = sidecarSurface,
        )
        if (plan == null) {
            tearDownSidecar()
            throw IllegalStateException(
                "No camera plan matched ${settings.resolution.label} @ ${settings.fps.value} fps on ${settings.lens}",
            )
        }
        _lastRtspPlan = plan
        if (sidecarEnabled) {
            // Spin up the existing MJPEG server on its dedicated port so a browser <img>
            // can hit /video while OBS pulls RTSP. The broadcaster is fed by the sidecar
            // ImageReader listener; the audio sidecar (PCM WAV) isn't supported in this
            // mode because Camera2 owns audio capture through MediaRecorder in RTSP.
            val server = MjpegServer(
                broadcaster, settings.mjpegPort, minOf(settings.fps.value, 15),
                username = settings.streamUsername,
                password = settings.streamPassword,
                audioBroadcaster = null,
                sslContext = if (settings.httpsEnabled) TlsManager.forContext(this).sslContext() else null,
            ).also { it.start() }
            mjpegServer = server
            startNsd(NsdAdvertiser.TYPE_MJPEG, settings.mjpegPort)
        }
        startNsd(NsdAdvertiser.TYPE_RTSP, settings.rtspPort)
    }

    /**
     * ImageReader callback: pulls each NV21 frame, encodes JPEG, publishes to the shared
     * [broadcaster]. Self-throttles to ~15 fps so the JPEG encode + watermark draw doesn't
     * starve the H.264 encoder running on the same camera output.
     */
    private fun makeSidecarListener(rotationDegrees: Int, settings: Settings): android.media.ImageReader.OnImageAvailableListener {
        val sensorOrientation = _lastRtspPlan?.sensorOrientation ?: 0
        // Camera frames are sensor-oriented; the encoder ships them as-is on RTSP, but the
        // MJPEG path historically rotates to upright. Match the sensor orientation for the
        // sidecar so browser viewers see the same upright image they always have.
        val sidecarRotation = ((sensorOrientation - rotationDegrees) + 360) % 360
        val minIntervalMs = 1000L / 15 // hard-cap at 15 fps
        return android.media.ImageReader.OnImageAvailableListener { reader ->
            val now = System.currentTimeMillis()
            val img = try { reader.acquireLatestImage() } catch (_: Throwable) { null } ?: return@OnImageAvailableListener
            try {
                if (now - sidecarLastPublishMs < minIntervalMs) return@OnImageAvailableListener
                sidecarLastPublishMs = now
                val jpeg = YuvToJpeg.encodeImage(
                    img, sidecarRotation, settings.jpegQuality,
                    mirror = settings.mirror, watermark = settings.watermarkText,
                )
                broadcaster.publish(jpeg)
            } catch (t: Throwable) {
                Log.w(TAG, "MJPEG sidecar encode failed: ${t.message}")
            } finally {
                try { img.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun tearDownSidecar() {
        try { sidecarReader?.close() } catch (_: Throwable) {}
        sidecarReader = null
        try { sidecarThread?.quitSafely() } catch (_: Throwable) {}
        sidecarThread = null
        sidecarHandler = null
    }

    private suspend fun startSrt(settings: Settings) {
        // SRT path also goes through Camera2 directly (via RtspCameraDriver), so unwind
        // any CameraX session before starting.
        try { cameraController?.unbind() } catch (_: Throwable) {}
        previewBoundKey = null
        streamingCamera = true
        val bitrate = if (settings.rtspBitrateKbps > 0) {
            settings.rtspBitrateKbps * 1000
        } else {
            recommendedVideoBitrate(settings.resolution.width, settings.resolution.height, settings.fps.value)
        }
        val rotationDegrees = when (currentRotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        val srtAudioPermitted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val srtMode = when (settings.srtMode) {
            guru.freberg.lenscast.prefs.SrtMode.CALLER   -> guru.freberg.lenscast.streaming.srt.SrtPublisher.Mode.CALLER
            guru.freberg.lenscast.prefs.SrtMode.LISTENER -> guru.freberg.lenscast.streaming.srt.SrtPublisher.Mode.LISTENER
        }
        if (srtMode == guru.freberg.lenscast.streaming.srt.SrtPublisher.Mode.CALLER && settings.srtHost.isBlank()) {
            throw IllegalStateException("SRT caller mode needs a remote host. Set Settings → SRT host, or switch to Listener.")
        }
        val mgr = guru.freberg.lenscast.streaming.srt.SrtManager(this).also { srtManager = it }
        val plan = mgr.start(
            guru.freberg.lenscast.streaming.srt.SrtManager.Config(
                lens = settings.lens,
                resolution = Size(settings.resolution.width, settings.resolution.height),
                fps = settings.fps.value,
                videoBitrateBps = bitrate,
                audioEnabled = settings.audioEnabled && srtAudioPermitted,
                deviceRotationDegrees = rotationDegrees,
                micSource = settings.micSource,
                audioGainDb = settings.audioGainDb,
                noiseSuppress = settings.noiseSuppress,
                echoCancel = settings.echoCancel,
                srtMode = srtMode,
                srtHost = settings.srtHost,
                srtPort = settings.srtPort,
                srtPassphrase = settings.srtPassphrase,
                srtLatencyMs = settings.srtLatencyMs,
                srtStreamId = settings.srtStreamId,
            ),
            previewSurface = null,
        )
        if (plan == null) {
            throw IllegalStateException(
                "No camera plan matched ${settings.resolution.label} @ ${settings.fps.value} fps on ${settings.lens}",
            )
        }
        _lastRtspPlan = plan
    }
    @Volatile private var srtManager: guru.freberg.lenscast.streaming.srt.SrtManager? = null

    private fun startWebRtc(settings: Settings) {
        // WebRTC's Camera2Capturer owns Camera2 exclusively — tear down whatever else
        // might be holding the camera (CameraX preview-only, etc.).
        try { cameraController?.unbind() } catch (_: Throwable) {}
        previewBoundKey = null
        streamingCamera = true
        val bridge = object : guru.freberg.lenscast.streaming.webrtc.WebRtcManager.ControlBridge {
            // mjpegControl.switchLens detects the live WebRTC path and drives the capturer's
            // seamless switch (rather than a CameraX rebind), so plain delegation is correct.
            override fun switchLens() = mjpegControl.switchLens()
            override fun toggleTorch() = mjpegControl.toggleTorch()
            override fun toggleMirror() = mjpegControl.toggleMirror()
            override fun toggleContinuousAf() = mjpegControl.toggleContinuousAf()
            override fun zoomBy(factor: Float) = mjpegControl.zoomBy(factor)
            override fun nudgeExposure(delta: Int) = mjpegControl.nudgeExposure(delta)
            override fun snapshot(): Boolean = mjpegControl.snapshot()
        }
        val mgr = guru.freberg.lenscast.streaming.webrtc.WebRtcManager(this, bridge)
            .also { webRtcManager = it }
        val webRtcAudioPermitted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val ok = mgr.start(
            lens = settings.lens,
            width = settings.resolution.width,
            height = settings.resolution.height,
            fps = settings.fps.value,
            audioEnabled = settings.audioEnabled && webRtcAudioPermitted,
        )
        if (!ok) throw IllegalStateException("WebRTC failed to initialise (Camera2 unavailable?)")
    }
    @Volatile private var webRtcManager: guru.freberg.lenscast.streaming.webrtc.WebRtcManager? = null

    /** Minimal JSON escape — surfaces strings as ASCII-safe payloads for /status. */
    internal fun jsonEscape(t: String): String = buildString(t.length) {
        for (c in t) when {
            c == '"'  -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
    }

    /** Resolves the `DISPLAY_NAME` (e.g. `Lenscast_20260528_223301.mp4`) for a MediaStore URI. */
    private fun displayNameForUri(uri: android.net.Uri): String? {
        return try {
            contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Throwable) { null }
    }

    /** Queue an upload of the most recently finalised MP4. Returns false if there isn't one. */
    fun retryLastSftpUpload(): Boolean {
        val uri = _lastRecordingUri ?: return false
        val name = displayNameForUri(uri) ?: "lenscast.mp4"
        sftpUploader.enqueue(uri, name)
        return true
    }

    fun sftpStatusJson(): String {
        val st = sftpUploader.status()
        val s = _status.value.settings
        fun q(v: String?): String = if (v == null) "null" else "\"${jsonEscape(v)}\""
        return """{"enabled":${s.sftpEnabled},"host":${q(s.sftpHost)},""" +
            """"state":"${st.state.name.lowercase()}",""" +
            """"current":${q(st.current)},"queue":${st.queueSize},""" +
            """"lastUploaded":${q(st.lastUploaded)},"lastError":${q(st.lastError)},""" +
            """"hasLastRecording":${_lastRecordingUri != null}}"""
    }

    private fun startNsd(type: String, port: Int) {
        nsd?.stop()
        nsd = NsdAdvertiser(this).also { it.start(type, port) }
    }

    private fun stopNsd() {
        try { nsd?.stop() } catch (_: Throwable) {}
        nsd = null
    }

    private var _lastRtspPlan: guru.freberg.lenscast.streaming.rtsp.RtspCameraDriver.Plan? = null
    fun lastRtspPlan() = _lastRtspPlan

    /**
     * H.264 bitrate target tuned for clean OBS playback on a LAN. Scales with pixels × fps.
     * 0.07 bits per pixel — a bit conservative on purpose so Wi-Fi packet loss doesn't
     * cause green corruption blocks at high resolution/fps. 1080p60 lands around 8 Mbps,
     * which most home Wi-Fi handles cleanly.
     */
    private fun recommendedVideoBitrate(w: Int, h: Int, fps: Int): Int =
        (w.toLong() * h * fps * 0.07).toInt().coerceIn(500_000, 16_000_000)

    @Volatile private var currentRotation: Int = android.view.Surface.ROTATION_0
    /** Debounce job for "rotate phone → reconfigure SRT" — see [setDeviceRotation]. */
    private var rotationRestartJob: kotlinx.coroutines.Job? = null
    /** Throttle for the "RTSP orientation locked" Toast — see [setDeviceRotation]. */
    @Volatile private var lastRtspLockMsgMs: Long = 0L
    /**
     * True while [restartStreaming] is mid-flight (between stopStreaming and the next
     * startStreaming reaching STREAMING). The UI observes this to avoid a CameraX rebind
     * race: without the flag, the brief STREAMING → IDLE flicker triggers MainScreen's
     * "bind CameraX preview" effect, which then collides with Camera2 still tearing down.
     */
    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    /**
     * Binds the camera with preview + ImageAnalysis. The analysis stream feeds the MJPEG
     * broadcaster continuously, so the MJPEG server can start/stop without a CameraX
     * rebind (which would briefly close the camera and show a black frame). RTSP mode
     * skips this path entirely — see [startRtsp].
     */
    suspend fun bindCameraIfNeeded(
        lens: guru.freberg.lenscast.prefs.Lens,
        resolution: guru.freberg.lenscast.prefs.Resolution,
        fps: Int,
        jpegQuality: Int,
        mirror: Boolean = false,
        watermarkText: String = "",
        whiteBalance: WhiteBalance = WhiteBalance.AUTO,
        antiBanding: AntiBanding = AntiBanding.AUTO,
        continuousAf: Boolean = true,
        exposureEv: Int = 0,
        effect: CameraEffect = CameraEffect.NONE,
        sceneMode: SceneMode = SceneMode.DISABLED,
        manualFocus: Boolean = false,
        manualFocusCentidiopters: Int = 0,
        manualExposure: Boolean = false,
        iso: Int = 100,
        shutterUs: Long = 16_666L,
    ) {
        val key = BindKey(
            lens, resolution.width, resolution.height, fps,
            whiteBalance, antiBanding, continuousAf,
            effect, sceneMode, manualFocus, manualFocusCentidiopters,
            manualExposure, iso, shutterUs,
        )
        if (key == previewBoundKey && cameraController != null) {
            cameraController?.jpegQuality = jpegQuality
            cameraController?.mirror = mirror
            cameraController?.watermarkText = watermarkText
            cameraController?.setTargetRotation(currentRotation)
            cameraController?.applyExposureEv(exposureEv)
            return
        }
        val controller = ensureController(jpegQuality)
        controller.mirror = mirror
        controller.watermarkText = watermarkText
        controller.bind(
            lifecycleOwner = this,
            lens = lens,
            targetResolution = Size(resolution.width, resolution.height),
            targetFps = fps,
            targetRotation = currentRotation,
            previewSurfaceProvider = pendingPreviewProvider,
            includeAnalysis = true,
            whiteBalance = whiteBalance,
            antiBanding = antiBanding,
            continuousAf = continuousAf,
            exposureEv = exposureEv,
            effect = effect,
            sceneMode = sceneMode,
            manualFocus = manualFocus,
            manualFocusDiopters = manualFocusCentidiopters / 100f,
            manualExposure = manualExposure,
            iso = iso,
            shutterUs = shutterUs,
        )
        previewBoundKey = key
    }

    /**
     * Called by the UI whenever the physical device orientation changes. We push the
     * new rotation to CameraX during preview so the on-screen preview and the JPEG
     * encoder stay upright as the user tilts the phone.
     *
     * Once a stream is active, rotation is *frozen* until Stop. Reason: MJPEG consumers
     * (browsers, ffmpeg, v4l2loopback → kamoso/Chrome/etc.) cache the frame dimensions
     * from the first frame they receive and won't re-negotiate when the JPEG size flips
     * between landscape and portrait — the image renders stretched/squished until the
     * user manually refreshes. Locking at Start gives the consumer a stable contract
     * for the lifetime of the stream; the user picks orientation, taps Start, and
     * everything downstream agrees on the size.
     *
     * RTSP already ignores rotation changes mid-stream (the encoder consumes the camera
     * Surface directly; see Roadmap), so this gating is a no-op there.
     */
    fun setDeviceRotation(rotation: Int) {
        val prev = currentRotation
        currentRotation = rotation
        if (_status.value.state != State.STREAMING) {
            cameraController?.setTargetRotation(rotation)
            return
        }
        if (prev == rotation) return
        val proto = _status.value.settings.protocol
        val rotationDegrees = when (rotation) {
            android.view.Surface.ROTATION_90 -> 90
            android.view.Surface.ROTATION_180 -> 180
            android.view.Surface.ROTATION_270 -> 270
            else -> 0
        }
        when (proto) {
            // SRT rebuilds only the camera/encoder/rotator in place — the SRT socket and the
            // connected receiver stay up (it re-inits on the new SPS), so rotation is seamless.
            // MPEG-TS allows the inline resolution change a 90° turn implies. Debounced so a
            // quick tilt doesn't thrash the pipeline.
            guru.freberg.lenscast.prefs.Protocol.SRT -> {
                rotationRestartJob?.cancel()
                rotationRestartJob = lifecycleScope.launch {
                    kotlinx.coroutines.delay(500)
                    if (_status.value.state != State.STREAMING) return@launch
                    Log.i(TAG, "Seamless SRT rotation → $rotationDegrees")
                    try { srtManager?.reconfigureVideo(rotationDegrees) } catch (t: Throwable) {
                        Log.w(TAG, "Seamless rotation failed; falling back to restart", t)
                        restartStreaming(_status.value.settings)
                    }
                }
            }
            // RTSP locks orientation at Start. Unlike SRT, RTSP clients fix their decoder
            // dimensions from the SDP at connect time and never re-read them, so a portrait↔
            // landscape turn can't be applied mid-session without forcing every client to
            // reconnect. Rather than silently ignore the turn or disrupt receivers, we keep the
            // orientation chosen at Start and tell the user why (once per actual turn).
            guru.freberg.lenscast.prefs.Protocol.RTSP -> {
                // Rate-limit: the accelerometer can flip-flop at an orientation boundary, and
                // we don't want a stack of Toasts. One message per 5 s of turning is plenty.
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastRtspLockMsgMs > 5_000) {
                    lastRtspLockMsgMs = now
                    emitMessage(getString(R.string.rtsp_rotation_locked_toast))
                }
            }
            else -> { /* MJPEG rolls with rotation already; WebRTC: no action */ }
        }
    }

    /** Last FPS upper bound the camera accepted — may be lower than requested if the lens caps out. */
    fun lastNegotiatedFps(): Int = cameraController?.lastNegotiatedFps() ?: 0

    /**
     * Total encoded/published frame count across whichever protocol is active.
     * MJPEG path: counted via [broadcaster]. RTSP path: counted by the H.264 encoder.
     */
    fun framesProducedNow(): Long = rtspManager?.framesProduced()?.takeIf { it > 0 }
        ?: srtManager?.framesProduced()?.takeIf { it > 0 }
        ?: broadcaster.framesProduced()

    /** Connected clients across both protocols (for the stats chip). */
    fun connectedClientCount(): Int = (rtspManager?.clientCount() ?: 0) +
        broadcaster.clientCount() + (webRtcManager?.clientCount() ?: 0) + srtClientCount()

    /** Per-peer SDP answer for a browser's WebRTC offer; null if WebRTC isn't running. */
    fun webRtcAnswer(offerSdp: String, remoteHostPort: String): String? =
        webRtcManager?.createAnswer(offerSdp, remoteHostPort)

    /** Cumulative bytes shipped over the wire since the active server started. */
    fun bytesSentNow(): Long = (rtspManager?.bytesSent() ?: 0L) + (mjpegServer?.bytesSent() ?: 0L) +
        (srtManager?.bytesSent() ?: 0L)

    /** `host:port` addresses of every currently streaming client across both protocols. */
    fun clientAddresses(): List<String> =
        (rtspManager?.clientAddresses() ?: emptyList()) +
        (mjpegServer?.clientAddresses() ?: emptyList()) +
        (webRtcManager?.clientAddresses() ?: emptyList())

    /** Closes whichever client (RTSP or MJPEG) matches `host:port`. Returns true on hit. */
    fun kickClient(remote: String): Boolean =
        (rtspManager?.kickClient(remote) ?: false) || (mjpegServer?.kickClient(remote) ?: false)

    fun unbindCamera() {
        if (streamingCamera) return
        try { cameraController?.unbind() } catch (_: Throwable) {}
        previewBoundKey = null
    }

    private fun ensureController(jpegQuality: Int): CameraController {
        val existing = cameraController
        if (existing != null) {
            existing.jpegQuality = jpegQuality
            return existing
        }
        val c = CameraController(this, broadcaster)
        c.jpegQuality = jpegQuality
        cameraController = c
        return c
    }

    fun setPreviewSurfaceProvider(provider: androidx.camera.core.Preview.SurfaceProvider?) {
        pendingPreviewProvider = provider
        cameraController?.attachPreviewSurface(provider)
    }

    fun setTorch(on: Boolean) {
        cameraController?.torchOn = on
        rtspManager?.setTorch(on)
        srtManager?.setTorch(on)
    }

    fun setZoomRatio(ratio: Float) { cameraController?.setZoomRatio(ratio) }
    fun currentZoomRatio(): Float = cameraController?.currentZoomRatio() ?: 1f

    fun tapToFocus(normalisedX: Float, normalisedY: Float) {
        cameraController?.focusAt(normalisedX, normalisedY)
    }

    fun setExposureEv(ev: Int) { cameraController?.applyExposureEv(ev) }
    fun setAudioGainDb(db: Int) { rtspManager?.setAudioGainDb(db) }
    /** Last-buffer peak in dBFS, clamped to -90..0. `-90` when no audio path is active. */
    /** "Clients" for SRT = 1 when connected, 0 otherwise (single-receiver protocol). */
    private fun srtClientCount(): Int = when (srtManager?.connectionState()) {
        guru.freberg.lenscast.streaming.srt.SrtPublisher.State.CONNECTED -> 1
        else -> 0
    }

    fun audioPeakDbfs(): Float = mjpegAudioCapture?.lastPeakDbfs()
        ?: rtspManager?.audioPeakDbfs()
        ?: srtManager?.audioPeakDbfs()
        ?: -90f
    /** URI of the most recently finalised MP4 recording, or null if none. */
    fun lastRecordingUri(): android.net.Uri? = _lastRecordingUri
    @Volatile private var _lastRecordingUri: android.net.Uri? = null

    /**
     * Save the most recently broadcast JPEG to MediaStore under
     * `Pictures/Lenscast/Lenscast_<timestamp>.jpg`. Returns the inserted Uri (for a Toast),
     * or null if no frame has been produced yet.
     */
    fun saveSnapshot(): Uri? {
        val bytes = broadcaster.latest()?.first ?: return null
        val name = "Lenscast_${System.currentTimeMillis()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Lenscast")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return null
        return try {
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
            uri
        } catch (t: Throwable) {
            Log.w(TAG, "Snapshot save failed", t)
            try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
            null
        }
    }

    /**
     * Stop the current stream and immediately restart with new settings. Used for
     * mid-stream lens switching on protocols where the camera is locked at start
     * (RTSP, SRT) — the H.264 input Surface is bound to one CameraDevice for the
     * stream's lifetime, so changing lens requires a full pipeline teardown. The
     * receiver sees a brief disconnect; well-behaved clients (OBS Media Source,
     * VLC, ffplay) reconnect automatically. Cheaper than nothing, less invasive
     * than asking the user to Stop / change settings / Start manually.
     */
    /**
     * Try to switch the camera lens mid-stream without restarting (which would drop the
     * receiver). Routes to the protocol manager's seamless in-place lens swap. Returns true if
     * handled; false means the caller should fall back to [restartStreaming] (e.g. RTSP where
     * the new lens forces a different resolution, or any non-camera-locked protocol). On
     * success the published settings are updated so the UI reflects the new lens.
     */
    suspend fun switchLensSeamless(newSettings: Settings): Boolean {
        if (_status.value.state != State.STREAMING) return false
        val size = Size(newSettings.resolution.width, newSettings.resolution.height)
        val handled = when (newSettings.protocol) {
            Protocol.SRT -> srtManager?.switchLens(newSettings.lens, size, newSettings.fps.value) ?: false
            Protocol.RTSP -> rtspManager?.switchLens(newSettings.lens, size, newSettings.fps.value) ?: false
            // WebRTC's capturer swaps the camera in place (no resolution lock — receivers
            // absorb the change), so any front↔back switch is seamless.
            Protocol.WEBRTC -> webRtcManager?.switchLens(newSettings.lens) ?: false
            else -> false
        }
        if (handled) _status.value = _status.value.copy(settings = newSettings)
        return handled
    }

    fun restartStreaming(newSettings: Settings) {
        _restarting.value = true
        if (_status.value.state == State.STREAMING || _status.value.state == State.STARTING) {
            stopStreaming()
        }
        startStreaming(newSettings)
    }

    fun stopStreaming() {
        rotationRestartJob?.cancel()
        rotationRestartJob = null
        try { mjpegServer?.stop() } catch (_: Throwable) {}
        mjpegServer = null
        tearDownSidecar()
        try { mjpegAudioCapture?.stop() } catch (_: Throwable) {}
        mjpegAudioCapture = null
        mjpegAudioBroadcaster = null
        // Grab the recording URI before nulling the manager so the activity can surface
        // a Toast linking to it after stopStreaming returns.
        _lastRecordingUri = rtspManager?.lastRecordingUri()
        try { rtspManager?.stop() } catch (_: Throwable) {}
        // rtspManager.stop() finalises the muxer; re-read so we surface the saved URI
        // (lastRecordingUri is only set after stop()).
        _lastRecordingUri = rtspManager?.lastRecordingUri() ?: _lastRecordingUri
        rtspManager = null
        // If the user has SFTP upload enabled, hand the freshly-finalised MP4 off to the
        // background queue. The queue is in-memory; if the user kills the app before the
        // upload completes, the recording stays in MediaStore and they can re-trigger it
        // from the "Upload last recording" button.
        _lastRecordingUri?.let { uri ->
            val name = displayNameForUri(uri) ?: "lenscast.mp4"
            sftpUploader.enqueue(uri, name)
        }
        try { webRtcManager?.stop() } catch (_: Throwable) {}
        webRtcManager = null
        try { srtManager?.stop() } catch (_: Throwable) {}
        srtManager = null
        stopNsd()
        _lastRtspPlan = null
        streamingCamera = false
        // Keep the controller around so the UI can immediately re-bind preview after stop.
        try { cameraController?.unbind() } catch (_: Throwable) {}
        previewBoundKey = null
        releaseWakeLock()
        // During a restart (rotate-phone → rebuild pipeline) we must NOT drop the service
        // out of foreground. startStreaming() will immediately call startForeground() again,
        // but Android 12+ forbids *starting* a foreground service from the background — and a
        // device rotation puts the activity mid-recreate, which counts as background. Leaving
        // the existing FGS up means the follow-up startForeground() is an allowed *update*,
        // not a forbidden fresh start. Crash was ForegroundServiceStartNotAllowedException.
        if (!_restarting.value) {
            // After streaming, decide where the service goes next: persistent web control
            // foreground (if the user opted in) or out of foreground entirely.
            val s = _status.value.settings
            val wantPersistent = s.persistentWebControl && s.webControlEnabled
            if (wantPersistent) {
                // Reuse the foreground state by swapping the notification to the web one.
                persistWebForeground = false  // will be re-set inside showWebControlForeground
                showWebControlForeground()
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                } else {
                    @Suppress("DEPRECATION")
                    stopForeground(true)
                }
            }
        }
        _status.value = Status(state = State.IDLE, settings = _status.value.settings)
    }

    override fun onDestroy() {
        stopStreaming()
        try { telephonyMonitor?.stop() } catch (_: Throwable) {}
        telephonyMonitor = null
        try { webControlServer?.stop() } catch (_: Throwable) {}
        webControlServer = null
        try { cameraController?.shutdown() } catch (_: Throwable) {}
        cameraController = null
        super.onDestroy()
    }

    private fun startForegroundWithType(settings: Settings) {
        val stopIntent = Intent(this, StreamingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val launchPending = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val port = when (settings.protocol) {
            Protocol.MJPEG  -> settings.mjpegPort
            Protocol.RTSP   -> settings.rtspPort
            Protocol.WEBRTC -> settings.webControlPort
            Protocol.SRT    -> settings.srtPort
        }
        val protoLabel = when (settings.protocol) {
            Protocol.MJPEG  -> "MJPEG"
            Protocol.RTSP   -> "RTSP"
            Protocol.WEBRTC -> "WebRTC"
            Protocol.SRT    -> "SRT"
        }
        val notif: Notification = NotificationCompat.Builder(this, LenscastApp.CHANNEL_STREAMING)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title_streaming))
            .setContentText(getString(R.string.notif_text_streaming, protoLabel, port))
            .setContentIntent(launchPending)
            .addAction(0, getString(R.string.notif_action_stop), stopPending)
            .setOngoing(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        val baseType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        // API 34+ refuses to start an FGS with `microphone` type unless RECORD_AUDIO is
        // already granted at runtime — including the type when the permission is missing
        // throws SecurityException and crashes the service. Drop the type silently here
        // so the camera-only stream still starts; the audio path itself bails earlier
        // (PcmCapture / RtspManager check the permission before opening AudioRecord).
        val micGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val type = if (settings.audioEnabled && micGranted)
            baseType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        else baseType
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, type)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Lenscast:streaming").apply {
            setReferenceCounted(false)
            acquire(8 * 60 * 60 * 1000L) // 8 hour safety cap
        }
    }

    private fun releaseWakeLock() {
        try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) {}
        wakeLock = null
    }

    companion object {
        private const val TAG = "StreamingService"
        private const val NOTIF_ID = 0xCA1
        const val ACTION_STOP = "guru.freberg.lenscast.action.STOP"
        const val ACTION_START_TILE = "guru.freberg.lenscast.action.START_TILE"
        /**
         * Internal action: promote the service to a SPECIAL_USE foreground notification so
         * the web control panel stays reachable while the app is backgrounded. Fired by the
         * boot receiver, by MainActivity on launch, and by `reconfigurePersistentForeground`
         * inside the service itself when the setting flips on at runtime.
         */
        const val ACTION_PERSIST_WEB = "guru.freberg.lenscast.action.PERSIST_WEB"
    }
}
