package guru.freberg.lenscast.streaming

import android.hardware.camera2.CaptureRequest
import android.util.Range
import guru.freberg.lenscast.prefs.AntiBanding
import guru.freberg.lenscast.prefs.CameraEffect
import guru.freberg.lenscast.prefs.SceneMode
import guru.freberg.lenscast.prefs.Settings
import guru.freberg.lenscast.prefs.WhiteBalance

/**
 * The sensor/ISP image controls, bundled so the H.264 paths (RTSP / SRT / RIST) can apply the
 * same picture the CameraX/MJPEG path does via [CameraController.applyCommonControls]. The
 * MJPEG path sets these through `Camera2Interop.Extender`; here [applyTo] sets the identical
 * `CaptureRequest` keys directly on the Camera2 driver's request builder.
 *
 * `mirror` is not a capture-request key — it's a horizontal flip applied in [GlRotator] for the
 * standard (≤30 fps) GL path. High-speed sessions have no GL stage and reject most of these
 * keys, so neither mirror nor the ISP controls below apply there (same constraint as rotation).
 */
data class ImageControls(
    val mirror: Boolean = false,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val antiBanding: AntiBanding = AntiBanding.AUTO,
    val continuousAf: Boolean = true,
    val exposureEv: Int = 0,
    val effect: CameraEffect = CameraEffect.NONE,
    val sceneMode: SceneMode = SceneMode.DISABLED,
    val manualFocus: Boolean = false,
    val manualFocusCentidiopters: Int = 0,
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val shutterUs: Long = 16_666L,
) {
    /**
     * Apply the ISP controls to a standard-session capture request, mirroring
     * `CameraController.applyCommonControls`. [evRange] is the sensor's
     * `CONTROL_AE_COMPENSATION_RANGE`; pass null to leave exposure compensation untouched.
     * Never call this for a constrained-high-speed request — it rejects most of these keys.
     */
    fun applyTo(b: CaptureRequest.Builder, evRange: Range<Int>?) {
        b.set(CaptureRequest.CONTROL_AWB_MODE, awbMode())
        b.set(CaptureRequest.CONTROL_AF_MODE, afMode())
        b.set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, antiBandingMode())
        b.set(CaptureRequest.CONTROL_EFFECT_MODE, effectMode())
        val scene = sceneModeInt()
        if (scene >= 0) {
            // Engaging a scene mode requires CONTROL_MODE = USE_SCENE_MODE.
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_USE_SCENE_MODE)
            b.set(CaptureRequest.CONTROL_SCENE_MODE, scene)
        } else {
            b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        }
        if (manualFocus) {
            b.set(CaptureRequest.LENS_FOCUS_DISTANCE, manualFocusCentidiopters / 100f)
        }
        if (manualExposure) {
            // AE off, then explicit sensitivity + exposure time. Lenses without the
            // MANUAL_SENSOR capability silently ignore these (the UI gates them).
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutterUs * 1_000L)
        } else if (evRange != null && !(evRange.lower == 0 && evRange.upper == 0)) {
            b.set(
                CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
                exposureEv.coerceIn(evRange.lower, evRange.upper),
            )
        }
    }

    private fun awbMode(): Int = when (whiteBalance) {
        WhiteBalance.AUTO         -> CaptureRequest.CONTROL_AWB_MODE_AUTO
        WhiteBalance.INCANDESCENT -> CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT
        WhiteBalance.FLUORESCENT  -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
        WhiteBalance.DAYLIGHT     -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
        WhiteBalance.CLOUDY       -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
        WhiteBalance.SHADE        -> CaptureRequest.CONTROL_AWB_MODE_SHADE
    }

    // Manual focus overrides continuous AF — AF_MODE goes OFF so LENS_FOCUS_DISTANCE applies.
    private fun afMode(): Int = when {
        manualFocus  -> CaptureRequest.CONTROL_AF_MODE_OFF
        continuousAf -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        else         -> CaptureRequest.CONTROL_AF_MODE_AUTO
    }

    private fun antiBandingMode(): Int = when (antiBanding) {
        AntiBanding.AUTO -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO
        AntiBanding.HZ50 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        AntiBanding.HZ60 -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ
        AntiBanding.OFF  -> CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF
    }

    private fun effectMode(): Int = when (effect) {
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

    /** -1 = "Disabled" so the caller leaves CONTROL_MODE at AUTO. */
    private fun sceneModeInt(): Int = when (sceneMode) {
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
}

/** Bundle the live image-control settings for the H.264 camera path. */
fun Settings.toImageControls(): ImageControls = ImageControls(
    mirror = mirror,
    whiteBalance = whiteBalance,
    antiBanding = antiBanding,
    continuousAf = continuousAf,
    exposureEv = exposureEv,
    effect = effect,
    sceneMode = sceneMode,
    manualFocus = manualFocus,
    manualFocusCentidiopters = manualFocusCentidiopters,
    manualExposure = manualExposure,
    iso = iso,
    shutterUs = shutterUs,
)
