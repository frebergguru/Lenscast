package guru.freberg.lenscast.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MeteringPoint
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import guru.freberg.lenscast.prefs.AntiBanding
import guru.freberg.lenscast.prefs.CameraEffect
import guru.freberg.lenscast.prefs.Lens
import guru.freberg.lenscast.prefs.SceneMode
import guru.freberg.lenscast.prefs.WhiteBalance
import guru.freberg.lenscast.streaming.FrameBroadcaster
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * CameraX wrapper used by the MJPEG streaming path.
 *
 * Owns two use cases:
 *  - Preview      → optional, attached to whatever Surface the UI provides.
 *  - ImageAnalysis → primary frame source. Latest YUV frame is encoded to JPEG and pushed
 *                    into [FrameBroadcaster].
 *
 * The service is the LifecycleOwner — so streaming survives screen-lock. The Activity can
 * attach/detach the preview surface independently.
 */
class CameraController(
    private val context: Context,
    private val broadcaster: FrameBroadcaster,
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageAnalysis: ImageAnalysis? = null
    @Volatile private var lastBoundFps: Int = 30

    @Volatile var jpegQuality: Int = 80
    @Volatile var mirror: Boolean = false
    @Volatile var torchOn: Boolean = false
        set(value) {
            field = value
            camera?.cameraControl?.enableTorch(value)
        }

    suspend fun bind(
        lifecycleOwner: LifecycleOwner,
        lens: Lens,
        targetResolution: Size,
        targetFps: Int,
        targetRotation: Int = Surface.ROTATION_0,
        previewSurfaceProvider: Preview.SurfaceProvider? = null,
        includeAnalysis: Boolean = true,
        whiteBalance: WhiteBalance = WhiteBalance.AUTO,
        antiBanding: AntiBanding = AntiBanding.AUTO,
        continuousAf: Boolean = true,
        exposureEv: Int = 0,
        effect: CameraEffect = CameraEffect.NONE,
        sceneMode: SceneMode = SceneMode.DISABLED,
        manualFocus: Boolean = false,
        manualFocusDiopters: Float = 0f,
        manualExposure: Boolean = false,
        iso: Int = 100,
        shutterUs: Long = 16_666L,
    ) {
        val p = awaitProvider()
        provider = p
        p.unbindAll()

        val selector = when (lens) {
            Lens.BACK  -> CameraSelector.DEFAULT_BACK_CAMERA
            Lens.FRONT -> CameraSelector.DEFAULT_FRONT_CAMERA
        }

        // Pick the actual FPS range the hardware supports for this lens. Camera2 will
        // refuse anything outside CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES — e.g. requesting
        // (60,60) on a sensor that only does (30,30) makes the capture session crash.
        val negotiated = negotiateFpsRange(lens, targetFps)
        lastBoundFps = negotiated.upper
        Log.i(TAG, "Requested fps=$targetFps, negotiated range=$negotiated for lens=$lens")

        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(targetResolution, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
            )
            .setAspectRatioStrategy(
                androidx.camera.core.resolutionselector.AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
            )
            .build()

        val awbMode = awbModeFor(whiteBalance)
        // Manual focus overrides continuousAf — AF_MODE goes OFF so the explicit
        // LENS_FOCUS_DISTANCE applies.
        val afMode = when {
            manualFocus -> CaptureRequest.CONTROL_AF_MODE_OFF
            continuousAf -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
            else -> CaptureRequest.CONTROL_AF_MODE_AUTO
        }
        val abMode = antiBandingModeFor(antiBanding)
        val effectMode = effectModeFor(effect)
        val scene = sceneModeFor(sceneMode)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
        Camera2Interop.Extender(previewBuilder)
            .applyCommonControls(
                negotiated, awbMode, afMode, abMode, effectMode, scene,
                manualFocus, manualFocusDiopters,
                manualExposure, iso, shutterUs,
            )
        val preview = previewBuilder.build()
            .also { it.setSurfaceProvider(previewSurfaceProvider) }

        val analysis = if (includeAnalysis) {
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)
            Camera2Interop.Extender(analysisBuilder)
                .applyCommonControls(
                    negotiated, awbMode, afMode, abMode, effectMode, scene,
                    manualFocus, manualFocusDiopters,
                    manualExposure, iso, shutterUs,
                )
            analysisBuilder.build()
                .also {
                    it.setAnalyzer(analysisExecutor) { proxy ->
                        try {
                            val jpeg = YuvToJpeg.encode(proxy, jpegQuality, mirror)
                            broadcaster.publish(jpeg)
                        } catch (t: Throwable) {
                            Log.w(TAG, "Frame encode failed: ${t.message}")
                        } finally {
                            proxy.close()
                        }
                    }
                }
        } else null

        camera = if (analysis != null) {
            p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
        } else {
            p.bindToLifecycle(lifecycleOwner, selector, preview)
        }
        this.preview = preview
        this.imageAnalysis = analysis
        camera?.cameraControl?.enableTorch(torchOn)
        applyExposureEv(exposureEv)
    }

    /** Live zoom — clamps to the lens's supported range. */
    fun setZoomRatio(ratio: Float) {
        val cam = camera ?: return
        val range = cam.cameraInfo.zoomState.value?.let { it.minZoomRatio..it.maxZoomRatio }
            ?: return
        cam.cameraControl.setZoomRatio(ratio.coerceIn(range.start, range.endInclusive))
    }

    /** Current zoom ratio, or 1.0 if unknown. Used to seed pinch-gesture state. */
    fun currentZoomRatio(): Float = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f

    /**
     * Tap-to-focus + tap-to-meter. Coordinates are normalised (0..1) within the preview
     * rectangle so the caller doesn't need to know surface pixel sizes.
     */
    fun focusAt(normalisedX: Float, normalisedY: Float) {
        val cam = camera ?: return
        val factory: MeteringPointFactory = SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point: MeteringPoint = factory.createPoint(
            normalisedX.coerceIn(0f, 1f),
            normalisedY.coerceIn(0f, 1f),
        )
        val action = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
        )
            // Auto-cancel returns to continuous AF after a few seconds so the stream
            // doesn't stay locked on whatever the user tapped.
            .setAutoCancelDuration(3, TimeUnit.SECONDS)
            .build()
        try { cam.cameraControl.startFocusAndMetering(action) } catch (_: Throwable) {}
    }

    fun applyExposureEv(ev: Int) {
        val cam = camera ?: return
        val range = cam.cameraInfo.exposureState.exposureCompensationRange
        if (range.lower == 0 && range.upper == 0) return
        val clamped = ev.coerceIn(range.lower, range.upper)
        try { cam.cameraControl.setExposureCompensationIndex(clamped) } catch (_: Throwable) {}
    }

    private fun awbModeFor(wb: WhiteBalance): Int = when (wb) {
        WhiteBalance.AUTO        -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        WhiteBalance.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalance.FLUORESCENT  -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        WhiteBalance.DAYLIGHT     -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalance.CLOUDY       -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalance.SHADE        -> CaptureRequest.CONTROL_AWB_MODE_SHADE
    }

    private fun antiBandingModeFor(ab: AntiBanding): Int = when (ab) {
        AntiBanding.AUTO -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        AntiBanding.HZ50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        AntiBanding.HZ60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        AntiBanding.OFF  -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
    }

    private fun effectModeFor(e: CameraEffect): Int = when (e) {
        CameraEffect.NONE       -> CaptureRequest.CONTROL_EFFECT_MODE_OFF
        CameraEffect.MONO       -> CaptureRequest.CONTROL_EFFECT_MODE_MONO
        CameraEffect.NEGATIVE   -> CaptureRequest.CONTROL_EFFECT_MODE_NEGATIVE
        CameraEffect.SEPIA      -> CaptureRequest.CONTROL_EFFECT_MODE_SEPIA
        CameraEffect.AQUA       -> CaptureRequest.CONTROL_EFFECT_MODE_AQUA
        CameraEffect.SOLARIZE   -> CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE
        CameraEffect.POSTERIZE  -> CaptureRequest.CONTROL_EFFECT_MODE_POSTERIZE
        CameraEffect.BLACKBOARD -> CaptureRequest.CONTROL_EFFECT_MODE_BLACKBOARD
        CameraEffect.WHITEBOARD -> CaptureRequest.CONTROL_EFFECT_MODE_WHITEBOARD
    }

    /** Returns -1 when the user picked "Disabled" so the caller skips the scene-mode opt-in. */
    private fun sceneModeFor(s: SceneMode): Int = when (s) {
        SceneMode.DISABLED  -> -1
        SceneMode.ACTION    -> CaptureRequest.CONTROL_SCENE_MODE_ACTION
        SceneMode.PORTRAIT  -> CaptureRequest.CONTROL_SCENE_MODE_PORTRAIT
        SceneMode.LANDSCAPE -> CaptureRequest.CONTROL_SCENE_MODE_LANDSCAPE
        SceneMode.NIGHT     -> CaptureRequest.CONTROL_SCENE_MODE_NIGHT
        SceneMode.SPORTS    -> CaptureRequest.CONTROL_SCENE_MODE_SPORTS
        SceneMode.THEATRE   -> CaptureRequest.CONTROL_SCENE_MODE_THEATRE
        SceneMode.FIREWORKS -> CaptureRequest.CONTROL_SCENE_MODE_FIREWORKS
        SceneMode.BEACH     -> CaptureRequest.CONTROL_SCENE_MODE_BEACH
        SceneMode.SNOW      -> CaptureRequest.CONTROL_SCENE_MODE_SNOW
        SceneMode.SUNSET    -> CaptureRequest.CONTROL_SCENE_MODE_SUNSET
    }

    /**
     * Bundles the per-bind Camera2Interop options so the Preview and ImageAnalysis builders
     * stay in sync — drift between them would leave one stream with stale colour controls
     * (the on-screen preview, since CameraX picks the analysis output as the "primary").
     */
    @Suppress("LongParameterList")
    private fun <T> Camera2Interop.Extender<T>.applyCommonControls(
        fpsRange: Range<Int>,
        awbMode: Int,
        afMode: Int,
        antiBandingMode: Int,
        effectMode: Int,
        sceneMode: Int,
        manualFocus: Boolean,
        manualFocusDiopters: Float,
        manualExposure: Boolean,
        iso: Int,
        shutterUs: Long,
    ): Camera2Interop.Extender<T> {
        setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fpsRange)
        setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, awbMode)
        setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afMode)
        setCaptureRequestOption(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antiBandingMode)
        setCaptureRequestOption(CaptureRequest.CONTROL_EFFECT_MODE, effectMode)
        if (sceneMode >= 0) {
            // Engaging a scene mode requires CONTROL_MODE=USE_SCENE_MODE.
            setCaptureRequestOption(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            setCaptureRequestOption(CaptureRequest.CONTROL_SCENE_MODE, sceneMode)
        }
        if (manualFocus) {
            setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusDiopters)
        }
        if (manualExposure) {
            // Manual exposure on Camera2: AE off, then explicit sensitivity + exposure
            // time. The camera silently ignores these on lenses without the
            // MANUAL_SENSOR capability — CameraCapabilities.supportsManualSensor gates
            // the UI so the user doesn't expect what won't work.
            setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
            setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterUs * 1_000L)
        }
        return this
    }

    /** The FPS upper bound the camera last accepted — for the UI to show "available capped at N". */
    fun lastNegotiatedFps(): Int = lastBoundFps

    fun attachPreviewSurface(provider: Preview.SurfaceProvider?) {
        preview?.setSurfaceProvider(provider)
    }

    /**
     * Update the target rotation on both use cases. CameraX uses this to set the
     * rotationDegrees field on each [androidx.camera.core.ImageProxy] so the consumer
     * (the JPEG encoder) produces upright frames regardless of how the phone is held.
     *
     * Cheap — doesn't tear down the capture session.
     */
    fun setTargetRotation(rotation: Int) {
        preview?.targetRotation = rotation
        imageAnalysis?.targetRotation = rotation
    }

    fun unbind() {
        try { provider?.unbindAll() } catch (_: Throwable) {}
        camera = null
        preview = null
        imageAnalysis = null
    }

    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
    }

    /**
     * Find the FPS range that best matches [targetFps] within the hardware's reported
     * `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES`. Strategy: prefer a fixed (n,n) range at
     * exactly the target; otherwise the range whose upper bound is closest to the target
     * without exceeding it. Falls back to (30,30) if the camera id can't be resolved.
     */
    private fun negotiateFpsRange(lens: Lens, targetFps: Int): Range<Int> {
        val ranges = supportedFpsRanges(lens) ?: return Range(30, 30)
        val capped = targetFps.coerceAtLeast(1)
        // 1. Exact fixed range match.
        ranges.firstOrNull { it.lower == capped && it.upper == capped }?.let { return it }
        // 2. Any range whose upper hits the target.
        ranges.filter { it.upper == capped }.maxByOrNull { it.lower }?.let { return it }
        // 3. The widest range that doesn't exceed the target on the upper bound, choosing
        //    the highest such upper. This is what gives 60 fps on devices that report
        //    (15,60) but not (60,60).
        ranges
            .filter { it.upper <= capped }
            .maxByOrNull { it.upper }
            ?.let { return it }
        // 4. Otherwise the lowest range available — better to underrun than crash.
        return ranges.minByOrNull { it.upper } ?: Range(30, 30)
    }

    private fun supportedFpsRanges(lens: Lens): Array<Range<Int>>? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val wantedFacing = when (lens) {
            Lens.BACK  -> CameraCharacteristics.LENS_FACING_BACK
            Lens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        }
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) == wantedFacing) {
                return ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            }
        }
        return null
    }

    private suspend fun awaitProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    cont.resume(future.get())
                } catch (t: Throwable) {
                    cont.cancel(t)
                }
            }, ContextCompat.getMainExecutor(context))
        }

    companion object {
        private const val TAG = "CameraController"
    }
}
