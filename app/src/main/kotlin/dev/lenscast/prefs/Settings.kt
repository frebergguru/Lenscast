package dev.lenscast.prefs

enum class Protocol { MJPEG, RTSP }

enum class Lens { BACK, FRONT }

/**
 * Manual white-balance presets backed by Camera2's `CONTROL_AWB_MODE` values. `AUTO` lets
 * the camera service pick; the rest pin the colour temperature even under tricky lighting.
 */
enum class WhiteBalance { AUTO, INCANDESCENT, FLUORESCENT, DAYLIGHT, CLOUDY, SHADE }

/**
 * Anti-flicker mode for auto-exposure under artificial lighting. `AUTO` lets Camera2 detect
 * the line frequency; pinning to 50 Hz (EU/AU) or 60 Hz (US) is more reliable on cheap
 * sensors. `OFF` disables anti-banding entirely.
 */
enum class AntiBanding { AUTO, HZ50, HZ60, OFF }

/**
 * Orientation override for the MJPEG stream. `AUTO` follows the accelerometer-driven
 * device rotation (current behaviour). The four explicit values pin the output rotation
 * regardless of how the phone is held — handy when the phone is on a tripod and the
 * sensor's natural orientation isn't what the user wants in OBS.
 */
enum class RotationLock { AUTO, PORTRAIT, LANDSCAPE_LEFT, LANDSCAPE_RIGHT, PORTRAIT_UPSIDE_DOWN }

enum class Resolution(val width: Int, val height: Int, val label: String) {
    P480(640, 480, "480p"),
    P720(1280, 720, "720p"),
    P1080(1920, 1080, "1080p"),
    P1440(2560, 1440, "1440p"),
    P2160(3840, 2160, "4K");

    companion object {
        fun fromOrdinal(o: Int): Resolution = entries.getOrNull(o) ?: P720
    }
}

enum class Fps(val value: Int) {
    FPS15(15), FPS24(24), FPS30(30), FPS60(60), FPS120(120), FPS240(240);

    /** Whether this FPS requires a Camera2 constrained-high-speed session (RTSP path only). */
    val isHighSpeed: Boolean get() = value > 30

    companion object {
        fun fromValue(v: Int): Fps = entries.firstOrNull { it.value == v } ?: FPS30
    }
}

data class Settings(
    val protocol: Protocol = Protocol.MJPEG,
    val resolution: Resolution = Resolution.P720,
    val fps: Fps = Fps.FPS30,
    val jpegQuality: Int = 80,
    val lens: Lens = Lens.BACK,
    val audioEnabled: Boolean = false,
    val keepScreenOn: Boolean = true,
    val mjpegPort: Int = 4747,
    val rtspPort: Int = 5540,
    // Image controls. All MJPEG-path only on the current pass; RTSP gets parity later.
    val mirror: Boolean = false,
    val continuousAf: Boolean = true,
    /** Exposure compensation in camera-native EV steps (0 = auto). Clamped at apply time. */
    val exposureEv: Int = 0,
    val whiteBalance: WhiteBalance = WhiteBalance.AUTO,
    val antiBanding: AntiBanding = AntiBanding.AUTO,
    /**
     * Optional HTTP Basic auth passcode for the MJPEG endpoints. Empty string = open access
     * (current behaviour). Username is fixed to `lenscast`; receivers can put it in the URL
     * as `http://lenscast:<pwd>@<ip>:4747/video`.
     */
    val streamPassword: String = "",
    val autoStart: Boolean = false,
    /** RTSP encoder bitrate cap in kbps. 0 = use the resolution+fps heuristic. */
    val rtspBitrateKbps: Int = 0,
    /** When true, on-device preview is hidden during streaming to save battery. */
    val blankPreview: Boolean = false,
    val rotationLock: RotationLock = RotationLock.AUTO,
)
