# Lenscast — Feature list

Everything Lenscast can do today, grouped by area. For *planned* features see
[Roadmap.md](Roadmap.md) and [TODO.md](TODO.md).

## Streaming protocols

- **MJPEG over HTTP** (default port 4747) — works in any orientation, up to 30 fps. Endpoints:
  `/video` (multipart stream), `/shot.jpg` (single frame), `/` (landing page), `/audio`
  (PCM-WAV sidecar when audio is on).
- **RTSP** (default port 5540) — H.264 + AAC. Version-negotiated per request: **RTSP 1.0**
  (RFC 2326) for OBS/VLC/ffmpeg and **RTSP 2.0** (RFC 7826). TCP-interleaved and UDP
  transports, multi-client fan-out, reads RTCP RR back for adaptive bitrate.
- **SRT** (default port 9710) — H.264 + AAC over MPEG-TS. **Caller** and **Listener** modes,
  optional AES encryption (passphrase), configurable latency window and stream-id.
- **RIST** (default data port 5004) — pure-Kotlin (no librist/JNI). VSF **Simple** (RTP/MP2T
  + RTCP-NACK retransmit) and **Main** (GRE v2, single UDP port, PSK **AES-CTR** 128/256)
  profiles. **Caller** and **Listener** modes, configurable buffer window.
- **WebRTC** — browser playback at `/webrtc/view` plus a standard **WHEP** endpoint
  (`POST`/`DELETE /whep/<id>`), served off the web-control port. Sub-second latency.

### Orientation

- **Orientation-correct H.264** (RTSP/SRT/RIST ≤30 fps) via a shared EGL/GL rotation stage —
  the wire is upright with no receiver-side rotation.
- **Seamless mid-stream rotation** on SRT/RIST (the receiver stays connected); RTSP restarts.
- **Manual rotation lock** — Auto / Portrait / Landscape-left / Landscape-right / Portrait-
  upside-down, overriding the accelerometer.
- High-speed RTSP (60/120/240 fps) ships the sensor-native landscape (constrained Camera2
  limitation).

### Preview parity

- **MJPEG sidecar** — the codec paths (RTSP/SRT/RIST, ≤30 fps) can also serve the MJPEG
  `/video` endpoint and drive the on-device preview off the same camera (on by default).

## Camera controls

- **Lens switch** — front / back, mid-stream where supported.
- **Resolution** — 480p / 720p / 1080p / 1440p / 4K (clamped to device capabilities).
- **Frame rate** — 15 / 24 / 30 / 60 / 120 / 240 fps (high rates on the RTSP high-speed path).
- **JPEG quality** — 10–95 (MJPEG / sidecar).
- **Zoom** — pinch-to-zoom and programmatic (relative).
- **Tap-to-focus** — with an on-screen focus ring.
- **Exposure compensation** (EV).
- **Manual exposure** — ISO and shutter-speed locks (devices with `MANUAL_SENSOR`).
- **White balance** — Auto / Incandescent / Fluorescent / Daylight / Cloudy / Shade.
- **Anti-banding** — Auto / 50 Hz / 60 Hz / Off.
- **Manual focus** — focus-distance slider (0 = infinity).
- **Continuous autofocus** toggle.
- **Scene modes** — Action / Portrait / Landscape / Night / Sports / Theatre / Fireworks /
  Beach / Snow / Sunset.
- **Effects** — Mono / Negative / Sepia / Aqua / Solarize / Posterize / Blackboard /
  Whiteboard.
- **Mirror / horizontal flip.**
- **Torch / flashlight** toggle.
- **Text watermark** — burned into every frame, with a `%t` self-updating `HH:mm:ss` token.

## Audio

- Optional (off by default), gated behind `RECORD_AUDIO`.
- **Microphone source picker** — Camcorder / Default / Voice-recognition / Voice-comm /
  Unprocessed.
