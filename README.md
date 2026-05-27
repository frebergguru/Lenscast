# Lenscast

Turn an Android phone into a network camera for OBS Studio (and any other HTTP client).
Free, no watermark, no PC-side software.

The phone runs a tiny **MJPEG-over-HTTP** server. OBS connects to it directly via its
built-in **Media Source** — no plugin, no helper app. Wi-Fi and USB (via `adb reverse`)
are both supported.

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
- Foreground service that survives screen lock
- One tap to copy the stream URL or the `adb reverse` command
- Browser-friendly landing page at `/` for quick sanity checks
- Snapshot endpoint at `/shot.jpg`

## Docs

| Doc                                                | What's in it                                                  |
|----------------------------------------------------|---------------------------------------------------------------|
| [Build.md](Docs/Build.md)                          | JDK / SDK / build environment setup on Manjaro / Arch         |
| [OBS-Integration.md](Docs/OBS-Integration.md)      | OBS Media Source config, Wi-Fi vs USB, troubleshooting        |
| [Architecture.md](Docs/Architecture.md)            | Module layout, design decisions, threading model              |
| [Roadmap.md](Docs/Roadmap.md)                      | Planned: USB UVC webcam mode, RTSP, tap-to-focus, zoom        |

`CLAUDE.md` at the repo root is an orientation file for AI assistants — safe to ignore
if you're a human reader.

## Status

This is a working hobby project. Tested on the developer's own Android phone, used as a
free DroidCam replacement. RTSP support is scaffolded but currently disabled — the
streaming protocol exposed today is MJPEG only. See [Docs/Roadmap.md](Docs/Roadmap.md).

## License

[GNU GPL v3](LICENSE). Free software — if you ship a modified version, share the
modifications under the same license.
