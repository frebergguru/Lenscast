# Lenscast architecture

## What the app does

Lenscast streams the phone's camera over the network in one of four protocols: an
**MJPEG-over-HTTP** server (CameraX captures YUV frames, encodes each to JPEG, and a
hand-rolled HTTP server replies to GET requests with a `multipart/x-mixed-replace` body),
plus four H.264 paths — **RTSP**, **SRT**, **RIST**, and **WebRTC/WHEP** — that own Camera2
directly and run frames through an EGL/GL rotation stage before a `MediaCodec` encoder.
OBS (or any HTTP/RTSP/SRT/RIST/WebRTC client) consumes the stream. A separate **web control
panel** server lets a browser start/stop streaming and edit all settings.

A long-running foreground service owns the camera so the stream keeps running when the
screen locks or the app is backgrounded.

## Module layout

Single `app/` Gradle module. Source tree:

```
app/src/main/kotlin/guru/freberg/lenscast/
├── LenscastApp.kt            # Application — creates streaming + web-control FGS channels
├── MainActivity.kt           # Compose host; binds StreamingService; PiP entry
├── camera/
│   ├── CameraController.kt   # CameraX wrapper (Preview + ImageAnalysis use cases)
│   ├── CameraCapabilities.kt # Per-lens capability queries (FPS, resolutions, ranges)
│   └── YuvToJpeg.kt          # YUV_420_888 → tight NV21 → rotated/mirrored JPEG
├── streaming/
│   ├── StreamingService.kt   # LifecycleService; FGS; camera/server orchestrator
│   ├── FrameBroadcaster.kt   # Lock-free latest-frame slot (AtomicReference)
│   ├── MjpegServer.kt        # ServerSocket; /video, /shot, /audio (PCM WAV), WebRTC/WHEP
│   ├── AudioBroadcaster.kt   # Per-client Channel fan-out for the PCM audio sidecar
│   ├── AudioUtils.kt         # Shared PCM/AAC helpers across the audio paths
│   ├── PcmCapture.kt         # AudioRecord → PCM-16LE producer with mute/gain
│   ├── GlRotator.kt          # Shared EGL/GL rotation stage for the H.264 (RTSP/SRT/RIST) paths
│   ├── HttpAcceptLoop.kt     # Shared ServerSocket accept-loop used by the HTTP servers
│   ├── RtspManager.kt        # Lifecycle wrapper over the RTSP server
│   ├── RecordingMuxer.kt     # Optional MP4 sink fed by the H.264/AAC encoders
│   ├── WebControlServer.kt   # Independent HTTP control panel on its own port
│   ├── RestApiServer.kt      # Machine JSON REST API on its own port (Bearer-token, see Docs/API.md)
│   ├── rtsp/                 # Hand-rolled RTSP: server, Camera2 driver,
│   │                         # H.264 + AAC encoders, RTP packetizers, SDP
│   ├── srt/                  # SRT: SrtManager (in-place reconfigure on rotation),
│   │                         # SrtPublisher, MpegTsMuxer
│   ├── rist/                 # RIST Simple + Main profiles (pure Kotlin): RistManager
│   │                         # (SrtManager twin), RistPublisher (RTP/MP2T + RTCP-NACK,
│   │                         # GRE v2 single-port for Main), RistCrypto (PSK AES-CTR)
│   └── webrtc/               # WebRtcManager — WebRTC playback + WHEP egress
├── net/
│   ├── NetworkUtils.kt       # Wi-Fi IPv4 lookup, port-free check
│   ├── NsdAdvertiser.kt      # mDNS / Bonjour registration for the streams
│   └── TlsManager.kt         # Software RSA + Bouncy Castle X.509 in PKCS12 (HTTPS)
├── upload/
│   └── SftpUploader.kt       # Pushes snapshots/recordings over SFTP; host-key pinning
├── system/
│   ├── BootReceiver.kt       # BOOT_COMPLETED → start stream or persistent web FGS
│   ├── StreamingTileService.kt # Quick Settings tile
│   ├── SystemWebcam.kt       # DeviceAsWebcam deep-link (Android 14+)
│   ├── TelephonyMonitor.kt   # Call-state listener (privacy / mute / drop)
│   ├── HealthMonitor.kt      # Watches stream/server health
│   └── CrashReporter.kt      # Captures uncaught exceptions
├── prefs/
│   ├── Settings.kt           # Data classes; everything user-configurable
│   ├── SettingsRepository.kt # DataStore wrapper; ports + values validated
│   ├── SettingsCodec.kt      # JSON export/import via org.json
│   └── EnumCodec.kt          # Shared enum ↔ string parsing for settings
└── ui/                       # Material 3 + Jetpack Compose
    ├── theme/                # Color, Type, Theme (dark + light)
    ├── MainScreen.kt
    ├── SettingsSheet.kt      # Collapsible SettingsGroup cards
    └── components/           # ConnectionInfoCard, PermissionGate, PreviewSurface,
                              # StatPill, QrCode
```

## Key design decisions

### 1. The service is the camera's LifecycleOwner

`StreamingService` extends `LifecycleService`. CameraX is bound to the service's
lifecycle, not the activity's. That's what lets the stream survive screen-lock and app
backgrounding — the Activity's preview Surface can detach without tearing down the
camera. The Activity is only responsible for pushing/pulling a Surface to render preview
into.

### 2. Preview-before-stream is the same path as streaming

`StreamingService.bindCameraIfNeeded(lens, resolution, jpegQuality, withAnalysis)` binds
the camera in one of two modes:

- **Preview-only** (`withAnalysis = false`) — just the Preview use case. No JPEG
  encoding, no server. Used so the user sees the lens feed before tapping Start.