- **Software gain** (−24…+24 dB), **noise suppression** and **echo cancellation** toggles.
- **Live audio level meter** (peak dBFS) on the home screen.
- **Audio over MJPEG** — a low-latency PCM-WAV `/audio` sidecar so the MJPEG path isn't silent.

## Capture & recording

- **Snapshot to gallery** — single shot, plus **burst** (long-press, 5 shots).
- **Local MP4 recording** (RTSP/SRT/RIST) — taps the live H.264 + AAC encoders into a
  `MediaMuxer`; finalised when the stream stops.
- **SFTP auto-upload** of finished recordings — host, port, user, password, remote dir, and
  optional **host-key fingerprint pinning** (TOFU otherwise); retries with backoff.

## Control surfaces

- **Web control panel** (default port 8080) — full settings parity, start/stop, live status,
  quick controls, presets, a LAN speed-test, drop-a-client, settings export/import — from any
  browser. Localized.
- **REST API** (default port 8088, opt-in) — JSON HTTP API for third-party apps/plugins:
  start/stop, all camera quick-controls, the full settings surface, presets and SFTP, with
  real status codes and **bearer-token** auth. See [API.md](API.md).
- **Quick-settings tile** — toggle streaming from the notification shade.
- **In-app quick controls** — overlay controls on the preview while streaming.
- **Named presets** — save/recall protocol + resolution + fps + lens in one tap.
- **Settings backup** — export/import the whole config as JSON (on-device and over the web
  panel; credentials redacted from the network export).

## Security

- **HTTP Basic auth** on the MJPEG endpoints and web panel (optional stream password).
- **HTTPS / RTSPS** — self-signed RSA-2048 cert (AndroidKeyStore), SHA-256 fingerprint shown
  in-app and on the panel for verification.
- **REST API bearer token** — 256-bit, CSPRNG-generated, **sealed at rest** (AndroidKeyStore
  AES-GCM), constant-time compared, never echoed over the network. The API is **fail-closed**
  (binds only when enabled with a token).
- **Secrets encrypted at rest** — stream/SFTP passwords and SRT/RIST passphrases are sealed in
  DataStore with the same hardware-backed key.

## Networking & connectivity

- **User-editable ports** for MJPEG / RTSP / SRT / RIST / web-control / REST API
  (validated 1024–65535).
- **mDNS / Bonjour advertisement** — `_http._tcp.` (MJPEG) and `_rtsp._tcp.` (RTSP) while
  running, for zero-config discovery.
- **USB streaming** via `adb forward` — no Wi-Fi required (see [USB.md](USB.md)).
- **System webcam** — a DeviceAsWebcam nudge for Android 14+ devices, plus a Linux
  v4l2loopback helper (`pc/lenscast-virtualcam`) for "phone as a regular webcam" in
  Zoom/Chrome/Discord (see [Webcam.md](Webcam.md)).

## Background, power & system integration

- **Foreground service** — streaming survives screen-lock and backgrounding.
- **Background reachability (on by default)** — the web panel and REST API keep serving **with
  the screen off**: a persistent foreground notification plus a partial wake lock and a
  high-perf Wi-Fi lock keep the CPU and radio awake so LAN connections are still answered when
  the phone is idle. Without these the screen-off Wi-Fi power-save parks the radio and the
  panel goes silent. Toggle it off ("keep web panel reachable") if you only use the panel with
  the app open.
- **Keep screen on while streaming** (optional).
- **Battery saver / blank preview** — hide the on-screen preview while streaming.
- **Auto-start on app launch** and **start-on-boot** (`BOOT_COMPLETED`).
- **Picture-in-picture** — collapses to the preview when you press Home while streaming.
- **Call handling** — when a phone call arrives mid-stream: Ignore / Mute the stream /
  Auto-reject the call.

## Localization

- In-app UI and web panel in **English** and **Norwegian Bokmål**, with an in-app language
  picker (overrides the system locale).

---

*This list reflects the shipped app. See [Architecture.md](Architecture.md) for how each piece
is built and [CLAUDE.md](../CLAUDE.md) for the orientation overview.*
