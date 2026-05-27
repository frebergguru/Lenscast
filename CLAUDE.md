# Lenscast — Claude orientation

Lenscast turns an Android phone into a network camera that OBS Studio (and any HTTP
client) can consume directly. Today: MJPEG over HTTP. Planned: USB UVC webcam mode and
RTSP. The project deliberately has **no PC-side software** — OBS connects via its
built-in Media Source.

This was renamed from "OBSCam" in May 2026 (the original name was already taken by
another project, and Lenscast covers the future non-OBS use cases too).

## How to build and run

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p dev.lenscast.debug -c android.intent.category.LAUNCHER 1
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
  JPEG. Lock-free, no per-client queues.
- **`MjpegServer`** is a hand-rolled `ServerSocket` + coroutine-per-client. Serves
  `/video`, `/shot.jpg`, and `/` (browser-friendly landing page).
- **Settings** live in DataStore, exposed as a `Flow<Settings>` via `SettingsRepository`.

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

## Things that are intentionally cut

- **RTSP** and the `RtspManager.kt` file. The `Protocol.RTSP` enum entry still exists and
  the service's `startStreaming` throws "RTSP path is not wired up" if anyone selects it.
  See [Docs/Roadmap.md](Docs/Roadmap.md) for re-enabling steps.
- **Microphone audio.** Belongs to the RTSP path. Permissions and FGS-microphone-type
  declarations were removed from the manifest.

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
adb shell dumpsys package dev.lenscast.debug | grep -E 'permission|granted'

# Stop the service / app
adb shell am force-stop dev.lenscast.debug
```

## Pointers

- [Docs/Build.md](Docs/Build.md) — JDK / SDK / build setup
- [Docs/OBS-Integration.md](Docs/OBS-Integration.md) — OBS source config + troubleshooting
- [Docs/Architecture.md](Docs/Architecture.md) — full design rationale
- [Docs/Roadmap.md](Docs/Roadmap.md) — what's planned and how to add it