- **Streaming** (`withAnalysis = true`) — adds ImageAnalysis; JPEG frames flow into
  `FrameBroadcaster`.

The UI calls the same method in both states; a key `(lens, w, h, withAnalysis)` triple
short-circuits redundant rebinds. The user can flip the lens mid-stream — the rebind
happens transparently and OBS reconnects through the brief frame gap.

### 3. Single broadcaster, lock-free latest-frame

`FrameBroadcaster` holds an `AtomicReference<ByteArray>` containing the most recent JPEG.
The CameraX analyzer writes to it on the MJPEG path; on the codec paths (RTSP/SRT/RIST)
CameraX is bypassed, so the optional MJPEG sidecar's `ImageReader` listener
(`StreamingService.makeSidecarListener`) writes to it instead. Each connected HTTP client
coroutine reads at its own FPS-throttled cadence — and the on-device Compose preview reads
the latest frame from it while a codec owns the camera. Slow clients naturally drop frames
— no per-client queues, no backpressure logic, no thread fan-out.

Frame counter (`framesProduced`) is exposed so the UI can show observed FPS.

### 4. Hand-rolled HTTP server (~150 LOC)

`MjpegServer` is a `ServerSocket` + coroutine-per-client (the accept loop is the shared
`HttpAcceptLoop`). Its routes:

| Route                     | Response                                                      |
|---------------------------|---------------------------------------------------------------|
| `/video`                  | `multipart/x-mixed-replace; boundary=lenscastframe` (the stream) |
| `/shot.jpg`               | Single JPEG snapshot                                         |
| `/`                       | A small HTML landing page that embeds `<img src="/video">`   |
| `/audio`                  | PCM-16LE WAV audio sidecar (fed by `AudioBroadcaster`)       |
| `/webrtc/...`             | WebRTC offer/answer handshake (`webRtcAnswer`)              |
| `POST`/`DELETE /whep/<id>`| WHEP egress session create / teardown                       |

NanoHTTPD would be 200 KB of dependency for what we'd write anyway. Hand-rolling is
faster and simpler to debug. Slow clients are dropped via a 5-second `soTimeout`. The
WebRTC/WHEP handshakes hand off to `streaming/webrtc/WebRtcManager`; the independent web
control panel is a separate server (`WebControlServer`) on its own port.

The **REST API** (`RestApiServer`, default port 8088) is a third such server for control
apps/plugins — JSON in/out, real status codes, Bearer-token auth. Critically it does *not*
re-implement any control logic: it delegates every action to the same `MjpegControl` bridge
the web panel uses, so all the mid-stream gates live in one place
(`StreamingService.updaterFor` + the start/protocol/import guards) and the API inherits them.
The token is CSPRNG-generated (`net/ApiToken`), sealed at rest like the other secrets
(`SecretCipher`), and the server is fail-closed — it only binds when enabled *and* a token is
set. See [API.md](API.md).

### 5. YUV → JPEG conversion path

CameraX hands the analyzer an `ImageProxy` in `YUV_420_888`. `YuvToJpeg.encode`:

1. Copies the Y plane and the interleaved VU plane into a **tight NV21 byte array**,
   respecting `rowStride` and `pixelStride`. Skipping this step lets `YuvImage` silently
   emit corrupted JPEGs on some Android 12+ devices when `rowStride ≠ width`.
2. Rotates the NV21 buffer by `imageInfo.rotationDegrees` (90, 180, 270) so OBS always
   receives an upright frame regardless of the physical phone orientation.
3. Passes the buffer to `YuvImage.compressToJpeg(...)`.

This is CPU-bound and adequate at 720p/30 fps on any 2020+ phone. If 1080p/60 ever
becomes a goal, swap in `libyuv` via JNI — the call site is small and centralized.

### 6. API 34/35 foreground-service rules

The service declares `android:foregroundServiceType="camera"` in the manifest, and
`startForeground` is called with the matching
`ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA` bitmask. Mismatch throws
`MissingForegroundServiceTypeException` on API 34+. The CAMERA runtime permission must be
granted before the FGS is promoted — the Compose UI gates the Start button behind
`rememberPermissionStatus`.

`POST_NOTIFICATIONS` (API 33+) is requested alongside CAMERA. Without it the FGS
notification is silently invisible and the service looks like it crashed.

### 7. Settings live in DataStore, mirrored through a Flow

`SettingsRepository.flow` is a hot `Flow<Settings>` collected by the Compose UI. Updates
go through `repo.update { it.copy(...) }`. The streaming service reads the current
settings at Start time and during mid-stream lens changes.

## Threading summary

| Thread / dispatcher           | What runs there                                            |
|-------------------------------|------------------------------------------------------------|
| Main                          | Compose, lifecycle callbacks, CameraX binding              |
| `analysisExecutor` (1 thread) | Per-frame YUV→JPEG encoding                                |
| `Dispatchers.IO` (server)     | ServerSocket accept loop + per-client coroutines           |
| DataStore internal            | Preference reads/writes (managed by DataStore itself)      |

## What's intentionally NOT here

- **OBS plugin.** Not needed — Media Source eats the MJPEG/RTSP stream directly.
- **Per-client frame queues** for MJPEG. The latest-frame broadcaster is enough; slow
  clients drop frames naturally.
- **libyuv / native acceleration.** Not needed at current resolutions.
- **RTSP seeking / trick-play (`Scale`, `Speed`) and `RECORD`/`ANNOUNCE`.** The source is
  a live, non-seekable, one-way feed, so the RTSP 2.0 server advertises
  `No-Seeking, Time-Progressing` and only the `play.basic` feature tag.
