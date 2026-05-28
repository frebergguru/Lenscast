# Roadmap

## Done

- MJPEG-over-HTTP server, Wi-Fi + USB transports
- RTSP server (H.264 + optional AAC) with Camera2 high-speed sessions for 60/120/240 fps
- CameraX preview before streaming, switchable lens mid-stream
- Foreground service that survives screen-lock
- Tap-to-copy connection URLs, settings sheet, Material 3 UI
- Per-protocol port is user-editable in the Settings sheet (1024–65535)
- **Linux virtual-camera helper** (`pc/lenscast-virtualcam`) — phone shows up as
  `/dev/videoN` for Zoom / Chrome / Discord / OBS via `v4l2loopback`. See
  [Webcam.md](Webcam.md).
- **DeviceAsWebcam nudge** — on Android 14+ devices that ship the system
  DeviceAsWebcam service (Pixel 8+, some other OEMs), the Settings sheet offers a
  deep-link into USB preferences so the user can flip the system-level "Use phone
  as webcam" toggle. The actual UVC frames come from the OS, not from Lenscast.
- **MJPEG image controls** — mirror (horizontal flip), exposure-compensation slider,
  white-balance preset (Auto / Tungsten / Fluor / Daylight / Cloudy / Shade),
  continuous-AF toggle, anti-flicker mode (Auto / 50 Hz / 60 Hz / Off). All driven
  through `Camera2Interop.Extender` on the CameraX bind so they apply to both the
  on-device preview and the streamed JPEGs.
- **Tap-to-focus and pinch-to-zoom** on the preview surface; tap shows a brief focus
  ring, auto-cancels after 3 s.
- **Snapshot overlay button** writes the latest JPEG to
  `Pictures/Lenscast/Lenscast_<timestamp>.jpg` via MediaStore.
- **HTTP Basic auth on MJPEG** (`/video`, `/shot.jpg`, `/`). Settings field for the
  passcode; fixed username `lenscast`. Receivers can embed creds in the URL
  (`http://lenscast:<pwd>@<ip>:4747/video`).
- **mDNS / NSD advertisement** — `_http._tcp.` for MJPEG and `_rtsp._tcp.` for RTSP
  registered via `NsdManager` while the server is up.
- **Quick Settings tile** — `StreamingTileService`. Tap toggles streaming using the
  last-saved settings, observes service status while the QS panel is open.
- **Auto-start on app launch** — Settings toggle. Once-per-process so rotation
  doesn't re-trigger.
- **RTSP encoder bitrate cap** — Settings slider in kbps (0 = use the
  resolution+fps heuristic).
- **Battery-saver / blank preview** — Settings toggle hides the on-screen preview
  while streaming; the analysis pipeline keeps publishing JPEGs.
- **Manual rotation lock (MJPEG)** — Settings segmented row pins the encoder rotation
  (Auto / Portrait / Landscape ← / Landscape → / Portrait ⤓) instead of following
  the accelerometer.
- **Camera effects** (`CONTROL_EFFECT_MODE`) — Mono / Negative / Sepia / Aqua /
  Solarize / Posterize / Blackboard / Whiteboard.
- **Scene modes** (`CONTROL_SCENE_MODE` + `CONTROL_MODE=USE_SCENE_MODE`) — Action /
  Portrait / Landscape / Night / Sports / Theatre / Fireworks / Beach / Snow / Sunset.
- **Manual focus** — toggle in Settings; centidiopter slider sets
  `LENS_FOCUS_DISTANCE` after switching `CONTROL_AF_MODE` to OFF.
- **Burst snapshot** — long-press the snapshot overlay button → 5 JPEGs at ~250 ms.
- **Start-on-boot** — `BootReceiver` listens for `BOOT_COMPLETED` / locked-boot;
  brings the foreground service up via the same `ACTION_START_TILE` path as the QS
  tile when `settings.startOnBoot` is set.
