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
- [ ] **ISO / sensor sensitivity lock** — Camera2 `SENSOR_SENSITIVITY` (requires
      `CONTROL_AE_MODE = OFF`). MJPEG path needs a Camera2 interop bridge or a switch
      to direct Camera2; RTSP path already owns Camera2.
- [ ] **Manual shutter speed** — `SENSOR_EXPOSURE_TIME`, same AE_OFF requirement.
- [x] **White-balance presets** — `CONTROL_AWB_MODE` via Camera2Interop. Settings
      sheet (Auto / Tungsten / Fluor / Daylight / Cloudy / Shade). MJPEG path.
- [ ] **Manual focus distance** — `LENS_FOCUS_DISTANCE` with a slider (0 = infinity,
      max from `LENS_INFO_MINIMUM_FOCUS_DISTANCE`). Toggle: AF / MF.
- [x] **Continuous-AF toggle** — `CONTROL_AF_MODE_CONTINUOUS_VIDEO` vs `_AUTO`.
      Settings sheet. MJPEG path.
- [ ] **Scene modes** — night, sports, action. `CONTROL_SCENE_MODE` where supported.
- [x] **Anti-banding** — `CONTROL_AE_ANTIBANDING_MODE` (Auto / 50 Hz / 60 Hz / Off).
      Settings sheet. MJPEG path.
- [ ] **Effects / filters** — Droidcam Pro has mono, sepia, negative, etc. Camera2
      `CONTROL_EFFECT_MODE`. Cheap to wire on the RTSP path; MJPEG needs a YUV-domain
      pass or a switch to GPU.
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

- [ ] **USB transport over ADB forward** — Droidcam ships a desktop client that runs
      `adb forward tcp:4747 tcp:4747` so the phone streams over USB without Wi-Fi.
      Lenscast's MJPEG server already binds on all interfaces; what's missing is a
      one-click PC-side helper or docs walking the user through `adb forward` for
      both ports. The Linux `lenscast-virtualcam` helper could grow a `--usb` mode
      that runs the forward automatically.
- [x] **Stream password / basic auth** — HTTP Basic auth on the MJPEG endpoints
      (`/video`, `/shot.jpg`, `/`). Settings field for the passcode; username fixed
      to `lenscast`. RTSP DIGEST is a separate follow-up.
- [ ] **HTTPS / RTSPS** — TLS for both protocols, even with a self-signed cert,
      lets users on hostile networks stream without exposing credentials. Add a
      "Generate self-signed cert" Settings action.
- [x] **Bonjour / mDNS advertisement** — `NsdAdvertiser` broadcasts `_http._tcp.`
      for MJPEG and `_rtsp._tcp.` for RTSP while the server is up.
- [ ] **Multi-PC URL favourites** — Droidcam Pro remembers IPs the user has streamed
      to before. Settings list of saved presets + a quick-pick on Start.

## Audio

- [ ] **Microphone source picker** — Droidcam Pro lets the user choose between
      camcorder mic, voice-recognition mic, and unprocessed. `MediaRecorder.AudioSource`
      values (`CAMCORDER`, `MIC`, `VOICE_RECOGNITION`, `UNPROCESSED`).
- [ ] **Live audio level meter** — small VU meter in the streaming card so users
      know the mic is hot. Sample `AudioRecord` peaks on the existing audio path.
- [ ] **Manual gain control** — software gain stage on the AAC encoder input. Cap
      at +12 dB with a soft limiter.
- [ ] **Audio over the MJPEG transport** — Droidcam Pro streams audio even on its
      MJPEG-equivalent mode (separate WAV channel on a sibling port). Lenscast only
      ships audio on RTSP today.
- [ ] **Noise suppression / echo cancel toggle** — Android `NoiseSuppressor`,
      `AcousticEchoCanceler` effects, attached to the `AudioRecord` session.

## Capture & sharing

- [x] **Snapshot button → gallery** — overlay button next to torch; saves the latest
      MJPEG frame to `Pictures/Lenscast/` via MediaStore, with a Toast confirming.
- [ ] **Burst snapshot** — Droidcam Pro can fire a sequence. Same plumbing as the
      single-shot snapshot, plus a count selector.
- [ ] **Local recording** — write the live H.264 + AAC stream to an MP4 in
      `Movies/Lenscast/` while streaming. `MediaMuxer` fed from the same encoders.

## UX, background, system integration

- [x] **Quick-settings tile** — `StreamingTileService` bound to
      `StreamingService.status`; tap toggles streaming using the last-saved settings.
- [x] **Auto-start on app launch** — Settings toggle. Once-per-process flag in
      `MainScreen` so rotation doesn't re-trigger it.
- [ ] **Start-on-boot** — `BOOT_COMPLETED` receiver that brings the foreground
      service up. Document the battery-optimisation exemption it implies.
- [x] **Battery-saver / blank-preview mode** — Settings toggle hides the
      on-screen preview while streaming; the analysis pipeline keeps publishing JPEGs.
- [ ] **Picture-in-picture preview** — `PictureInPictureParams` so the user can
      keep an eye on the framing while using another app.
- [ ] **Web control page** — extend the `/` landing page to expose Start / Stop,
      lens switch, torch, resolution from the browser. Already half-built since
      `MjpegServer` serves a landing HTML.
- [x] **Encoder bitrate cap** — RTSP-only slider in Settings (0 kbps = use the
      resolution+fps heuristic).
- [ ] **Light theme polish** — already in [Roadmap → Minor polish](Docs/Roadmap.md).

## Desktop / PC-side

Droidcam ships native Windows and macOS clients with a virtual-camera driver. Lenscast
has the Linux v4l2loopback helper only.

- [ ] **Windows virtual-camera driver** — DirectShow filter or MediaFoundation
      virtual camera reading the MJPEG / RTSP stream. Code-signing required, called
      out as out-of-scope in the roadmap; track here so the gap is explicit.
- [ ] **macOS virtual-camera System Extension** — same shape as Windows. Needs
      Apple notarization.
- [ ] **GUI for the Linux helper** — `lenscast-virtualcam` is CLI today. A small
      tray app (start/stop, pick device, pick stream URL) closes most of the gap
      with the Droidcam desktop UX.

## Out of scope / not planned

- **Native UVC gadget from inside Lenscast** — covered and rejected in
  [Roadmap → Planned](Docs/Roadmap.md#native-uvc-gadget-from-inside-lenscast--not-feasible-from-a-third-party-app).
  Droidcam doesn't actually do this either; the gap is the same on both sides.
- **>30 fps MJPEG** — architectural cap in
  [Roadmap → Known architectural cap](Docs/Roadmap.md#known-architectural-cap-mjpeg--30-fps).
  Use the RTSP transport for high frame rates.
