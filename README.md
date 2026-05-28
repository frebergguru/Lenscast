# Lenscast

Turn an Android phone into a network camera for OBS Studio (and any other HTTP client).
Free, no watermark.

The phone runs a tiny **MJPEG-over-HTTP** server (default port 4747) and an optional
**RTSP** server (H.264 + AAC, default port 5540, landscape only). OBS connects directly
via its built-in **Media Source** — no plugin, no helper app. Wi-Fi and USB (via
`adb forward`) are both supported.

For apps that don't take a URL (Zoom, Chrome, Discord, Meet) Lenscast can also act as
a regular webcam: on Linux via the included `pc/lenscast-virtualcam` helper, or on any
platform via OBS Virtual Camera. See [Docs/Webcam.md](Docs/Webcam.md).

## Quick start

```bash
# Build (CLI only — no Android Studio needed)
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. Open Lenscast on the phone, grant camera + notifications permission.
2. Tap **Start streaming**.
3. In OBS: **Sources → + → Media Source**, uncheck "Local File", paste the URL the app
   shows you (e.g. `http://192.168.1.42:4747/video`).

Full setup, USB tethering, and troubleshooting are in
[Docs/OBS-Integration.md](Docs/OBS-Integration.md).

## Features

- Material 3 Compose UI, dynamic color on Android 12+
- Live preview before you start streaming
- Front / back camera with mid-stream lens switching
- Flashlight toggle, configurable resolution (480p / 720p / 1080p), FPS, JPEG quality
- MJPEG (any orientation) or RTSP H.264 + AAC (landscape only, up to 240 fps)
- Linux v4l2loopback helper turns the stream into a regular system webcam
- DeviceAsWebcam nudge on supported Android 14+ devices (Pixel 8+ and similar)
- Manually configurable server port per protocol (1024–65535)
- Foreground service that survives screen lock
- One tap to copy the stream URL or the `adb forward` command
- Browser-friendly landing page at `/` for quick sanity checks
- Snapshot endpoint at `/shot.jpg`

## Docs

| Doc                                                | What's in it                                                  |
|----------------------------------------------------|---------------------------------------------------------------|
| [Build.md](Docs/Build.md)                          | JDK / SDK / build environment setup on Manjaro / Arch         |
| [OBS-Integration.md](Docs/OBS-Integration.md)      | OBS Media Source config, Wi-Fi vs USB, troubleshooting        |
| [Webcam.md](Docs/Webcam.md)                        | Using Lenscast as a regular system webcam (Linux + OBS paths) |
| [Architecture.md](Docs/Architecture.md)            | Module layout, design decisions, threading model              |
| [Roadmap.md](Docs/Roadmap.md)                      | Planned and shipped features, plus what's deliberately out    |

`CLAUDE.md` at the repo root is an orientation file for AI assistants — safe to ignore
if you're a human reader.

## Status

This is a working hobby project. Tested on the developer's own Android phone, used as a
free DroidCam replacement. MJPEG works in any orientation; RTSP currently streams in the
sensor's native landscape orientation only (see
[Docs/Roadmap.md](Docs/Roadmap.md) for the GL pipeline that would lift this).

## License

[GNU GPL v3](LICENSE). Free software — if you ship a modified version, share the
modifications under the same license.
