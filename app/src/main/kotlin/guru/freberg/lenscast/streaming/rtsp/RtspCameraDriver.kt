package guru.freberg.lenscast.streaming.rtsp

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.core.content.ContextCompat
import guru.freberg.lenscast.prefs.Lens
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Owns the Camera2 lifecycle for the RTSP path. Two modes:
 *
 *  - **Standard session** when target fps ≤ 30: a regular `CameraCaptureSession` outputs to
 *    the encoder Surface plus an optional preview Surface. AE_TARGET_FPS_RANGE is set
 *    via the closest range from `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`.
 *  - **Constrained-high-speed session** when target fps > 30: a high-speed session whose
 *    output is the H.264 MediaCodec input Surface (which IS an allowed output type for
 *    high-speed sessions — unlike `ImageReader` YUV, which is what blocked us in MJPEG).
 *
 * Picks the best supported `(size, fps range)` combination at runtime via the streaming-config
 * map; nothing is hardcoded per device.
 */
class RtspCameraDriver(
    private val context: Context,
) {
    data class Plan(
        val cameraId: String,
        val size: Size,
        val fpsRange: Range<Int>,
        val highSpeed: Boolean,
        val sensorOrientation: Int,
    )

    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var torchOn: Boolean = false
        set(value) { field = value; reapplyTorch() }
    private val cameraThread = HandlerThread("LenscastRtspCam").also { it.start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private var currentPlan: Plan? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var sidecarSurface: Surface? = null

    /** Instance method delegates to the companion for backwards compatibility. */
    fun plan(lens: Lens, requested: Size, targetFps: Int): Plan? =
        plan(context, lens, requested, targetFps)

    /**
     * Opens the camera and starts capturing into [encoderSurface] (mandatory) and the
     * optional [previewSurface]. Both must have a buffer size matching `plan.size` for
     * high-speed sessions (Surfaces from MediaCodec's input surface are sized at config
     * time; the preview SurfaceView must use [SurfaceHolder.setFixedSize] to match).
     */
    /**
     * @param sidecarSurface an optional extra output (typically an ImageReader's surface) the
     *   capture session writes to in parallel with [encoderSurface]. Only legal for standard
     *   sessions (fps ≤ 30) — high-speed sessions reject anything but encoder/preview Surfaces.
     *   When [plan.highSpeed] is true and a sidecar is supplied, the sidecar is silently
     *   ignored so the high-speed path keeps working.
     */
    @SuppressLint("MissingPermission")
    suspend fun start(
        plan: Plan,
        encoderSurface: Surface,
        previewSurface: Surface?,
        sidecarSurface: Surface? = null,
    ) {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "CAMERA permission must be granted before RtspCameraDriver.start"
        }
        stop()
        currentPlan = plan
        this.encoderSurface = encoderSurface
        this.previewSurface = previewSurface
        this.sidecarSurface = if (plan.highSpeed) null else sidecarSurface

        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val opened = suspendCancellableCoroutine<CameraDevice> { cont ->
            cm.openCamera(plan.cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    if (cont.isActive) cont.resume(camera)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    Log.w(TAG, "Camera disconnected")
                    try { camera.close() } catch (_: Throwable) {}
                    device = null
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    Log.e(TAG, "Camera error code=$error")
                    try { camera.close() } catch (_: Throwable) {}
                    device = null
                    if (cont.isActive) cont.resumeWithException(RuntimeException("Camera open failed: $error"))
                }
            }, cameraHandler)
        }
        device = opened

        val outputs = listOfNotNull(encoderSurface, previewSurface, this.sidecarSurface)
        if (plan.highSpeed) startHighSpeed(opened, outputs)
        else                startStandard(opened, outputs)
    }

    private suspend fun startHighSpeed(device: CameraDevice, outputs: List<Surface>) {
        val plan = currentPlan ?: return
        @Suppress("DEPRECATION")
        val created = suspendCancellableCoroutine<CameraCaptureSession> { cont ->
            device.createConstrainedHighSpeedCaptureSession(
                outputs,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        Log.e(TAG, "High-speed session config failed")
                        if (cont.isActive) cont.resumeWithException(RuntimeException("High-speed session config failed"))
                    }
                },
                cameraHandler,
            )
        }
        session = created
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            for (s in outputs) addTarget(s)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            applyTorch(this, torchOn)
        }
        val hsSession = created as CameraConstrainedHighSpeedCaptureSession
        val burst = hsSession.createHighSpeedRequestList(builder.build())
        hsSession.setRepeatingBurst(burst, null, cameraHandler)
        Log.i(TAG, "Capture session running (high-speed, fps=${plan.fpsRange}, size=${plan.size})")
    }

    private suspend fun startStandard(device: CameraDevice, outputs: List<Surface>) {
        val plan = currentPlan ?: return
        val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                val cfgs = outputs.map { OutputConfiguration(it) }
                val sc = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    cfgs,
                    { it.run() }, // direct executor
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(s: CameraCaptureSession) {
                            if (cont.isActive) cont.resume(s)
                        }
                        override fun onConfigureFailed(s: CameraCaptureSession) {
                            Log.e(TAG, "Standard session config failed")
                            if (cont.isActive) cont.resumeWithException(RuntimeException("Standard session config failed"))
                        }
                    },
                )
                device.createCaptureSession(sc)
            }
        } else {
            @Suppress("DEPRECATION")
            suspendCancellableCoroutine<CameraCaptureSession> { cont ->
                device.createCaptureSession(outputs, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resume(s)
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        if (cont.isActive) cont.resumeWithException(RuntimeException("Standard session config failed"))
                    }
                }, cameraHandler)
            }
        }
        session = created
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            for (s in outputs) addTarget(s)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            applyTorch(this, torchOn)
        }
        created.setRepeatingRequest(builder.build(), null, cameraHandler)
        Log.i(TAG, "Capture session running (standard, fps=${plan.fpsRange}, size=${plan.size})")
    }

    fun setTorch(on: Boolean) { torchOn = on }

    private fun reapplyTorch() {
        val plan = currentPlan ?: return
        val s = session ?: return
        val d = device ?: return
        try {
            val builder = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                encoderSurface?.let { addTarget(it) }
                previewSurface?.let { addTarget(it) }
                sidecarSurface?.let { addTarget(it) }
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, plan.fpsRange)
                applyTorch(this, torchOn)
            }
            if (plan.highSpeed) {
                val hs = s as CameraConstrainedHighSpeedCaptureSession
                hs.setRepeatingBurst(hs.createHighSpeedRequestList(builder.build()), null, cameraHandler)
            } else {
                s.setRepeatingRequest(builder.build(), null, cameraHandler)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Torch update failed: ${t.message}")
        }
    }

    private fun applyTorch(b: CaptureRequest.Builder, on: Boolean) {
        b.set(
            CaptureRequest.FLASH_MODE,
            if (on) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF,
        )
    }

    fun stop() {
        try { session?.close() } catch (_: Throwable) {}
        session = null
        try { device?.close() } catch (_: Throwable) {}
        device = null
        encoderSurface = null
        previewSurface = null
        sidecarSurface = null
    }

    fun shutdown() {
        stop()
        cameraThread.quitSafely()
    }

    fun sensorOrientation(): Int = currentPlan?.sensorOrientation ?: 0

    companion object {
        private const val TAG = "RtspCameraDriver"

        /**
         * Plans a session for the request without opening a camera. Pure query of
         * `CameraCharacteristics`; safe to call from the UI thread for sizing decisions.
         * Returns null if no usable config exists.
         *
         * Strategy:
         *   1. fps ≤ 30 → standard session at the requested size, fps clamped to nearest
         *      AE_AVAILABLE_TARGET_FPS_RANGE.
         *   2. fps > 30 → look for a high-speed config that supports the target fps at the
         *      requested size, then at any supported size at-or-below it.
         */
        fun plan(context: Context, lens: Lens, requested: Size, targetFps: Int): Plan? {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            val wantedFacing = when (lens) {
                Lens.BACK  -> CameraCharacteristics.LENS_FACING_BACK
                Lens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            }
            for (id in cm.cameraIdList) {
                val ch = cm.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.LENS_FACING) != wantedFacing) continue
                val sensorOrientation = ch.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

                if (targetFps <= 30) {
                    val standardRange = pickStandardFpsRange(ch, targetFps)
                    return Plan(id, requested, standardRange, highSpeed = false, sensorOrientation = sensorOrientation)
                }

                val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: continue
                val hasHs = caps.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO }
                if (!hasHs) continue
                val cfg = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
                val sizesAtOrBelow = cfg.highSpeedVideoSizes
                    .filter { it.width <= requested.width && it.height <= requested.height }
                    .sortedByDescending { it.width.toLong() * it.height }
                val candidates = (listOf(requested) + sizesAtOrBelow).distinct()
                for (size in candidates) {
                    val ranges = runCatching { cfg.getHighSpeedVideoFpsRangesFor(size) }.getOrNull() ?: continue
                    val range = pickFpsRange(ranges, targetFps) ?: continue
                    return Plan(id, size, range, highSpeed = true, sensorOrientation = sensorOrientation)
                }
            }
            return null
        }

        private fun pickStandardFpsRange(ch: CameraCharacteristics, target: Int): Range<Int> {
            val ranges = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return Range(target, target)
            ranges.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
            ranges.filter { it.upper == target }.maxByOrNull { it.lower }?.let { return it }
            ranges.filter { it.upper <= target }.maxByOrNull { it.upper }?.let { return it }
            return ranges.maxByOrNull { it.upper } ?: Range(30, 30)
        }

        private fun pickFpsRange(ranges: Array<Range<Int>>, target: Int): Range<Int>? {
            // 1. Exact fixed (target, target) range — caps the rate to the request.
            ranges.firstOrNull { it.lower == target && it.upper == target }?.let { return it }
            // 2. Range whose upper bound equals the target — same effect, AE won't exceed.
            ranges.filter { it.upper == target }.maxByOrNull { it.lower }?.let { return it }
            // 3. Range containing the target — prefer SMALLEST upper bound so AE doesn't
            //    drift far above the user's request (otherwise (60,240) gets picked over
            //    (60,120) and the camera runs at 240 fps in bright light).
            val containing = ranges.filter { target in it.lower..it.upper }
            if (containing.isNotEmpty()) return containing.minByOrNull { it.upper }
            return ranges.filter { it.upper <= target }.maxByOrNull { it.upper }
                ?: ranges.minByOrNull { it.upper }
        }
    }
}
