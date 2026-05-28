package dev.lenscast.streaming

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
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.lenscast.MainActivity
import dev.lenscast.LenscastApp
import dev.lenscast.R
import android.media.MediaRecorder
import dev.lenscast.camera.CameraCapabilities
import dev.lenscast.camera.CameraController
import dev.lenscast.net.NsdAdvertiser
import dev.lenscast.net.TlsManager
import dev.lenscast.prefs.AntiBanding
import dev.lenscast.prefs.CameraEffect
import dev.lenscast.prefs.MicSource
import dev.lenscast.prefs.Protocol
import dev.lenscast.prefs.SceneMode
import dev.lenscast.prefs.Settings
import dev.lenscast.prefs.SettingsCodec
import dev.lenscast.prefs.SettingsRepository
import dev.lenscast.prefs.WhiteBalance
import dev.lenscast.streaming.rtsp.AacEncoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    val broadcaster = FrameBroadcaster()
    private var cameraController: CameraController? = null
    private var mjpegServer: MjpegServer? = null
    private var mjpegAudioBroadcaster: AudioBroadcaster? = null
    private var mjpegAudioCapture: PcmCapture? = null
    private var rtspManager: RtspManager? = null
    private var nsd: NsdAdvertiser? = null
    private var webControlServer: WebControlServer? = null
    private var webControlSettings: Triple<Boolean, Int, Boolean>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pendingPreviewProvider: androidx.camera.core.Preview.SurfaceProvider? = null
    // (RTSP path runs without an on-device preview; see startRtsp for the reasoning.)
    /** Tracks the camera config we last bound with, so we know when to rebind. */
    private var previewBoundKey: BindKey? = null

    private data class BindKey(
        val lens: dev.lenscast.prefs.Lens,
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
    }

    private fun reconfigureWebControl(enabled: Boolean, port: Int, httpsEnabled: Boolean) {
        val previous = webControlSettings
        if (previous == Triple(enabled, port, httpsEnabled) && webControlServer != null) return
        try { webControlServer?.stop() } catch (_: Throwable) {}
        webControlServer = null
        if (enabled) {
            val srv = WebControlServer(
                port = port,
                control = mjpegControl,
                mjpegPort = { _status.value.settings.mjpegPort },
                rtspPort = { _status.value.settings.rtspPort },
                sslContext = if (httpsEnabled) TlsManager.sslContext() else null,
                mjpegIsHttps = { _status.value.settings.httpsEnabled },
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
                stopSelf()
            }
            ACTION_START_TILE -> {
                lifecycleScope.launch {
                    val saved = SettingsRepository(this@StreamingService).flow.first()
                    startStreaming(saved)
                }
            }
        }
        return START_NOT_STICKY
    }

    fun startStreaming(settings: Settings) {
        if (_status.value.state == State.STREAMING || _status.value.state == State.STARTING) return
        _status.value = Status(state = State.STARTING, settings = settings, activeProtocol = settings.protocol)
        startForegroundWithType(settings)
        acquireWakeLock()

        lifecycleScope.launch {
            try {
                when (settings.protocol) {
                    Protocol.MJPEG -> startMjpeg(settings)
                    Protocol.RTSP  -> startRtsp(settings)
                }
                _status.value = _status.value.copy(state = State.STREAMING, errorMessage = null)
            } catch (t: Throwable) {
                Log.e(TAG, "Start failed", t)
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
        val audioBroadcaster = if (settings.audioEnabled) {
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
            password = settings.streamPassword,
            audioBroadcaster = audioBroadcaster,
            sslContext = if (settings.httpsEnabled) TlsManager.sslContext() else null,
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
            _status.value.settings.lens == dev.lenscast.prefs.Lens.BACK
        override fun torchIsOn(): Boolean = cameraController?.torchOn == true
        override fun toggleTorch() { setTorch(!torchIsOn()) }
        override fun switchLens() {
            val repo = SettingsRepository(this@StreamingService)
            lifecycleScope.launch {
                repo.update {
                    val newLens = if (it.lens == dev.lenscast.prefs.Lens.BACK)
                        dev.lenscast.prefs.Lens.FRONT
                    else dev.lenscast.prefs.Lens.BACK
                    it.copy(lens = newLens)
                }
                // Rebind to the new lens with the current settings, including newLens.
                val s = repo.flow.first()
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
            val target = dev.lenscast.prefs.Resolution.entries.firstOrNull { it.label == label }
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
            val target = dev.lenscast.prefs.Fps.entries.firstOrNull { it.value == value } ?: return false
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
                "mjpeg" -> Protocol.MJPEG
                "rtsp"  -> Protocol.RTSP
                else    -> return false
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
                    val lens = when (v) { "back" -> dev.lenscast.prefs.Lens.BACK
                                          "front" -> dev.lenscast.prefs.Lens.FRONT
                                          else -> return null }
                    Pair({ it.copy(lens = lens) }, true)
                }
                "mirror"        -> Pair({ it.copy(mirror = b) }, false)
                "continuousAf"  -> Pair({ it.copy(continuousAf = b) }, true)
                "exposureEv"    -> {
                    val ev = value.toIntOrNull() ?: return null
                    Pair({ it.copy(exposureEv = ev) }, false)
                }
                "whiteBalance" -> {
                    val wb = dev.lenscast.prefs.WhiteBalance.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(whiteBalance = wb) }, true)
                }
                "antiBanding" -> {
                    val ab = dev.lenscast.prefs.AntiBanding.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(antiBanding = ab) }, true)
                }
                "effect" -> {
                    val e = dev.lenscast.prefs.CameraEffect.entries
                        .firstOrNull { it.name.equals(v, true) } ?: return null
                    Pair({ it.copy(effect = e) }, true)
                }
                "sceneMode" -> {
                    val sm = dev.lenscast.prefs.SceneMode.entries
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
                    val src = dev.lenscast.prefs.MicSource.entries
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
                    val rl = dev.lenscast.prefs.RotationLock.entries
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
            val lensLabel = if (s.lens == dev.lenscast.prefs.Lens.BACK) "back" else "front"
            val protoLabel = if (s.protocol == Protocol.MJPEG) "mjpeg" else "rtsp"
            val res = CameraCapabilities.supportedResolutions(this@StreamingService, s.lens)
                .joinToString(",") { "\"${it.label}\"" }
            val fps = CameraCapabilities.supportedFps(
                this@StreamingService, s.lens, s.resolution, s.protocol,
            ).joinToString(",")
            fun lower(any: Any) = any.toString().lowercase()
            // Hand-rolled JSON — values are well-known primitives, no escaping needed.
            return "{" +
                """"state":"$st","protocol":"$protoLabel","lens":"$lensLabel",""" +
                """"torch":${torchIsOn()},""" +
                """"resolution":"${s.resolution.label}",""" +
                """"availableResolutions":[$res],"availableFps":[$fps],""" +
                """"fps":${framesPerSecondNow()},"targetFps":${s.fps.value},""" +
                """"clients":${connectedClientCount()},"audioPeakDbfs":${audioPeakDbfs()},""" +
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
                """"tlsFingerprint":"${TlsManager.fingerprintSha256() ?: ""}",""" +
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
        if (_status.value.state == State.STREAMING && _status.value.activeProtocol == Protocol.RTSP) return
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
            manualExposure = s.manualExposure,
            iso = s.iso,
            shutterUs = s.shutterUs,
        )
    }

    @Volatile private var lastFpsCount: Long = 0
    @Volatile private var lastFpsT: Long = System.currentTimeMillis()

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
        val plan = mgr.start(
            RtspManager.Config(
                lens = settings.lens,
                resolution = Size(settings.resolution.width, settings.resolution.height),
                fps = settings.fps.value,
                videoBitrateBps = bitrate,
                audioEnabled = settings.audioEnabled,
                port = settings.rtspPort,
                deviceRotationDegrees = rotationDegrees,
                micSource = settings.micSource,
                audioGainDb = settings.audioGainDb,
                noiseSuppress = settings.noiseSuppress,
                echoCancel = settings.echoCancel,
                recordLocally = settings.recordLocally,
            ),
            previewSurface = null,
        )
        if (plan == null) {
            throw IllegalStateException(
                "No camera plan matched ${settings.resolution.label} @ ${settings.fps.value} fps on ${settings.lens}",
            )
        }
        _lastRtspPlan = plan
        startNsd(NsdAdvertiser.TYPE_RTSP, settings.rtspPort)
    }

    private fun startNsd(type: String, port: Int) {
        nsd?.stop()
        nsd = NsdAdvertiser(this).also { it.start(type, port) }
    }

    private fun stopNsd() {
        try { nsd?.stop() } catch (_: Throwable) {}
        nsd = null
    }

    private var _lastRtspPlan: dev.lenscast.streaming.rtsp.RtspCameraDriver.Plan? = null
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

    /**
     * Binds the camera with preview + ImageAnalysis. The analysis stream feeds the MJPEG
     * broadcaster continuously, so the MJPEG server can start/stop without a CameraX
     * rebind (which would briefly close the camera and show a black frame). RTSP mode
     * skips this path entirely — see [startRtsp].
     */
    suspend fun bindCameraIfNeeded(
        lens: dev.lenscast.prefs.Lens,
        resolution: dev.lenscast.prefs.Resolution,
        fps: Int,
        jpegQuality: Int,
        mirror: Boolean = false,
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
            cameraController?.setTargetRotation(currentRotation)
            cameraController?.applyExposureEv(exposureEv)
            return
        }
        val controller = ensureController(jpegQuality)
        controller.mirror = mirror
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
        currentRotation = rotation
        if (_status.value.state == State.STREAMING) return
        cameraController?.setTargetRotation(rotation)
    }

    /** Last FPS upper bound the camera accepted — may be lower than requested if the lens caps out. */
    fun lastNegotiatedFps(): Int = cameraController?.lastNegotiatedFps() ?: 0

    /**
     * Total encoded/published frame count across whichever protocol is active.
     * MJPEG path: counted via [broadcaster]. RTSP path: counted by the H.264 encoder.
     */
    fun framesProducedNow(): Long = rtspManager?.framesProduced()?.takeIf { it > 0 }
        ?: broadcaster.framesProduced()

    /** Connected clients across both protocols (for the stats chip). */
    fun connectedClientCount(): Int = (rtspManager?.clientCount() ?: 0) + broadcaster.clientCount()

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
    }

    fun setZoomRatio(ratio: Float) { cameraController?.setZoomRatio(ratio) }
    fun currentZoomRatio(): Float = cameraController?.currentZoomRatio() ?: 1f

    fun tapToFocus(normalisedX: Float, normalisedY: Float) {
        cameraController?.focusAt(normalisedX, normalisedY)
    }

    fun setExposureEv(ev: Int) { cameraController?.applyExposureEv(ev) }
    fun setAudioGainDb(db: Int) { rtspManager?.setAudioGainDb(db) }
    /** Last-buffer peak in dBFS, clamped to -90..0. `-90` when no audio path is active. */
    fun audioPeakDbfs(): Float = mjpegAudioCapture?.lastPeakDbfs()
        ?: rtspManager?.audioPeakDbfs()
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

    fun stopStreaming() {
        try { mjpegServer?.stop() } catch (_: Throwable) {}
        mjpegServer = null
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
        stopNsd()
        _lastRtspPlan = null
        streamingCamera = false
        // Keep the controller around so the UI can immediately re-bind preview after stop.
        try { cameraController?.unbind() } catch (_: Throwable) {}
        previewBoundKey = null
        releaseWakeLock()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        _status.value = Status(state = State.IDLE, settings = _status.value.settings)
    }

    override fun onDestroy() {
        stopStreaming()
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
        val port = if (settings.protocol == Protocol.MJPEG) settings.mjpegPort else settings.rtspPort
        val protoLabel = if (settings.protocol == Protocol.MJPEG) "MJPEG" else "RTSP"
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
        val type = if (settings.audioEnabled)
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
        const val ACTION_STOP = "dev.lenscast.action.STOP"
        const val ACTION_START_TILE = "dev.lenscast.action.START_TILE"
    }
}
