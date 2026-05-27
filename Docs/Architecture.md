# Lenscast architecture

## What the app does

Lenscast runs an MJPEG-over-HTTP server on the phone. CameraX captures YUV frames,
encodes each to JPEG, and a hand-rolled HTTP server replies to GET requests with a
`multipart/x-mixed-replace` body. OBS (or any HTTP client) consumes the stream.

A long-running foreground service owns the camera so the stream keeps running when the
screen locks or the app is backgrounded.

## Module layout

Single `app/` Gradle module. Source tree:

```
app/src/main/kotlin/dev/lenscast/
├── LenscastApp.kt            # Application — creates the FGS notification channel
├── MainActivity.kt           # Compose host; binds StreamingService
├── camera/
│   ├── CameraController.kt   # CameraX wrapper (Preview + ImageAnalysis use cases)
│   └── YuvToJpeg.kt          # YUV_420_888 → tight NV21 → rotated JPEG
├── streaming/
│   ├── StreamingService.kt   # LifecycleService, foreground service, camera owner
│   ├── FrameBroadcaster.kt   # Lock-free latest-frame slot (AtomicReference)
│   ├── MjpegServer.kt        # ServerSocket + multipart writer
│   ├── RtspManager.kt        # Lifecycle wrapper over the RTSP server
│   └── rtsp/                 # Hand-rolled RTSP: server, Camera2 driver,
│                             # H.264 + AAC encoders, RTP packetizers, SDP, GL rotation
├── prefs/
│   ├── Settings.kt           # Data classes (Lens, Resolution, Fps, mjpegPort, rtspPort, …)
│   └── SettingsRepository.kt # DataStore wrapper; ports validated to 1024–65535
├── net/
│   └── NetworkUtils.kt       # Wi-Fi IPv4 lookup, port-free check
└── ui/                       # Material 3 + Jetpack Compose
    ├── theme/                # Color, Type, Theme
    ├── MainScreen.kt
    ├── SettingsSheet.kt
    └── components/           # ConnectionInfoCard, PermissionGate, PreviewSurface, StatPill
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
The CameraX analyzer writes to it. Each connected HTTP client coroutine reads at its own
FPS-throttled cadence. Slow clients naturally drop frames — no per-client queues, no
backpressure logic, no thread fan-out.

Frame counter (`framesProduced`) is exposed so the UI can show observed FPS.

### 4. Hand-rolled HTTP server (~150 LOC)

`MjpegServer` is a `ServerSocket` + coroutine-per-client. It handles three routes:

| Route        | Response                                                      |
|--------------|---------------------------------------------------------------|
| `/video`     | `multipart/x-mixed-replace; boundary=lenscastframe` (the stream) |
| `/shot.jpg`  | Single JPEG snapshot                                          |
| `/`          | A small HTML landing page that embeds `<img src="/video">`    |

NanoHTTPD would be 200 KB of dependency for what we'd write anyway. Hand-rolling is
faster and simpler to debug. Slow clients are dropped via a 5-second `soTimeout`.

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
- **Multi-client RTSP.** Single client at a time; a new connect closes the old session.
- **UDP RTCP receiver reports** (the server sends SR but doesn't read RR back).
