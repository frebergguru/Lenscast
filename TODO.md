# TODO — Droidcam Pro feature gap

Features that Droidcam / Droidcam Pro (Dev47Apps) ships and Lenscast does not. Grouped
by area. Items already tracked in [Docs/Roadmap.md](Docs/Roadmap.md) are linked rather
than re-described — they belong on this list for completeness but the design notes live
in the roadmap.

## Manual camera controls

Droidcam Pro exposes these on a per-stream basis. CameraX `CameraControl` /
`Camera2 CaptureRequest` covers most of them.

- [x] **Exposure compensation slider** — `CameraControl.setExposureCompensationIndex`,
      clamped to `CameraInfo.exposureState.exposureCompensationRange`. Settings sheet,
      MJPEG path.
- [x] **ISO / sensor sensitivity lock** + **Manual shutter speed** — single
      Settings toggle (`manualExposure`) plus ISO and shutter sliders, both
      clamped to the lens's `SENSOR_INFO_SENSITIVITY_RANGE` /
      `SENSOR_INFO_EXPOSURE_TIME_RANGE`. Wired via Camera2Interop in
      `CameraController.applyCommonControls`: when on, `CONTROL_AE_MODE` goes
      to OFF and `SENSOR_SENSITIVITY` / `SENSOR_EXPOSURE_TIME` take effect.
      Phone Settings sheet hides the controls on lenses without the
      `MANUAL_SENSOR` capability; web control panel mirrors the same logic
      from `/status.supportsManualSensor` + `isoRange` / `shutterRangeUs`.
- [x] **White-balance presets** — `CONTROL_AWB_MODE` via Camera2Interop. Settings
      sheet (Auto / Tungsten / Fluor / Daylight / Cloudy / Shade). MJPEG path.
- [x] **Manual focus distance** — Settings toggle plus a centidiopter slider
      (0 = infinity). When enabled, `CONTROL_AF_MODE` switches to OFF and
      `LENS_FOCUS_DISTANCE` (diopters) is set via Camera2Interop. MJPEG path.
- [x] **Continuous-AF toggle** — `CONTROL_AF_MODE_CONTINUOUS_VIDEO` vs `_AUTO`.
      Settings sheet. MJPEG path.
- [x] **Scene modes** — `CONTROL_SCENE_MODE` (Off / Action / Portrait / Landscape /
      Night / Sports / Theatre / Fireworks / Beach / Snow / Sunset). Camera2 also
      needs `CONTROL_MODE = USE_SCENE_MODE`. Settings sheet. MJPEG path.
- [x] **Anti-banding** — `CONTROL_AE_ANTIBANDING_MODE` (Auto / 50 Hz / 60 Hz / Off).
      Settings sheet. MJPEG path.
- [x] **Effects / filters** — `CONTROL_EFFECT_MODE` (None / Mono / Negative /
      Sepia / Aqua / Solarize / Posterize / Blackboard / Whiteboard). Wired via
      Camera2Interop so it applies to both preview and the streamed JPEGs without
      a separate YUV-domain pass. MJPEG path.
- [x] **Mirror / horizontal flip** — in-place NV21 row reverse inside `YuvToJpeg`,
      gated by a Settings toggle. MJPEG output.
- [x] **Manual rotation lock** — Settings segmented row (Auto / Portrait /
      Landscape ← / Landscape → / Portrait ⤓). When non-Auto, MainScreen overrides
      the accelerometer-driven rotation passed to `setDeviceRotation`. MJPEG path.
- [x] **Tap-to-focus** — `FocusMeteringAction` with auto-cancel after 3 s;
      preview shows a brief focus ring.
- [x] **Pinch-to-zoom** — Compose `detectTransformGestures` →
      `CameraControl.setZoomRatio`, clamped to the lens's reported range.

## Connectivity & transports

- [x] **USB transport over ADB forward** — documented in
      [Docs/USB.md](Docs/USB.md) (one-shot `adb forward` for both ports,
      OBS/VLC URL forms, custom-port mapping, comparison vs Wi-Fi). The Linux
      helper auto-mode is still open.