- **Audio pipeline polish (RTSP)**:
  - Microphone source picker (Camcorder / Default mic / Voice recognition / Voice
    communication / Unprocessed) → `MediaRecorder.AudioSource`.
  - Software gain stage in the AacEncoder captureLoop (-24..+24 dB, hard clip,
    live-updatable via a @Volatile linear factor).
  - `NoiseSuppressor` / `AcousticEchoCanceler` toggles, attached to the AudioRecord
    session id when `isAvailable()` and released in `stop()`.
  - Live VU meter — AacEncoder publishes per-buffer peak in dBFS; MainScreen draws
    a green/amber/red bar below the stat row whenever audio is on.
- **Web control page** — `/` landing in `MjpegServer` is now a control panel with
  Switch camera, Toggle torch, Mirror, Continuous AF, Zoom ±, EV ±, Snapshot and
  Stop buttons that POST to `/control/*`. A small `MjpegControl` bridge keeps the
  server decoupled from the rest of the service. A `GET /status` JSON endpoint
  feeds a 1 Hz stats line above the buttons (lens / fps / clients / zoom / EV /
  mirror / torch / audio peak).
- **Named presets** — save the current protocol+resolution+fps+lens as a labelled
  preset from the Settings sheet, apply or delete in one tap. Stored as a
  newline-delimited string in DataStore (no JSON dependency).
- **Picture-in-picture** — `MainActivity.onUserLeaveHint` auto-enters PiP when the
  user presses Home while streaming; `MainScreen` collapses to just the camera box.
- **USB transport docs** — [Docs/USB.md](USB.md) walks the user through
  `adb forward` for both ports.
- **Local recording (RTSP)** — `RecordingMuxer` taps the existing H.264 + AAC
  encoder callbacks and writes an MP4 to `Movies/Lenscast/` via MediaStore.
  Track config is captured from `H264Encoder.parameterSets` and
  `AacEncoder.asc`; tracks added once both are known; PTS rebased to start at
  zero. Finalised in `RtspManager.stop()`; UI surfaces a Toast on the
  STREAMING → IDLE transition.
- **Audio over MJPEG** — direct PCM-16LE capture via `PcmCapture` (no codec,
  no codec lookahead). `MjpegServer` serves it as `audio/wav` on `/audio` with
  an open-ended WAV header (`0xFFFFFFFF` length sentinels), so receivers treat
  it as a live stream with near-zero buffer. Fan-out is per-client via a
  `Channel`-subscribed `AudioBroadcaster`; slow clients only drop their own
  oldest samples. Permissions and foreground-service type are no longer gated
  on RTSP. AAC was tried first but receiver-side buffering and the codec
  lookahead added 300–1500 ms of audible lag; PCM/WAV is ~88 KB/s mono and
  plays in real time in `<audio>`, VLC, ffplay, and the Linux helper.
- **Web control — JPEG quality slider** — debounced `POST /control/quality?v=NN`
  endpoint; the slider in the landing page updates `CameraController.jpegQuality`
  live without rebinding the camera.
- **Persistent web control panel.** Opt-in `Settings.persistentWebControl`.
  When the user turns it on, `StreamingService` promotes itself to a
  low-importance SPECIAL_USE foreground notification so the OS can't reap
  it when the app is backgrounded. `BootReceiver` brings it up after
  reboot, `MainActivity` covers cold launches, and `stopStreaming` falls
  back into the web notification instead of leaving foreground entirely.
  Manifest gained `specialUse` + the Android-14 `<property>` explanation
  and `FOREGROUND_SERVICE_SPECIAL_USE` permission.
- **Picker overflow fix.** Enum pickers for white balance (6), effect
  (9), scene (11), mic source (5) and call behaviour (3 long labels) now
  use an `ExposedDropdownMenu` instead of the M3 segmented row that was
  squashing labels into single-character vertical columns.
