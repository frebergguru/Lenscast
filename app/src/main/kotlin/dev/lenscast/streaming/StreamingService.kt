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
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.lenscast.MainActivity
import dev.lenscast.LenscastApp
import dev.lenscast.R
import dev.lenscast.camera.CameraController
import dev.lenscast.prefs.Protocol
import dev.lenscast.prefs.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var rtspManager: RtspManager? = null
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                stopStreaming()
                stopSelf()
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
                _status.value = _status.value.copy(state = State.ERROR, errorMessage = t.message)
                stopStreaming()
            }
        }
    }

    private suspend fun startMjpeg(settings: Settings) {
        streamingCamera = true
        // The activity's LaunchedEffect keeps the CameraX session bound (with ImageAnalysis)
        // whenever the screen is on. We just need to make sure that's true here in case the
        // user tapped Start while the camera wasn't bound yet (e.g., permission just granted),
        // then start the HTTP server.
        bindCameraIfNeeded(settings.lens, settings.resolution, settings.fps.value, settings.jpegQuality)
        val server = MjpegServer(broadcaster, settings.mjpegPort, settings.fps.value).also { it.start() }
        mjpegServer = server
    }

    private suspend fun startRtsp(settings: Settings) {
        // The RTSP path owns Camera2 directly; tear down any CameraX preview-only session.
        try { cameraController?.unbind() } catch (_: Throwable) {}
        previewBoundKey = null
        streamingCamera = true

        // No on-device preview during RTSP streaming — the Compose layout swap on rotation
        // would otherwise tear down the SurfaceView/TextureView and abandon Camera2's
        // BufferQueue. The encoder still gets every frame; OBS still sees the live video.
        val bitrate = recommendedVideoBitrate(settings.resolution.width, settings.resolution.height, settings.fps.value)
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
    ) {
        val key = BindKey(lens, resolution.width, resolution.height, fps)
        if (key == previewBoundKey && cameraController != null) {
            cameraController?.jpegQuality = jpegQuality
            cameraController?.setTargetRotation(currentRotation)
            return
        }
        val controller = ensureController(jpegQuality)
        controller.bind(
            lifecycleOwner = this,
            lens = lens,
            targetResolution = Size(resolution.width, resolution.height),
            targetFps = fps,
            targetRotation = currentRotation,
            previewSurfaceProvider = pendingPreviewProvider,
            includeAnalysis = true,
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

    fun stopStreaming() {
        try { mjpegServer?.stop() } catch (_: Throwable) {}
        mjpegServer = null
        try { rtspManager?.stop() } catch (_: Throwable) {}
        rtspManager = null
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
        val type = if (settings.protocol == Protocol.RTSP && settings.audioEnabled)
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
    }
}