- [x] **Stream password / basic auth** — HTTP Basic auth on the MJPEG endpoints
      (`/video`, `/shot.jpg`, `/`). Settings field for the passcode; username fixed
      to `lenscast`. RTSP DIGEST is a separate follow-up.
- [x] **HTTPS (MJPEG + web control)** — Settings toggle. Self-signed RSA-2048
      cert auto-generated via `AndroidKeyStore` the first time it's enabled
      (no Bouncy Castle dep needed — Android synthesises the X.509 wrapper
      when you create a key pair with a `setCertificateSubject(...)` spec).
      `MjpegServer` and `WebControlServer` swap their accept-loop to use
      `SSLContext.serverSocketFactory` when a context is provided.
      Fingerprint surfaces in both the Settings sheet and the web control
      page so the user can verify the cert hasn't been MITM-ed. RTSPS is
      still open — most RTSP clients (OBS in particular) don't speak it.
- [x] **Bonjour / mDNS advertisement** — `NsdAdvertiser` broadcasts `_http._tcp.`
      for MJPEG and `_rtsp._tcp.` for RTSP while the server is up.
- [x] **Multi-PC URL favourites** — reinterpreted as **named presets** since
      Lenscast is the server (so there's no "PC IP" to remember). Save the
      current protocol+resolution+fps+lens as a labelled preset; apply or
      delete from the Settings sheet.

## Audio

- [x] **Microphone source picker** — Settings segmented row (Camcorder / Default
      mic / Voice recog / Voice comm / Unprocessed) drives
      `MediaRecorder.AudioSource` on the AacEncoder.
- [x] **Live audio level meter** — AacEncoder publishes the per-buffer peak
      amplitude as dBFS. MainScreen shows a colour-shifting bar (green → amber
      → red at -3 dBFS) below the stat row while audio is on.
- [x] **Manual gain control** — software gain stage in the captureLoop (-24..+24
      dB) with hard clip. Live-updated via a @Volatile linear factor so the
      slider re-applies without restarting the encoder.
- [x] **Audio over the MJPEG transport** — `AudioBroadcaster` mirrors
      `FrameBroadcaster` for AAC AUs. When MJPEG + audio is on,
      `StreamingService.startMjpeg` spins up an `AacEncoder` and the
      `MjpegServer` exposes an `/audio` endpoint that streams `audio/aac` with
      synthesised ADTS headers (so ffmpeg / VLC / browser `<audio>` can decode
      without out-of-band ASC). Runtime permission, foreground-service type
      and Settings UI are no longer gated on RTSP.
- [x] **Noise suppression / echo cancel toggle** — Settings toggles. Effects
      attached to the AudioRecord session id when `isAvailable()` returns true;
      released in `AacEncoder.stop`.

## Capture & sharing

- [x] **Snapshot button → gallery** — overlay button next to torch; saves the latest
      MJPEG frame to `Pictures/Lenscast/` via MediaStore, with a Toast confirming.
- [x] **Burst snapshot** — long-press the snapshot button. 5 shots at ~250 ms
      intervals via `StreamingService.saveSnapshot`.
- [x] **Local recording** — RTSP-only Settings toggle. `RecordingMuxer` taps the
      existing H.264 + AAC encoder callbacks and feeds a `MediaMuxer` writing to
      `Movies/Lenscast/Lenscast_<ts>.mp4` via MediaStore (IS_PENDING handshake
      on Q+). Finalised when streaming stops; a Toast confirms the saved path.

## UX, background, system integration

- [x] **Quick-settings tile** — `StreamingTileService` bound to
      `StreamingService.status`; tap toggles streaming using the last-saved settings.
- [x] **Auto-start on app launch** — Settings toggle. Once-per-process flag in
      `MainScreen` so rotation doesn't re-trigger it.
- [x] **Start-on-boot** — `BootReceiver` listens for `BOOT_COMPLETED` and
      `LOCKED_BOOT_COMPLETED`; if Settings.startOnBoot is true, hands off to the
      service via `ACTION_START_TILE`. Settings sheet shows the
      battery-optimisation caveat next to the toggle.
- [x] **Battery-saver / blank-preview mode** — Settings toggle hides the
      on-screen preview while streaming; the analysis pipeline keeps publishing JPEGs.
- [x] **Picture-in-picture preview** — `MainActivity.onUserLeaveHint` auto-enters
      PiP when the user presses Home while streaming; `MainScreen` collapses to
      just the preview when `inPictureInPicture` is true. Aspect ratio follows
      the selected resolution.
- [x] **Web control page** — `/` now renders a control panel with Switch camera,
      Toggle torch, Snapshot and Stop buttons that POST to `/control/*`
      endpoints handled inside `MjpegServer`. The server reaches the service via
      a small `MjpegControl` bridge.
- [ ] **Web control page — extended surface.** The current panel covers the four
      most-common toggles, but the same `MjpegControl` bridge can grow to expose
      most of the Settings sheet from the browser:
      - **Resolution / FPS pickers** (`<select>` + apply button — rebinds the camera).
      - **JPEG quality slider** (live, no rebind needed).
      - **White-balance / effect / scene / anti-flicker pickers** (rebind keyed).
      - **Manual-focus toggle + diopter slider**, **continuous-AF toggle**,
        **mirror toggle** — partially landed (mirror / AF / continuous-AF, see
        below); rest still open.
      - **RTSP bitrate slider** for the RTSP path (no rebind, MediaCodec
        `setParameters` once we wire it).
      - **Audio gain slider** + mic-source picker + NS/AEC toggles (RTSP only).
      - **Multi-client kick** — list active client IPs and a "drop this one"
        button (server already tracks per-socket sessions).
      - **Settings export / import** as a JSON blob the user can paste between
        devices.
      Several of these are already wired up in the second pass:
- [x] **Web control — settings export / import.** `GET /export` downloads a
      JSON blob; the page has a textarea + Import button that `POST /import`s
      the pasted JSON back. Phone Settings sheet has Export / Import buttons
      using the Storage Access Framework. Codec is hand-rolled with `org.json`,
      versioned, and tolerant of missing fields so older exports still apply.
- [x] **Web control — runtime knobs:** zoom in/out, EV ±, mirror toggle,
      continuous-AF toggle, live stats refresh (fps / clients / audio peak)
      polled every second via a new `/status` JSON endpoint.
- [x] **Web control — JPEG quality slider:** `/control/quality?v=NN` updates
      `Settings.jpegQuality` and `CameraController.jpegQuality` live; debounced
      150 ms on the slider input so the user doesn't fire a POST per frame.
- [x] **Web control — independent panel for both protocols.** New
      `WebControlServer` on its own port (Settings → `webControlPort`, default
      8080; toggle to disable). Runs the entire time the service is alive — the
      user can hit `http://<phone>:8080/` to start a stream, stop, and tune
      from any LAN device. Page is data-driven from `/status`: idle shows a
      Start button, MJPEG live shows the embedded preview + audio/snapshot
      links + JPEG quality slider, RTSP live shows the `rtsp://` URL hint.
      All control buttons (lens / torch / mirror / continuous-AF / zoom / EV /
      snapshot / stop) are available in either protocol where they make sense.
- [x] **Web control — resolution + FPS pickers.** `/control/resolution?v=720p`
      and `/control/fps?v=30`. Available options come from
      `CameraCapabilities.supportedResolutions` / `supportedFps` in the
      `/status` JSON, so the `<select>` always reflects what the active lens
      can actually do.
- [x] **Web control — protocol picker (idle).** Radio buttons on the idle
      block POST `/control/protocol?v=mjpeg|rtsp` before the user hits Start.
      FPS is clamped to the new path's range automatically.
- [x] **Web control — full Settings parity.** Generic
      `POST /control/setting?key=K&v=V` endpoint dispatched by a single
      `updateSetting()` on `MjpegControl`. Page has a collapsible Settings
      panel mirroring the app's Settings sheet (lens / image / audio /
      stream / UX / automation / server ports). `/status` carries every
      field; the 1 Hz refresh repopulates controls except the one currently
      focused so the user's edits aren't clobbered mid-type. Streaming-only
      restrictions (audio toggle, mic source, NS/AEC, ports, etc.) are
      enforced server-side.
