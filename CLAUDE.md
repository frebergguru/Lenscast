# Lenscast — Claude orientation

Lenscast turns an Android phone into a network camera that OBS Studio (and any HTTP
client) can consume directly. Five streaming protocols are live today:

- **MJPEG over HTTP** (default port 4747) — works in any orientation, up to 30 fps.
- **RTSP** (H.264 + AAC, default port 5540) — orientation-correct (portrait/landscape) at
  ≤30 fps via an EGL/GL rotation stage; high-speed 60/120/240 fps sessions still ship the
  sensor's native landscape (constrained Camera2 can't take the GL SurfaceTexture). The
  server negotiates version per request: **RTSP 1.0** (RFC 2326) for OBS/VLC/ffmpeg and
  **RTSP 2.0** (RFC 7826) for 2.0 clients, with 1.0 wire output kept byte-identical.
- **SRT** (H.264 + AAC over MPEG-TS, default port 9710) — orientation-correct via the same
  GL rotation stage, and rotates seamlessly mid-stream without dropping the receiver.
- **RIST** (H.264 + AAC over MPEG-TS, default data port 5004) — VSF **Simple and Main**
  profiles, implemented in **pure Kotlin** (no librist/JNI). Simple: RTP/MP2T + RTCP-NACK
  retransmit (data port + RTCP on +1). Main: GRE v2 over a single UDP port with PSK
  **AES-CTR** encryption (`RistCrypto`, PBKDF2-HMAC-SHA256 / IV from the GRE seq, matching
  librist). Shares the SRT camera pipeline + `MpegTsMuxer`, so it rotates and switches lens
  mid-stream the same way. NULL-packet suppression and DTLS are not implemented.
- **WebRTC** — browser playback at `/webrtc/view` plus a WHEP endpoint
  (`POST`/`DELETE /whep/<id>`), both served off the **web-control port** (default 8080),
  not a port of its own. Owns Camera2 directly like RTSP (via `Camera2Capturer`).

The MJPEG, RTSP, SRT, and RIST ports are user-editable in the Settings sheet (range
1024–65535). The codec paths (RTSP/SRT/RIST) can also run an optional **MJPEG sidecar**
(`Settings.mjpegSidecar`, on by default, ≤30 fps) that serves a parallel `/video` browser
preview and drives the on-device preview off the same camera. Beyond streaming, the app
also runs a **web control panel** (`WebControlServer`, default port 8080) with full
settings parity and start/stop, plus optional **local MP4 recording**, **SFTP upload**, a
**text watermark**, and **HTTPS / RTSPS** (self-signed RSA-2048, Bouncy Castle PKCS12). For
"phone-as-regular-webcam" use cases (Zoom, Chrome, Discord) there's an optional Linux
helper at `pc/lenscast-virtualcam` and a DeviceAsWebcam nudge in the Settings sheet for
Android 14+ devices that ship the system service. OBS users don't need either —
OBS reads the MJPEG/RTSP URL via its built-in Media Source. See
[Docs/Webcam.md](Docs/Webcam.md).

This was renamed from "OBSCam" in May 2026 (the original name was already taken by
another project, and Lenscast covers the future non-OBS use cases too).

