package guru.freberg.lenscast.prefs

enum class Protocol { MJPEG, RTSP, WEBRTC, SRT }

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

/** Maps directly to Camera2's `CONTROL_EFFECT_MODE_*`. Not every device supports every value. */
enum class CameraEffect { NONE, MONO, NEGATIVE, SEPIA, AQUA, SOLARIZE, POSTERIZE, BLACKBOARD, WHITEBOARD }

/** Maps directly to Camera2's `CONTROL_SCENE_MODE_*` (DISABLED → use `CONTROL_MODE_AUTO`). */
enum class SceneMode { DISABLED, ACTION, PORTRAIT, LANDSCAPE, NIGHT, SPORTS, THEATRE, FIREWORKS, BEACH, SNOW, SUNSET }

/**
 * Which `MediaRecorder.AudioSource` the RTSP audio path opens. Pickers differ in tonal
 * processing the OS applies before our encoder sees the samples — Camcorder is most
 * neutral, Voice Recognition has noise suppression, Voice Communication has AEC,
 * Unprocessed bypasses everything (when supported).
 */
enum class MicSource { CAMCORDER, MIC, VOICE_RECOGNITION, VOICE_COMMUNICATION, UNPROCESSED }

/**
 * What Lenscast does when a phone call comes in while a stream is live.
 *  - [IGNORE]        — current default. Audio keeps flowing; receivers hear the ringtone.
 *  - [MUTE_STREAM]   — zero the stream audio for the call's lifetime; restore on IDLE.
 *  - [DROP_CALL]     — auto-reject the incoming call via `TelecomManager.endCall()`. Needs
 *                      ANSWER_PHONE_CALLS, and silently degrades to MUTE_STREAM on devices
 *                      that block the API (some OEM skins do).
 */
enum class CallBehavior { IGNORE, MUTE_STREAM, DROP_CALL }

/**
 * A named snapshot of streaming-shape settings — protocol, resolution, fps, lens — that
 * the user can save and recall in one tap. Other Settings (audio knobs, image controls)
 * stay at whatever they currently are; presets are meant for the "which receiver am I
 * streaming to" decision rather than the long tail of niche knobs.
 */