- **Privacy / call-handling mode.** `Settings.callBehavior` (IGNORE /
  MUTE_STREAM / DROP_CALL). `TelephonyMonitor` watches CallState via
  `TelephonyCallback` on API 31+ and the deprecated `PhoneStateListener`
  on older. On non-IDLE: MUTE_STREAM zeros the PcmCapture / AacEncoder
  buffer before gain (true silence on the wire, VU drops to −90 dBFS);
  DROP_CALL tries `TelecomManager.endCall()` and falls back to mute if
  the API is rejected by an OEM ROM. Runtime permissions
  (READ_PHONE_STATE + ANSWER_PHONE_CALLS for drop) are requested when
  the user picks a non-IGNORE option; denial leaves the feature inert.
- **Web ↔ app live sync.** `StreamingService.onCreate` now observes the
  whole `SettingsRepository.flow` and copies it into `_status.value.settings`,
  so edits made in the phone Settings sheet flow into `/status` on the
  next 1 Hz web poll. App reads via `repo.flow.collectAsState`, so the
  reverse direction already worked. Net effect: editing on either side
  updates the other within ~1 second.
- **Web UI quick-pass.** Resolution + FPS pickers moved to the Camera
  tab of the Settings panel so they're available before Start (not only
  inside the streaming-only Quick controls card). App version surfaced
  in the page footer; passed in via a new `appVersion` lambda on
  `WebControlServer`.
- **Web control panel redesign.** Full rewrite of `WebControlServer.renderLandingHtml`
  with a modern design system (CSS variables, consistent spacing, sticky header
  with state badge). Two-column grid on desktop, single-column stack on mobile.
  Hero card adapts to idle / MJPEG-live / RTSP-live; status grid replaces the
  flat single-line stats; tabbed Settings (Camera / Image / Audio / Stream / UX /
  System) replace the old single-scroll `<details>`. Toast replaces the inline
  message strip. Fixed two regressions during the rewrite: the live preview
  `<img>` is now (re-)attached on the idle → MJPEG-live transition with a
  cache-busting URL so browsers don't reuse a stale failed connection from
  before the MjpegServer existed, and the Quick controls / Status cards hide
  themselves entirely while idle.
- **App SettingsSheet — collapsible groups.** New `SettingsGroup` composable
  wraps each section in a Material 3 Card with an animated chevron and
  `rememberSaveable`-backed expand state. Hoisted `val context = LocalContext.current`
  to the top so every group's lambda can close over it. Replaces the wall-of-text
  flat scroll with 8 grouped cards (Streaming basics + Image expanded by default;
  Stream output & audio, Security & access, Automation, Web control panel,
  Presets, System webcam, Backup collapsed).
- **Light-theme surface tones.** The light scheme now overrides
  `surfaceContainer{,High,Highest}`, `surfaceVariant`, `onSurfaceVariant`,
  `primaryContainer`, `onPrimaryContainer` and `outline` with violet-tinted whites
  so the connection card and URL row stack have proper layered contrast in light
  mode. Six new colour constants in `theme/Color.kt`.
- **Settings export / import.** `SettingsCodec` (hand-rolled with `org.json`,
  versioned, tolerant of missing fields) gives JSON in/out for the whole
  `Settings` blob. Phone Settings sheet has Export/Import buttons via SAF
  (`ActivityResultContracts.CreateDocument` / `OpenDocument`); web page has a
  download link plus a paste-in textarea backed by `GET /export` and
  `POST /import`. `WebControlServer.handle` now reads the request body when
  `Content-Length` is present so the import endpoint receives the JSON.
- **Linux helper: GTK GUI.** `pc/lenscast-virtualcam-gui` — single-file Python/GTK3
  wrapper around the bash helper. URL field with mDNS discovery (avahi-browse),
  loopback dropdown auto-detected from `v4l2-ctl`, audio + insecure toggles, a
  Start/Stop button with a live log pane. Settings persist to
  `~/.config/lenscast/gui.json`. GTK3 (not GTK4) for the widest desktop
  compatibility; no tray icon since AppIndicator is unreliable across modern
  GNOME/KDE/Wayland.