## How to build and run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p guru.freberg.lenscast -c android.intent.category.LAUNCHER 1
```

Build environment quirks (full detail in [Docs/Build.md](Docs/Build.md)):

- Gradle daemon is pinned to JDK 21 via `org.gradle.java.home` in `gradle.properties`.
  The user's machine has JDK 21 and JDK 26; AGP 8.5 supports up to JDK 21.
- Android SDK lives at `~/Android/Sdk` on the user's machine. `local.properties` points
  there.
- `compileSdk = 35`, `minSdk = 26`, `targetSdk = 35`. AGP 8.5.2 throws a warning on
  compileSdk=35 — suppressed in `gradle.properties`.

## Architecture in one minute

- **`MainActivity`** binds to **`StreamingService`** (a `LifecycleService`). The service
  is the camera's `LifecycleOwner`, so streaming survives screen-lock and backgrounding.
- **`CameraController`** wraps CameraX with two use cases: `Preview` (always) and
  `ImageAnalysis` (only when streaming).
- **`StreamingService.bindCameraIfNeeded(lens, resolution, jpegQuality, withAnalysis)`**
  is the single entry point — it handles both preview-only mode and streaming mode.
  Idempotent on the `(lens, w, h, withAnalysis)` key.
- **`YuvToJpeg.encode`** converts each `YUV_420_888` `ImageProxy` to NV21, rotates by
  `rotationDegrees`, then `YuvImage.compressToJpeg`. The NV21 copy is required —
  skipping it lets `YuvImage` emit corrupted JPEGs when `rowStride ≠ width`.
- **`FrameBroadcaster`** is a single `AtomicReference<ByteArray>` holding the latest
  JPEG. Lock-free, no per-client queues. Fed by CameraX on the MJPEG path and by the
  MJPEG-sidecar `ImageReader` on the codec paths; read by `MjpegServer` *and* the on-device
  Compose preview.
- **`MjpegServer`** is a hand-rolled `ServerSocket` + coroutine-per-client. Serves
  `/video`, `/shot.jpg`, `/` (landing page), the `/audio` PCM-WAV sidecar, and the
  WebRTC/WHEP handshake routes (`webRtcAnswer`, `webRtcWhepCreate`, `webRtcWhepDelete`).
- **`streaming/rtsp/`** is the parallel RTSP path: `RtspServer` (sockets + RTSP verbs,
  version-negotiated 1.0/2.0, multi-client fan-out, TCP-interleaved + UDP transports,
  reads RTCP RR back for the adaptive-bitrate loop), `RtspCameraDriver` (Camera2 high-speed
  session), `H264Encoder` + `AacEncoder`, RTP packetizers, SDP builder. Owns Camera2
  directly while streaming, so CameraX is bypassed; the on-device preview during streaming
  is rendered from the MJPEG sidecar's JPEG frames (see the sidecar note under "RTSP / SRT
  path caveats"), falling back to a placeholder when the sidecar is off.
- **`streaming/srt/`** is the SRT path: `SrtManager` (lifecycle + in-place
  `reconfigureVideo` on rotation), `SrtPublisher`, `MpegTsMuxer`. Shares the `GlRotator`
  EGL stage and the H.264/AAC encoders.
- **`streaming/rist/`** is the RIST path: `RistManager` (a structural copy of `SrtManager`),
  `RistPublisher` (pure-Kotlin RIST Simple **and** Main profiles over `DatagramSocket` —
  RTP/MP2T + RTCP-NACK retransmit, with Main adding GRE v2 framing on a single port), and
  `RistCrypto` (Main-profile PSK AES-CTR). Reuses `srt/MpegTsMuxer` and `rtsp/Rtcp`; no
  native library.
- **`streaming/webrtc/WebRtcManager`** drives the WebRTC/WHEP egress (also Camera2-owning).
- **`WebControlServer`** is an independent HTTP control panel on its own port (default
  8080) — start/stop and full settings parity from a browser; runs whenever the app is up.
- **`RestApiServer`** is the machine-facing JSON REST API on its own port (default 8088,
  user-toggleable) for third-party control apps/plugins — start/stop, camera quick-controls,
  and the full settings surface as JSON with real status codes. **Bearer-token** auth
  (`Settings.apiToken`, CSPRNG-generated via `net/ApiToken`, sealed at rest by `SecretCipher`,
  never echoed over the wire); **fail-closed** (binds only when enabled *and* a token exists).
  Routes every action through the same `MjpegControl` bridge as the panel, so it inherits all
  the blocking gates for free. See [Docs/API.md](Docs/API.md).
- **`GlRotator`** (in `streaming/`) is the shared EGL/GL rotation stage for the RTSP/SRT
  H.264 paths. `RecordingMuxer` is the optional MP4 sink; `upload/SftpUploader` ships
  snapshots/recordings; `net/TlsManager` backs the HTTPS/RTSPS toggle.
- **Settings** live in DataStore, exposed as a `Flow<Settings>` via `SettingsRepository`.
  Ports include `mjpegPort` / `rtspPort` / `srtPort` / `webControlPort` (user-editable,
  validated to 1024–65535 in the repo), plus a large feature surface (recording, SFTP,
  watermark, manual exposure, effects, scene modes, audio processing, presets, …).

Full breakdown: [Docs/Architecture.md](Docs/Architecture.md).

## Patterns to follow

- **Use `Edit`, not `Write`, on existing source files.** The codebase is small enough
  that surgical edits beat rewrites.
- **No comments restating what the code does.** Only comments that explain *why* — a
  hidden constraint, a subtle invariant, a workaround for a specific Android footgun.
  The existing files follow this; match it.
- **Foreground service rules are load-bearing.** If you change anything in
  `StreamingService.startForegroundWithType`, keep the manifest's `foregroundServiceType`
  in sync with the bitmask passed to `startForeground`. Mismatch crashes on API 34+.
- **Don't bind the camera from the Activity.** It must be the service. The Activity only
  pushes a `Preview.SurfaceProvider` via `setPreviewSurfaceProvider`.
- **`ImageProxy.close()` in a `finally`.** CameraX `ImageAnalysis` has a 1-image queue;
  leaking one stalls the entire pipeline.

## RTSP / SRT path caveats

- **Rotation via `streaming/GlRotator.kt`.** Both H.264 paths run the camera through an
  EGL/GL stage that rotates frames into the held orientation before the encoder, so the
  wire is upright (no receiver-side rotation needed). The rotation is
  `glRotation = (360 - deviceRotation) % 360` — `sensorOrientation` cancels because the
  SurfaceTexture transform already corrects the sensor mount, so it's device-independent.
  The encoder gets `KEY_ROTATION = 0` on this path (it sees already-upright frames).
- **High-speed RTSP (60/120/240 fps) is still landscape.** Constrained Camera2 high-speed
  sessions only accept MediaCodec/preview Surfaces, not the GL SurfaceTexture, so those
  fall back to sensor-native landscape + a `KEY_ROTATION` hint (`useGlRotation =
  !plan.highSpeed` in `RtspManager`). SRT is ≤30 fps so it always rotates.
- **Mid-stream rotation:** SRT reconfigures the video pipeline in place
  (`SrtManager.reconfigureVideo`) keeping the socket/receiver connected; RTSP still does a
  full restart on rotation. Both are driven by `StreamingService.setDeviceRotation`.
- **Audio** is optional (off by default), gated behind `RECORD_AUDIO` and the
  `microphone` foreground-service type. Toggling Audio in Settings changes which
  permissions are requested.
- **MJPEG sidecar / preview parity.** All three codec paths share one optional sidecar:
  a second `ImageReader` output on the Camera2 session whose YUV frames are JPEG-encoded
  into `FrameBroadcaster`, serving `/video` on `mjpegPort` for a browser preview *and*
  driving the on-device Compose preview while a codec owns the camera. Wired via
  `StreamingService.buildSidecarSurface` / `attachSidecarListener` / `startSidecarMjpegServer`;
  `SrtManager`/`RistManager` hold the surface and re-apply it across mid-stream rotation and
  lens switch. Rotation is computed here lens-aware (front `sensor+device`, back
  `sensor-device`) because these raw frames skip the GL stage — and the listener must be
  attached *after* the plan resolves (`_lastRtspPlan` is null until then). High-speed
  (>30 fps) sessions reject the extra output, so there's no sidecar there.

## Verification before declaring a change done

If you change camera/streaming/UI code:

1. `./gradlew :app:assembleDebug` — must succeed.
2. `adb install -r app/build/outputs/apk/debug/app-debug.apk` to the connected phone.
3. Launch the app and confirm:
   - Camera preview appears before tapping Start.
   - Switch camera works (overlay icon, top-right of preview).
   - Tapping Start makes `http://<phone-ip>:4747/video` viewable in a desktop browser.
