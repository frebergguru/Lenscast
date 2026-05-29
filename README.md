# Lenscast

Turn an Android phone into a network camera for OBS Studio (and any other HTTP client).
Free, no watermark.

The phone runs a tiny **MJPEG-over-HTTP** server (default port 4747) and four H.264
options: **RTSP** (H.264 + AAC, default port 5540), **SRT** (H.264 + AAC over MPEG-TS,
default port 9710), **RIST** (Simple + Main profiles, H.264 + AAC over MPEG-TS, default
data port 5004), and **WebRTC** for browser playback (served off the web-control port, with a WHEP
endpoint for WHEP-aware players). RTSP, SRT and RIST are orientation-correct
(portrait/landscape) at ≤30 fps; SRT and RIST also rotate seamlessly mid-stream. OBS connects
directly via its built-in **Media Source** — no plugin, no helper app. Wi-Fi and USB (via
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
2. Tap **Start streaming** — or open `http://<phone-ip>:8080/` from any browser on the
   LAN and use the web control panel.
3. In OBS: **Sources → + → Media Source**, uncheck "Local File", paste the URL the app
   shows you (e.g. `http://192.168.1.42:4747/video`).

Full setup, USB tethering, and troubleshooting are in
[Docs/OBS-Integration.md](Docs/OBS-Integration.md).

## Features

**Streaming**

- **MJPEG-over-HTTP** (default 4747, any orientation, up to 30 fps) and **RTSP** (H.264 +
  AAC, default 5540, up to 240 fps via Camera2 high-speed sessions; orientation-correct at
  ≤30 fps, sensor-native landscape for high-speed)
- **SRT** (H.264 + AAC over MPEG-TS, default 9710) — listener or caller mode, optional AES
  passphrase, tunable latency; orientation-correct and rotates seamlessly mid-stream
- **RIST** (H.264 + AAC over MPEG-TS, default data port 5004) — vendor-neutral, pure-Kotlin
  (no librist). **Simple Profile**: RTP/MP2T + RTCP-NACK retransmit (RTCP on port +1).
  **Main Profile**: GRE over a single UDP port with optional PSK **AES-128/256** encryption.
  Listener or caller mode, tunable buffer window; shares the SRT pipeline so it
  rotates/switches lens mid-stream. (NULL-packet suppression and DTLS aren't implemented.)
- **WebRTC** browser playback at `/webrtc/view`, plus a **WHEP** endpoint
  (`POST`/`DELETE /whep/<id>`) for WHEP-aware players — both served off the web-control port
- **Audio sidecar** on the network transports — RTSP and SRT carry audio in-session; MJPEG
  exposes a PCM-16LE WAV stream at `/audio` with near-zero receiver-side buffering
- **Local recording** — RTSP-path toggle that mirrors the live H.264/AAC encoders to an
  MP4 in `Movies/Lenscast/` while you stream
- **SFTP upload** — push snapshots / recordings to a server, with host-key fingerprint
  pinning
- **Watermark** — optional text overlay burned into the streamed frames
- **HTTPS toggle** — self-signed RSA-2048 cert generated with Bouncy Castle and
  persisted as PKCS12 in app-private storage; SHA-256 fingerprint shown in the app
  and on the web panel
- **HTTP Basic auth** passcode for the MJPEG endpoints (`/video`, `/shot.jpg`, `/audio`)
- **mDNS / NSD advertisement** — OBS, VLC and Bonjour-aware tools discover the phone
  without typing an IP
- **Foreground service** that survives screen lock; **Quick Settings tile** to toggle
  streaming from the notification shade; **auto-start on launch** + **start-on-boot**
  options

**Web control panel** (`http://<phone-ip>:8080/`)

- Independent HTTP server, configurable port, runs whenever the app is alive
- Pick the protocol (MJPEG / RTSP / SRT / RIST / WebRTC) and **Start streaming** straight from a
  browser
- Full Settings parity with the app — camera / image / audio / stream / UX / automation
  / server ports / security
- Live preview embedded for MJPEG; RTSP URL hint for the OBS-side workflow
- `GET /export` + `POST /import` for backing settings up between devices

**Camera controls**

- Front / back lens with mid-stream switching, **pinch-to-zoom**, **tap-to-focus**,
  **mirror** flip, **continuous-AF** toggle, **manual focus** with diopter slider
- **Exposure compensation**, **white balance**, **anti-flicker**, **camera effects**
  (mono / sepia / negative / aqua / solarize / posterize / blackboard / whiteboard),
  **scene modes** (action / portrait / night / sports / fireworks / beach / snow /
  sunset…)
- **Manual exposure** — ISO + shutter sliders clamped to the lens's reported ranges
  (hidden on lenses without the MANUAL_SENSOR capability)
- **Snapshot** to `Pictures/Lenscast/` via overlay button (long-press for a 5-shot burst)
- **Rotation lock** — Auto / Portrait / Landscape ← / Landscape → / Portrait ⤓
- **Picture-in-picture** when you press Home while streaming
- **Battery-saver** blank-preview mode

**Audio polish (RTSP / SRT path)**

- Microphone source picker (Camcorder / Default / Voice recog / Voice comm / Unprocessed)
- Software gain stage (-24 to +24 dB), noise suppression and echo-cancel toggles, live
  VU meter

**Webcam paths**

- Linux **v4l2loopback** helper (`pc/lenscast-virtualcam`) with audio forwarding,
  reconnect, HTTPS support, and a `--doctor` mode that sanity-checks the system
- **DeviceAsWebcam** deep-link on Android 14+ devices that ship the system service

**UI**

- Material 3 Compose UI with dynamic colour on Android 12+
- Live preview before streaming, on-camera overlay for the common controls, a stats
  row with FPS / clients / audio peak while streaming
- Tap-to-copy stream URLs, USB-tethering URL, `adb forward` command, and the audio /
  web-control / RTSP URLs as they apply

## Docs

| Doc                                                | What's in it                                                  |
|----------------------------------------------------|---------------------------------------------------------------|
| [Build.md](Docs/Build.md)                          | JDK / SDK / build environment setup on Manjaro / Arch         |
| [OBS-Integration.md](Docs/OBS-Integration.md)      | OBS Media Source config, Wi-Fi vs USB, troubleshooting        |
| [Webcam.md](Docs/Webcam.md)                        | Using Lenscast as a regular system webcam (Linux + OBS paths) |
| [USB.md](Docs/USB.md)                              | Streaming over USB via `adb forward` (no Wi-Fi needed)        |
| [Architecture.md](Docs/Architecture.md)            | Module layout, design decisions, threading model              |
| [Roadmap.md](Docs/Roadmap.md)                      | Planned and shipped features, plus what's deliberately out    |

`CLAUDE.md` at the repo root is an orientation file for AI assistants — safe to ignore
if you're a human reader.

## Status

This is a working hobby project. Tested on the developer's own Android phone, used as a
free DroidCam replacement. MJPEG works in any orientation; RTSP and SRT are
orientation-correct at ≤30 fps via an EGL/GL rotation stage (SRT also rotates mid-stream).
The one landscape-only case left is high-speed RTSP (60/120/240 fps) — see
[Docs/Roadmap.md](Docs/Roadmap.md).

## License

[GNU GPL v3](LICENSE). Free software — if you ship a modified version, share the
modifications under the same license.
