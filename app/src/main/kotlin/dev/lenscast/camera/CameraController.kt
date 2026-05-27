package dev.lenscast.camera

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
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import dev.lenscast.prefs.Lens
import dev.lenscast.streaming.FrameBroadcaster
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
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

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(targetRotation)
        Camera2Interop.Extender(previewBuilder)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, negotiated)
        val preview = previewBuilder.build()
            .also { it.setSurfaceProvider(previewSurfaceProvider) }

        val analysis = if (includeAnalysis) {
            val analysisBuilder = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(resolutionSelector)
                .setTargetRotation(targetRotation)
            Camera2Interop.Extender(analysisBuilder)
                .setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, negotiated)
            analysisBuilder.build()
                .also {
                    it.setAnalyzer(analysisExecutor) { proxy ->
                        try {
                            val jpeg = YuvToJpeg.encode(proxy, jpegQuality)
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
