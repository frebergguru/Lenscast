package guru.freberg.lenscast.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Range
import android.util.Size
import guru.freberg.lenscast.prefs.Fps
import guru.freberg.lenscast.prefs.Lens
import guru.freberg.lenscast.prefs.Protocol
import guru.freberg.lenscast.prefs.Resolution
import guru.freberg.lenscast.streaming.rtsp.RtspCameraDriver

/**
 * Per-device capability queries used to keep the Settings UI honest. All checks read
 * `CameraCharacteristics` at runtime so the supported options reflect *this* phone's
 * sensors, not assumptions about Pixel-class hardware — front cameras on many phones
 * cap at 30 fps, only flagship back cameras advertise 240 fps high-speed, mid-range
 * devices fall somewhere in between. Querying dynamically means the FPS picker hides
 * options that would otherwise crash `startStreaming` with "No camera plan matched".
 */
object CameraCapabilities {

    /**
     * The set of `Fps.value` integers that this device's [lens] can produce at
     * [resolution] under [protocol]. Queries are cheap (read static metadata) so the
     * caller doesn't need to memoize aggressively, but `remember(lens, resolution,
     * protocol)` is fine.
     */
    fun supportedFps(
        context: Context,
        lens: Lens,
        resolution: Resolution,
        protocol: Protocol,
    ): Set<Int> {
        val size = Size(resolution.width, resolution.height)
        return Fps.entries
            .filter { isFpsSupported(context, lens, size, it, protocol) }
            .map { it.value }
            .toSet()
    }

    private fun isFpsSupported(
        context: Context,
        lens: Lens,
        size: Size,
        fps: Fps,
        protocol: Protocol,
    ): Boolean = when (protocol) {
        // MJPEG path uses CameraX's standard Preview/ImageAnalysis use cases. Anything
        // above 30 fps requires a constrained-high-speed session (RTSP only) — so for
        // MJPEG we accept only non-high-speed values that the lens actually advertises
        // in CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES. Most phones do 15/24/30 on both
        // lenses, but some budget devices cap the front sensor lower.
        Protocol.MJPEG -> !fps.isHighSpeed && lensHasAeRangeContaining(context, lens, fps.value)

        // RTSP path: delegate to the existing planner. It already does the right thing
        // for both ≤30 fps (standard session) and >30 fps (high-speed config search,
        // including resolution fallback when the exact requested size isn't a valid
        // high-speed size). If plan() returns null, the combo would crash at start.
        Protocol.RTSP -> RtspCameraDriver.plan(context, lens, size, fps.value) != null

        // WebRTC path also owns Camera2 directly (via Camera2Capturer). Same constraints
        // as MJPEG: standard sessions, ≤30 fps. We don't probe MediaCodec here — the
        // PeerConnectionFactory's encoder picker chooses what the device supports.
        Protocol.WEBRTC -> !fps.isHighSpeed && lensHasAeRangeContaining(context, lens, fps.value)

        // SRT reuses the RTSP camera driver and H.264 encoder; same capability constraints.
        Protocol.SRT -> RtspCameraDriver.plan(context, lens, size, fps.value) != null
    }

    /**
     * Replace [current] with the closest still-supported alternative when the user
     * changes lens or resolution. "Closest" = highest supported FPS at or below the
     * current value; falls back to the single highest supported when nothing is
     * at-or-below. Final fallback is 30 fps which every modern phone hits on every
     * sensor.
     */
    fun nextBestFps(supported: Set<Int>, current: Fps): Fps {
        if (current.value in supported) return current
        Fps.entries
            .filter { it.value in supported && it.value <= current.value }
            .maxByOrNull { it.value }
            ?.let { return it }
        Fps.entries
            .filter { it.value in supported }
            .maxByOrNull { it.value }
            ?.let { return it }
        return Fps.FPS30
    }