data class Preset(
    val name: String,
    val protocol: Protocol,
    val resolution: Resolution,
    val fps: Fps,
    val lens: Lens,
)

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
     * Optional HTTP Basic auth for the MJPEG endpoints. Empty password = open access
     * (username is irrelevant in that case). Default username is "Lenscast"; users can
     * change it. Receivers embed both in the URL as
     * `http://<user>:<pwd>@<ip>:4747/video`.
     */
    val streamUsername: String = "Lenscast",
    val streamPassword: String = "",
    val autoStart: Boolean = false,
    /** RTSP encoder bitrate cap in kbps. 0 = use the resolution+fps heuristic. */
    val rtspBitrateKbps: Int = 0,
    /** When true, on-device preview is hidden during streaming to save battery. */
    val blankPreview: Boolean = false,
    val rotationLock: RotationLock = RotationLock.AUTO,
    val effect: CameraEffect = CameraEffect.NONE,
    val sceneMode: SceneMode = SceneMode.DISABLED,
    /**
     * When true, [continuousAf] is overridden — AF_MODE goes to OFF and [manualFocusDiopters]
     * drives `LENS_FOCUS_DISTANCE` (in diopters, 0 = infinity). Range is sensor-dependent;
     * UI clamps to the lens's `LENS_INFO_MINIMUM_FOCUS_DISTANCE`.
     */
    val manualFocus: Boolean = false,
    /** Diopters × 100 (so we can store as int). 0 = infinity. */
    val manualFocusCentidiopters: Int = 0,
    val startOnBoot: Boolean = false,
    val micSource: MicSource = MicSource.VOICE_RECOGNITION,
    /** Software audio gain in dB, clamped at apply time. 0 = unity. */
    val audioGainDb: Int = 0,
    val noiseSuppress: Boolean = false,
    val echoCancel: Boolean = false,
    val presets: List<Preset> = emptyList(),
    // RTSP-only. When enabled, the H.264 + AAC encoders also feed a MediaMuxer writing to
    // Movies/Lenscast/Lenscast_<ts>.mp4. The recording runs for the lifetime of the
    // stream; stopping the stream finalises the file.
    val recordLocally: Boolean = false,
    /** Web control panel — independent HTTP port, runs as long as the service is alive. */
    val webControlEnabled: Boolean = true,
    val webControlPort: Int = 8080,
    /**
     * When true, the streaming service stays in the foreground (low-priority notification,
     * SPECIAL_USE type) any time the web control panel is enabled — even when no stream is
     * running. Without this, the OS can reap the service shortly after the user
     * backgrounds the app and the panel goes silent. Cost: a persistent notification.
     */
    val persistentWebControl: Boolean = false,
    /**
     * Camera2 manual exposure. When true, AE goes OFF and [iso] / [shutterUs] take over.
     * Devices without the MANUAL_SENSOR capability silently ignore these — the UI hides
     * the controls in that case. EV-compensation is meaningless with AE off and the
     * camera will ignore it.
     */
    val manualExposure: Boolean = false,
    val iso: Int = 100,
    val shutterUs: Long = 16_666L, // 1/60 s
    val callBehavior: CallBehavior = CallBehavior.IGNORE,
    /**
     * When true, the MJPEG and web-control HTTP servers serve TLS instead of plain HTTP.
     * Cert is a self-signed RSA-2048 generated by AndroidKeyStore on first enable;
     * receivers see a hostname mismatch and self-signed warning that the user accepts
     * once per browser. RTSP isn't covered — most RTSP clients (OBS especially) don't
     * speak RTSPS, so adding it would break common workflows.
     */
    val httpsEnabled: Boolean = false,
    /**
     * Optional watermark drawn into every outgoing MJPEG frame. Blank = disabled.
     * The literal token `%t` is expanded to a wall-clock `HH:mm:ss` per frame, so the user
     * can stick `Lenscast %t` and get a self-updating timestamp.
     */
    val watermarkText: String = "",
    /**
     * Auto-upload finished MP4 recordings (RTSP path only) to a remote SFTP server. The
     * upload runs in the background after [recordLocally] finalises the file; failures
     * retry with backoff but do not block the next stream.
     */
    val sftpEnabled: Boolean = false,
    val sftpHost: String = "",
    val sftpPort: Int = 22,
    val sftpUser: String = "",
    val sftpPassword: String = "",
    /** Remote directory the file lands in. Blank = the user's home dir. */
    val sftpRemoteDir: String = "",
    /**
     * Optional SSH host-key fingerprint pinning. Format: hex string of either the SHA-256
     * or MD5 host-key hash (with or without `SHA256:` / `MD5:` prefix). Blank = TOFU on first
     * connect, accept any key thereafter.
     */
    val sftpHostKeyFingerprint: String = "",
    /**
     * BCP-47 language tag for in-app UI ("en", "nb"). Blank = follow the device system
     * locale. Applied via `AppCompatDelegate.setApplicationLocales` so the Activity
     * recreates with the new resources immediately. On API < 33 the change persists
     * across launches but doesn't reconfigure the running Activity without a restart.
     */
    val languageTag: String = "",
    /**
     * When true and protocol = RTSP at ≤30 fps, also expose the MJPEG endpoints (`/video`,
     * `/audio` if audio is on, `/shot.jpg`) on [mjpegPort] in parallel with the RTSP stream.
     * Implemented via an extra ImageReader output added to the Camera2 capture session so a
     * single camera open feeds both encoders. Cannot be enabled for high-speed sessions
     * (>30 fps) because high-speed only accepts MediaCodec/preview Surfaces.
     */
    val mjpegSidecar: Boolean = false,
    /**
     * Where to send the SRT stream (`Protocol.SRT`):
     *  - **LISTENER** (default): the phone listens on [srtPort]; receivers (OBS, ffmpeg)
     *    pull via `srt://<phone-ip>:<port>`.
     *  - **CALLER**: the phone dials out to a remote SRT listener at
     *    [srtHost]:[srtPort]. Easiest path through NAT.
     */
    val srtMode: SrtMode = SrtMode.LISTENER,
    val srtHost: String = "",
    val srtPort: Int = 9710,
    /**
     * Optional AES passphrase for the SRT encryption. 10–79 ASCII chars. Blank
     * disables encryption. Receivers must use the same passphrase.
     */
    val srtPassphrase: String = "",
    /**
     * SRT ARQ latency window in milliseconds. Controls how far back the receiver
     * can request retransmits. 120 ms is the libsrt default; 300 ms is safer on
     * cellular uplinks. Lower = lower glass-to-glass delay at the cost of more
     * dropped frames under packet loss. 200 ms is a LAN-friendly default that
     * keeps OBS Media Source perceived lag low — receivers can override per
     * connection via `?latency=N` in the SRT URL.
     */
    val srtLatencyMs: Int = 200,
    /**
     * Optional `streamid` for the listener to route the connection. The CALLER
     * sends it on connect; LISTENER-side filtering is the receiver's job.
     */
    val srtStreamId: String = "",
)

enum class SrtMode { CALLER, LISTENER }