- [x] **Encoder bitrate cap** — RTSP-only slider in Settings (0 kbps = use the
      resolution+fps heuristic).
- [x] **Light theme polish** — fill out the `lightColorScheme` with brand-tinted
      surface tones (surfaceContainer / surfaceContainerHigh / surfaceContainerHighest /
      surfaceVariant / primaryContainer / outline / onSurfaceVariant). The default M3
      light scheme left those at pure-white grays, collapsing the URL row stack inside
      the connection card. Six new colour constants in `theme/Color.kt`.

## Desktop / PC-side

- [x] **GUI for the Linux helper** — `pc/lenscast-virtualcam-gui` is a
      single-file Python/GTK3 wrapper. URL field with **Find on LAN**
      (mDNS discovery via `avahi-browse`), loopback dropdown
      (auto-detected from `v4l2-ctl`), audio + insecure toggles, Start/Stop
      with a streaming log view. Settings persist to
      `~/.config/lenscast/gui.json`. No tray icon (Ayatana AppIndicator is
      finicky across desktops), but the window is small enough to live on a
      workspace by itself.
- [x] **Linux helper — audio + HTTPS.** `lenscast-virtualcam -a` creates a
      PulseAudio null sink (`lenscast`) and runs a parallel ffmpeg pulling
      `/audio` (PCM-16LE WAV) into it; apps pick "Monitor of Lenscast" as a
      mic. `--insecure` passes `-tls_verify 0` to ffmpeg for self-signed
      HTTPS URLs from Lenscast's built-in TLS toggle. Doctor checks for
      `pactl`. README updated with both flags.