4. For OBS-impacting changes, verify the feed in OBS Media Source as well — the browser
   check uses a single connection; OBS may stress reconnect logic differently.

You can't see what's on the phone's screen yourself. If the user reports a visual bug,
ask them to describe what they see — don't claim a fix works until they confirm.

## Useful one-liners

```bash
# Recent app logs
adb logcat -d -t 200 | grep -iE 'lenscast|cameracontroller|streamingservice|camerax'

# Check current permission state
adb shell dumpsys package guru.freberg.lenscast | grep -E 'permission|granted'

# Stop the service / app
adb shell am force-stop guru.freberg.lenscast
```

## Pointers

- [Docs/Build.md](Docs/Build.md) — JDK / SDK / build setup
- [Docs/OBS-Integration.md](Docs/OBS-Integration.md) — OBS source config + troubleshooting
- [Docs/USB.md](Docs/USB.md) — streaming over USB via `adb forward` (no Wi-Fi)
- [Docs/Webcam.md](Docs/Webcam.md) — Lenscast as a regular system webcam (Linux helper + OBS Virtual Camera)
- [Docs/API.md](Docs/API.md) — the JSON REST API (port 8088) for building control apps/plugins
- [Docs/Architecture.md](Docs/Architecture.md) — full design rationale
- [Docs/Roadmap.md](Docs/Roadmap.md) — what's planned and how to add it
- `pc/` — Linux v4l2loopback helper (`lenscast-virtualcam`); not part of the APK build
- [tools/](tools/) — dev/CI utilities; `lenscast-rtsp-probe` is a pure-Python RTSP 1.0/2.0
  conformance test client (the only way to exercise the 2.0 path, since VLC/OBS/ffmpeg are
  all 1.0)