    /**
     * The set of [Resolution]s this device's [lens] can output. Determined from the
     * sensor's `SCALER_STREAM_CONFIGURATION_MAP` — both common output-class targets are
     * checked (SurfaceTexture for the preview path, YUV_420_888 for ImageAnalysis) and
     * the union is returned. So if the camera advertises a size in any path our pipeline
     * uses, it shows up in the picker.
     *
     * Back cameras on modern phones typically reach 1440p or 4K; front cameras usually
     * cap at 1080p. Mid-range devices may not even hit 1080p on the front. Querying at
     * runtime means the UI always reflects this specific sensor.
     */
    fun supportedResolutions(context: Context, lens: Lens): Set<Resolution> {
        val sizes = cameraOutputSizes(context, lens) ?: return Resolution.entries.toSet()
        return Resolution.entries
            .filter { res -> sizes.any { it.width == res.width && it.height == res.height } }
            .toSet()
    }

    /**
     * Replace [current] with the closest still-supported alternative when the user
     * changes lens. Prefers the highest supported size at or below the current; falls
     * back to the single highest supported size. Final fallback is 720p which any
     * camera-equipped Android phone supports.
     */
    fun nextBestResolution(supported: Set<Resolution>, current: Resolution): Resolution {
        if (current in supported) return current
        val currentPixels = current.width.toLong() * current.height
        Resolution.entries
            .filter { it in supported && (it.width.toLong() * it.height) <= currentPixels }
            .maxByOrNull { it.width.toLong() * it.height }
            ?.let { return it }
        Resolution.entries
            .filter { it in supported }
            .maxByOrNull { it.width.toLong() * it.height }
            ?.let { return it }
        return Resolution.P720
    }

    private fun cameraOutputSizes(context: Context, lens: Lens): List<android.util.Size>? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val wantedFacing = when (lens) {
            Lens.BACK  -> CameraCharacteristics.LENS_FACING_BACK
            Lens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        }
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != wantedFacing) continue
            val cfg = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: continue
            // Two output classes our pipeline uses: SurfaceTexture (CameraX Preview) and
            // YUV_420_888 (ImageAnalysis for MJPEG). Union them — any size advertised in
            // either is reachable from at least one streaming path.
            val texSizes = cfg.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
            val yuvSizes = cfg.getOutputSizes(android.graphics.ImageFormat.YUV_420_888).orEmpty()
            return (texSizes.toList() + yuvSizes.toList()).distinct()
        }
        return null
    }

    /** ISO range the sensor advertises, or null if unsupported / camera missing. */
    fun isoRange(context: Context, lens: Lens): Range<Int>? =
        characteristic(context, lens) { it.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }

    /** Exposure time range in nanoseconds, or null if unsupported. */
    fun exposureTimeRangeNs(context: Context, lens: Lens): Range<Long>? =
        characteristic(context, lens) { it.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }

    /**
     * True when this lens declares the MANUAL_SENSOR capability — that's the prerequisite
     * for `CONTROL_AE_MODE = OFF` + `SENSOR_SENSITIVITY` + `SENSOR_EXPOSURE_TIME`. Most
     * Pixels and high-end Samsung / OnePlus phones do; budget devices often don't.
     */
    fun supportsManualSensor(context: Context, lens: Lens): Boolean =
        characteristic(context, lens) { ch ->
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return@characteristic false
            caps.any { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR }
        } ?: false

    private inline fun <T> characteristic(context: Context, lens: Lens, read: (CameraCharacteristics) -> T?): T? {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
        val wantedFacing = when (lens) {
            Lens.BACK  -> CameraCharacteristics.LENS_FACING_BACK
            Lens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        }
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != wantedFacing) continue
            return read(ch)
        }
        return null
    }

    private fun lensHasAeRangeContaining(context: Context, lens: Lens, fps: Int): Boolean {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return false
        val wantedFacing = when (lens) {
            Lens.BACK  -> CameraCharacteristics.LENS_FACING_BACK
            Lens.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
        }
        for (id in cm.cameraIdList) {
            val ch = cm.getCameraCharacteristics(id)
            if (ch.get(CameraCharacteristics.LENS_FACING) != wantedFacing) continue
            val ranges: Array<Range<Int>> =
                ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: return false
            return ranges.any { fps in it.lower..it.upper }
        }
        return false
    }
}