## Open

- [x] **Persistent web control server.** Opt-in
      `Settings.persistentWebControl`. When on + `webControlEnabled`, the
      service promotes itself to a SPECIAL_USE foreground notification
      (own low-importance channel `lenscast.webcontrol`) so the OS can't
      reap it while the user is in another app. `BootReceiver` and
      `MainActivity` both fire `ACTION_PERSIST_WEB` to start it cold;
      `onCreate` observes the setting and swaps state at runtime;
      `stopStreaming` falls back into the web notification instead of
      dropping foreground entirely. Manifest grew `specialUse` to the
      service's `foregroundServiceType`, a `<property>` element with the
      Android 14 explanation, and a `FOREGROUND_SERVICE_SPECIAL_USE`
      permission.
- [x] **Privacy / call-handling mode.** `Settings.callBehavior` enum
      (IGNORE / MUTE_STREAM / DROP_CALL). `TelephonyMonitor` registers a
      `TelephonyCallback.CallStateListener` on API 31+ and falls back to
      `PhoneStateListener` on older releases. On non-IDLE: MUTE_STREAM
      sets a `muted` flag on `PcmCapture` + `AacEncoder` (zero the buffer
      before gain so the wire carries true silence and the VU drops to
      −90 dBFS); DROP_CALL calls `TelecomManager.endCall()` and falls
      back to mute when the API is rejected. READ_PHONE_STATE +
      ANSWER_PHONE_CALLS requested at runtime when the user picks a
      non-IGNORE option; if denied, the monitor silently no-ops.

## Out of scope / not planned

- **Native UVC gadget from inside Lenscast** — covered and rejected in
  [Roadmap → Planned](Docs/Roadmap.md#native-uvc-gadget-from-inside-lenscast--not-feasible-from-a-third-party-app).
- **Native Windows DirectShow / MediaFoundation virtual camera and macOS
  Camera Extension.** Technical work is moderate but the EV code-signing
  (~$300/yr), Apple Developer Program ($99/yr) and ongoing
  notarization / WHQL maintenance overhead aren't a fit for a hobby
  project. OBS Virtual Camera already covers both platforms by
  consuming the MJPEG URL — that's the recommended path.
  Droidcam doesn't actually do this either; the gap is the same on both sides.
- **>30 fps MJPEG** — architectural cap in
  [Roadmap → Known architectural cap](Docs/Roadmap.md#known-architectural-cap-mjpeg--30-fps).
  Use the RTSP transport for high frame rates.