- **Linux helper: audio + HTTPS.** `lenscast-virtualcam -a` creates a PulseAudio
  null sink and forwards `/audio` (PCM-16LE WAV) into it via a parallel ffmpeg
  with the same auto-reconnect loop as video; downstream apps pick
  "Monitor of Lenscast" as a mic. `--insecure` flag passes `-tls_verify 0`
  for self-signed HTTPS URLs from Lenscast's built-in TLS toggle. README
  documents both flags.
- **HTTPS for MJPEG + web control** — `TlsManager` synthesises a self-signed
  RSA-2048 cert via `AndroidKeyStore` (`KeyGenParameterSpec.setCertificateSubject`
  auto-generates the X.509 wrapper around the key pair — no Bouncy Castle).
  `MjpegServer` / `WebControlServer` accept an optional `SSLContext`; when
  present, the accept loop calls `sslContext.serverSocketFactory.createServerSocket`.
  Self-signed warning on first visit is unavoidable; SHA-256 fingerprint is
  surfaced in both the Settings sheet and the web page so the user can
  verify the cert. RTSPS deliberately skipped — OBS doesn't support it.
- **Manual exposure (ISO + shutter)** — single toggle in Settings, plus
  range-clamped sliders sourced from `SENSOR_INFO_SENSITIVITY_RANGE` /
  `SENSOR_INFO_EXPOSURE_TIME_RANGE` via new helpers in
  `CameraCapabilities`. `CameraController.applyCommonControls` flips
  `CONTROL_AE_MODE` to OFF and writes `SENSOR_SENSITIVITY` +
  `SENSOR_EXPOSURE_TIME` when enabled. Both the phone Settings sheet and the
  web control panel gate the controls on the lens's MANUAL_SENSOR capability,
  so the user never sees knobs that can't actually do anything.
- **Independent web control panel (`WebControlServer`)** — separate HTTP server,
  bound to its own port (default 8080, toggleable + portable via Settings). Runs
  the entire time `StreamingService` is alive, so the panel is reachable in
  idle, MJPEG-streaming, and RTSP-streaming states. `/control/start` triggers
  the same code path the QS tile uses; `/control/protocol` lets the user pick
  MJPEG vs RTSP from the idle screen; `/control/setting?key=...&v=...` is a
  generic Settings updater dispatched through a single `updateSetting()` on
  `MjpegControl`. `/status` reports state + protocol + every Settings field
  the panel needs; a 1 Hz poll keeps the page mirrored, skipping any control
  the user is currently editing so typing isn't clobbered. The page is
  single-HTML, JS-driven: idle / live blocks, MJPEG-only vs RTSP-only
  sections, and resolution / FPS / WB / effect / scene / antiBanding /
  rotation lock pickers are all shown/hidden from the same `/status` poll.

## Known architectural cap: MJPEG ≤ 30 fps

The current MJPEG path tops out at whatever the device's **standard** Camera2 preview
session supports. On most modern Android phones (including the dev phone) the OEM caps
that at 30 fps even when the sensor records at 120 or 240 fps.

We investigated the obvious workaround — Camera2's `createConstrainedHighSpeedCaptureSession`
— and hit a hard wall: high-speed sessions only accept *preview-class* output surfaces
(SurfaceView / TextureView with an opaque format) or a MediaCodec H.264 encoder input
surface. They **explicitly reject** `ImageReader` outputs in `YUV_420_888`, which is
exactly the format the JPEG-encoding broadcaster needs. The check is
`SurfaceUtils.checkHighSpeedSurfaceFormat` in AOSP and the rejection is by design — high
speed is meant for video recording, not frame-by-frame CPU work.

The HighSpeedCameraSession class was implemented end-to-end, including device-agnostic
config negotiation. The wall is at the Android API contract, not in our integration.

There are two ways past it, both significant:

1. **Run a high-speed → H.264 → MJPEG round-trip.** A MediaCodec encoder consumes the
   high-speed Surface, the encoded H.264 NALs are fed to a decoder, and the decoded YUV
   frames are JPEG-encoded for the broadcaster. Expensive in CPU and latency; defeats
   most of the point.
2. **Skip MJPEG entirely above 30 fps.** Stream H.264 directly via RTSP. This is what
   the Roadmap's RTSP section is about — and it's the natural home for higher frame
   rates.

For now, the FPS picker exposes 15 / 24 / 30 only. Higher rates will arrive with RTSP.

## Known limitation: RTSP stream is locked to sensor orientation (landscape)

The Settings sheet surfaces this directly under the Protocol picker — selecting RTSP
shows a note that only landscape orientation is supported, so users know to rotate the
phone before tapping Start.

The MJPEG path rotates each frame per `imageProxy.imageInfo.rotationDegrees` in
[`YuvToJpeg`](../app/src/main/kotlin/dev/lenscast/camera/YuvToJpeg.kt), so MJPEG output
is always upright regardless of how the phone is held.

The RTSP path doesn't, because the H.264 encoder is fed by the camera's Surface
directly with no opportunity to apply per-frame rotation. We currently set
`MediaFormat.KEY_ROTATION` as metadata, but this only embeds the rotation in the H.264
bitstream's Display Orientation SEI — most live-stream decoders ignore SEI and render
the bitstream as-is. So RTSP comes out in the sensor's native orientation (landscape).

The proper fix is an **EGL/GL pipeline**: camera writes into a `SurfaceTexture`, a small
fragment-shader pass rotates the texture by `(sensorOrientation - deviceRotation)` and
draws into the encoder's input Surface. Adds ~200 LOC and one EGL context. Roughly:

```
Camera2 ─→ SurfaceTexture ─→ glDraw(rotation matrix) ─→ MediaCodec input Surface
```

Workaround until that lands: rotate in the receiver. In OBS, right-click the
Media Source → Transform → Rotate 90° CW (or use a filter). Lossless for the user.

## Planned

### Native UVC gadget from inside Lenscast — not feasible from a third-party app

The dream version of "phone as webcam" is the phone presenting itself to the PC as
a UVC (USB Video Class) device with Lenscast supplying the frames. This is **not
reachable from a regular app**, even on Android 14+:

- The system DeviceAsWebcam service exists on Pixel 8+ and some OEMs, but
  `IDeviceAsWebcam` is `@SystemApi` and only callable by platform-signed apps.
  The frames the host PC sees come from the OS's own camera service, not from
  Lenscast.
- Bypassing the system service means writing to
  `/sys/kernel/config/usb_gadget/`, which requires root and a kernel built with
  the UVC gadget driver. Only a sliver of devices cooperate.

Two pragmatic alternatives that **are** shipped:

- **Linux PC-side helper** ([Webcam.md](Webcam.md)) — `pc/lenscast-virtualcam`
  bridges the MJPEG stream into a `v4l2loopback` device.
- **DeviceAsWebcam nudge** — on devices that ship the system service, the
  Settings sheet deep-links into USB preferences so the user can enable it.

A real Windows / macOS virtual-camera driver (signed DirectShow filter or macOS
System Extension) would extend the PC-side approach to those platforms but
brings a code-signing/notarization yak-shave that's deliberately out of scope.

### RTSP portrait support

The RTSP server itself is shipped (hand-rolled on top of `MediaCodec`, see
`streaming/rtsp/`). What's missing is an EGL/GL rotation pass between the camera and the
H.264 encoder so the stream isn't pinned to the sensor's landscape orientation. Sketch
above in *Known limitation: RTSP stream is locked to sensor orientation*. Until that
lands, the Settings sheet shows users a note that RTSP is landscape-only.

### Minor polish

(Tap-to-focus, pinch-to-zoom, snapshot button, encoder bitrate cap, the QS tile and
the light-theme surface tones all landed — see *Done* above.)
