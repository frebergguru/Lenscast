package dev.lenscast.prefs

enum class Protocol { MJPEG, RTSP }

enum class Lens { BACK, FRONT }

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
)
