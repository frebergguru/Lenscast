# TODO — feature backlog

Candidate features Lenscast does not ship yet, grouped by area. Items already tracked in
[Roadmap.md](Roadmap.md) are linked rather than re-described — they belong on
this list for completeness but the design notes live in the roadmap.

## Manual camera controls

Per-stream camera controls. CameraX `CameraControl` / `Camera2 CaptureRequest` covers most
of them.

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
      [USB.md](USB.md) (one-shot `adb forward` for both ports,
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
- [x] **Web control page — extended surface.** Every sub-bullet below landed
      in the second / third / fifth passes — the Settings tab now mirrors the
      app's Settings sheet 1:1 via `POST /control/setting?key=K&v=V`. The
      fifth pass added a watermark text input (Image tab), a live `Bitrate`
      stat chip fed by a server-rolled `txKbps` on `/status`, and a
      battery+thermal `health` banner at the top of the page that mirrors
      `HealthMonitor` (synchronous `snapshotNow()` keeps `/status` cheap).
      - **Resolution / FPS pickers** (`<select>` + apply button — rebinds the camera).
      - **JPEG quality slider** (live, no rebind needed).
      - **White-balance / effect / scene / anti-flicker pickers** (rebind keyed).
      - **Manual-focus toggle + diopter slider**, **continuous-AF toggle**,
        **mirror toggle** — partially landed (mirror / AF / continuous-AF, see
        below); rest still open.
      - **RTSP bitrate slider** for the RTSP path (no rebind, MediaCodec
        `setParameters` once we wire it).
      - **Audio gain slider** + mic-source picker + NS/AEC toggles (RTSP only).
      - **Multi-client kick** — done in the third pass. /status carries a
        `clientList` array of `host:port` strings; the live Status card renders
        each with a Drop button that POSTs `/control/kick?remote=…`. Tracks
        every PLAYing RTSP socket and every MJPEG `/video` + `/audio`
        consumer; short-lived requests (shot/landing/status) are not listed.
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

### Polish / quality-of-life

- [x] **QR code on the Connection card.** Tap to expand the URL into a QR; another
      phone or laptop can scan instead of typing `https://192.168.x.y:8080/`.
      Long-press the card or tap the new QR icon. ZXing.
- [x] **Stats overlay in the app.** Bitrate chip alongside FPS / clients in the
      stat row; rolling 1 s window of bytes shipped across MJPEG + RTSP. **Dropped
      + RTT now shipped** as a second chip row (and on the web panel's stats grid +
      `/status` JSON as `dropped` / `rttMs`). RTT comes from parsing the LSR/DLSR
      fields of incoming RTCP Receiver Reports (`Rtcp.roundTripMs`, RFC 3550
      §6.4.1) in `RtspManager.onReceiverReport` — RTSP-only, `-1`/"—" on the other
      paths. Drops are deliberately best-effort and per-path, since they aren't a
      single source: RTSP reports the receiver's cumulative wire loss (RR
      `cumulativeLost`); SRT/RIST count the TS packets shed locally when the link
      can't keep up with the encoder; MJPEG reports 0 (CameraX drops upstream of
      the analyzer, invisible to us). `RtcpTest` now pins LSR/DLSR parsing + the
      RTT math.
- [x] **Watermark / logo overlay.** Settings field; `%t` expands to a wall-clock
      `HH:mm:ss`. Drawn via `Canvas.drawText` on a re-decoded Bitmap after the
      `YuvImage.compressToJpeg` step (cheaper than an NV21-domain glyph cache
      and lets the user pick any string).
- [x] **Network speed test.** `/speedtest?bytes=N` on the MJPEG port sinks N
      bytes of zeros (10 MB default, capped at 100 MB) so the receiver can
      measure raw LAN throughput. Web control panel has a one-tap "Run LAN
      speed test" button that reports Mbps.
- [x] **Battery / thermal warning.** `HealthMonitor` banner on MainScreen.
      `BatteryManager` for level/charging, `PowerManager.OnThermalStatusChangedListener`
      on API 29+. Surfaces a warning/error banner at WARN / CRITICAL thresholds.

### Adjacent use cases

- [x] **RTSPS (RTSP over TLS).** `RtspServer` constructor accepts the same
      `SSLContext` as the MJPEG path; receivers use `rtsps://`. URL schemes in
      the Connection card flip when HTTPS is on.
- [x] **Multi-client RTSP.** `RtspManager.activeSinks` is a `CopyOnWriteArrayList`;
      every NAL/AAC packet is fanned out to every PLAYing session. `RtspServer`
      no longer boots the previous client on new accept. Verified the
      `onClientTeardown(sink)` callback identifies the right sink to remove.
- [x] **Multi-protocol simultaneous** (MJPEG sidecar + RTSP/SRT/RIST). The
      `Settings.mjpegSidecar` toggle now applies to all three Camera2 codec
      paths (RTSP, SRT, RIST), not just RTSP. When on, `RtspCameraDriver.start`
      also takes a `sidecarSurface` and adds it to the `CameraCaptureSession`
      output set alongside the H.264 encoder Surface (for SRT/RIST the camera's
      primary output is the GL rotator's SurfaceTexture; the sidecar is just a
      third target). The sidecar is an `ImageReader` (`YUV_420_888`, 2-buffer)
      whose `OnImageAvailableListener` pushes each latest frame through
      `YuvToJpeg.encodeImage` and publishes to the existing `FrameBroadcaster`;
      `MjpegServer` is started on `mjpegPort` alongside the codec stream.
      `StreamingService.buildSidecarSurface` / `startSidecarMjpegServer` are the
      shared helpers; `SrtManager`/`RistManager` hold the surface and re-apply it
      across mid-stream rotation / lens switch. The web panel attaches its
      `<img>` preview off the new `mjpegServing` status flag (RTSP/SRT/RIST
      heroes each got their own preview `<img>` + overlay). Self-throttled to
      15 fps so the JPEG encode doesn't starve the H.264 encoder on the same
      camera output. Constraint: ≤30 fps only — Camera2 high-speed sessions
      reject ImageReader YUV outputs, so the toggle is greyed at >30 fps with a
      note. WebRTC stays mutually exclusive — its `Camera2Capturer` claims the
      camera the same way `RtspCameraDriver` does, so a WebRTC-plus-sidecar would
      need a deeper rewrite (Camera2 ownership moved into a shared driver fanning
      out to all encoder backends).
- [x] **Adaptive bitrate.** `Rtcp.parseReceiverReports` extracts fraction-lost +
      cumulative-loss + jitter from incoming RR packets. `RtspSession` starts a
      per-UDP-session listener on the rtcpSocket and forwards each RR to
      `RtspManager` via a new `StreamProvider.onReceiverReport` callback.
      A 2-second AIMD loop in `RtspManager` (`startAdaptiveLoop`) drives
      `H264Encoder.setBitrate` via MediaCodec `PARAMETER_KEY_VIDEO_BITRATE`:
      multiplicative 0.75× decrease on loss > 5 %, additive ~62 kbps increase
      after 8 s of loss < 1 %, floor 250 kbps, ceiling = the configured
      `videoBitrateBps`. TCP-interleaved RR is now demuxed too — `RtspSession.serve`
      wraps the socket InputStream in a `PushbackInputStream`, peeks the first
      byte, and dispatches `$<channel><len>` frames (RTCP channels 1/3) through
      the same parser. OBS et al. — which default to TCP — now drive ABR.
- [x] **Recording uploads** (SFTP). `com.hierynomus:sshj:0.39.0` (reuses our
      Bouncy Castle for crypto). New `SftpUploader` runs a single-worker
      coroutine queue: stages each MediaStore MP4 into `cacheDir` via
      `contentResolver.openInputStream`, opens an SSHClient with optional
      SHA-256 host-key pinning, `mkdirs` the remote directory, `put`s the file.
      3 attempts with 5s/20s/60s backoff before drop. Settings fields cover
      host/port/user/password/remoteDir/fingerprint; surfaced on both the
      phone Settings sheet and the web control panel (System tab) with a
      2 Hz `/sftp/status` poll and an "Upload last recording now" button
      backed by `/sftp/retry`. WebDAV and SMB remain open — same plumbing,
      different transport.
- [ ] **Cloud relay.** "Phone is on cellular, receiver is on a different LAN" —
      needs a hosted Lenscast-relay service that both sides connect to.
      Significant infrastructure + ongoing cost; deliberately deferred.
- [x] **WebRTC** (browser viewer with audio + DataChannel). `io.github.webrtc-sdk:android:125.6422.07`
      adds ~30 MB of native code. New `Protocol.WEBRTC` — mutually exclusive
      with MJPEG / RTSP because `Camera2Capturer` owns the camera. Per-peer
      `PeerConnection`s spun up via `POST /webrtc/offer` on the web control port
      (half-trickle ICE: the answer carries every candidate inline so no
      separate trickle endpoint is needed). Browser viewer at `/webrtc/view`
      is a single self-contained HTML page; the main web panel also embeds an
      in-place preview that uses the same handshake. **Audio:** a
      `JavaAudioDeviceModule` with hardware AEC/NS, gated on the existing
      `Settings.audioEnabled` toggle. An audio track is added to every PC
      alongside the video track. **DataChannel:** every PC opens a "lenscast"
      channel; browser-side quick-control buttons (lens, torch, mirror, AF,
      zoom, EV, snapshot) send `{"cmd":"…"}` JSON over it and bypass the
      HTTP endpoints. The Kotlin side routes those commands through the same
      `MjpegControl` surface the web HTTP path already uses.
- [x] **WHEP-compliant endpoint.** Shipped as a dedicated `POST /whep` on the
      web-control port (the WHEP-shaped `/webrtc/offer` stays for the in-house
      viewer): offer `application/sdp` in, `201 Created` + answer SDP +
      `Location: /whep/<id>` out, and `DELETE /whep/<id>` for graceful teardown
      (`webRtcWhepCreate` / `webRtcWhepDelete`). `OPTIONS` is handled for CORS.
      Trickle ICE / ICE restart via `PATCH <resource>` is deliberately **not**
      supported (returns `405`) — we gather every candidate before returning the
      answer, so there's nothing to patch. Strict WHEP clients (OBS WHIP/WHEP
      plugin, future receivers) connect without the in-house viewer's workarounds.
- [x] **SRT (Secure Reliable Transport) — `Protocol.SRT`.** Uses
      `io.github.thibaultbee.srtdroid:srtdroid-core:1.9.5` (the ThibaultBee
      Android wrapper around libsrt) for the socket layer; ~600 KB .so per
      ABI. Two modes:
        - **Caller**: phone dials `srt://<host>:<port>` (defaults to UDP
          9710). Best path through NAT.
        - **Listener**: phone binds the port; OBS / ffmpeg pull from
          `srt://<phone-ip>:<port>`.
      Wraps the existing `H264Encoder` + `AacEncoder` outputs in a
      hand-rolled `MpegTsMuxer` (188-byte TS packets, PAT/PMT every 50
      packets, PCR on video PID, AAC ADTS framing) and ships each packet
      via `SrtPublisher`. Settings: mode, host, port, AES passphrase,
      ARQ latency window (20–8000 ms), optional stream-id. Same camera
      pipeline as RTSP, so the FPS / resolution / lens picker behave
      identically.
- [x] **RIST (Reliable Internet Stream Transport) — `Protocol.RIST`.**
      VSF's vendor-neutral alternative to SRT. Shipped the **Simple
      Profile** (VSF TR-06-1) as a **pure-Kotlin** sender — no librist /
      JNI / NDK build at all. The wire format is plain RTP carrying
      MPEG-TS (`PT=33`, 7×188 per packet) plus RTCP-driven
      retransmission, which is exactly what Simple Profile prescribes and
      gives it the "receiver compatibility everywhere" property
      (ffmpeg `rist://`, OBS, Wowza, …). `streaming/rist/RistPublisher`
      owns two `DatagramSocket`s (data on the even port, RTCP on
      port + 1): it bundles TS into RTP, keeps every sent packet in a
      ring buffer, and resends on an incoming RTCP **Generic NACK**
      (RFC 4585 RTPFB `PT=205 FMT=1`); it also emits a periodic Sender
      Report (reusing `rtsp/Rtcp.buildSenderReport`). `RistManager` is a
      structural copy of `SrtManager` — same `RtspCameraDriver`, GL
      rotation stage, H.264/AAC encoders and `srt/MpegTsMuxer` — so
      seamless mid-stream rotation and lens switching work identically.
      Two modes (Caller / Listener) mirror SRT. Settings block: mode,
      host, data port, profile, encryption passphrase, AES key length,
      buffer window; full phone Settings-sheet + web-control parity,
      export/import, and `/status` JSON.
      **Main Profile** (TR-06-2) now ships too, also pure-Kotlin: GRE v2
      encapsulation over a single UDP port (`streaming/rist/RistPublisher`
      branches on profile), the VSF ethertype (`0xCCE0`) + reduced-overhead
      header (virt ports 1971→1968), a 32-bit GRE sequence, and **PSK
      AES-CTR encryption** (`streaming/rist/RistCrypto`) with
      PBKDF2-HMAC-SHA256(1024, salt=4-byte nonce) key derivation and
      IV = `BE32(greSeq)‖0` — implemented byte-for-byte from the librist
      source (`src/proto/gre.c`, `src/crypto/psk.c`) and pinned with
      known-answer unit tests (RFC PBKDF2 vectors + AES round-trip).
      Retransmit handles both RIST NACK shapes (range PT 204, bitmask
      PT 205). AES-128/256 selectable; the GRE header signals the size so
      receivers adapt. **Not implemented:** NULL-packet suppression and
      DTLS (certificate) encryption — PSK only. NOTE: builds + unit-tests
      clean and the crypto matches the spec vectors, but end-to-end interop
      against a live RIST receiver (ffmpeg `rist://…?secret=`, VLC, Wowza)
      has **not** been exercised here and should be confirmed on hardware.

      **Interop status (tested 2026-05-29 vs librist 0.2.14 `ristreceiver` on the LAN):**
        - **Simple Profile, Caller mode -> librist: VERIFIED end-to-end.** Phone dials the
          receiver, librist completes its handshake, authenticates the flow, and decodes our
          H.264 (`quality:100`, `lost:0`). Three interop bugs were found + fixed:
            1. Simple-Profile RTP goes to the data port, RTCP to **data+1** (librist pairs the
               data/RTCP peers by source IP only) — the SR must be on the odd port.
            2. librist refuses data until it sees an RTCP **SDES (CNAME)**; we now send a
               compound **SR+SDES** (`RistPublisher.buildSdes`), bursted on connect + every
               100 ms to beat its ~270 ms handshake-window race.
            3. RIST **echo** req/resp reuse `PT 204` + name "RIST", differing from the range
               NACK only in the first flag byte (0x82/0x83 vs 0x80). Keying NACK detection off
               PT alone decoded echo NTP stamps as huge seq ranges -> resend storm. Now matched
               on the full flag byte + "RIST" name, with a run-length cap.
        - **Retransmit (re-enabled): VERIFIED.** With the echo/NACK fix, a clean LAN run shows
          `dropped_late:0 duplicates:0 quality:100` at the encoder's ~2.5 Mbps (no storm).
        - **Main Profile, Caller mode → librist (`-p 1`): VERIFIED, AES-128 and AES-256.**
          Recorded + decoded clean H.264 720x1280 (~27 fps) for both key sizes; a deliberately
          wrong passphrase yields **zero** decodable output, confirming the PSK AES-CTR is real.
        - **Listener mode** (phone binds; receiver dials in): peer learning **reworked to be
          symmetric** (`RistPublisher.learnPeer` + a Simple-Listener `dataListenLoop`) — each
          direction is pinned to the exact source address:port a packet arrived from, never the
          old hardcoded `data+1`. **Full matrix re-tested live (2026-05-30) vs ristreceiver
          (librist 0.2.14):**

          | mode | Simple (-p 0) | Main (-p 1) |
          |------|---------------|-------------|
          | **Caller** (phone→receiver-listening)   | ✅ quality 100, lost 0, ~2.3 Mbps | ✅ quality 100, lost 0, ~2.3 Mbps |
          | **Listener** (receiver dials phone)     | ❌ librist limitation             | ✅ **quality 100, lost 0, ~2.1 Mbps** |

          **RIST Listener now works — via the Main profile.** Main muxes data+RTCP on one
          GRE port with a real keepalive handshake, so ristreceiver's caller knocks the correct
          port, the phone learns its source (symmetric `learnPeer`), and librist parents the
          flow. **Simple + Listener still won't talk to librist**, for two librist-side reasons
          proven by capture: (1) librist's simple caller-receiver sends its RTCP to a **fixed
          port 32769** regardless of the URL port; (2) even after aligning ports so full
          bidirectional RTCP + data flows, librist logs `FLOW … cannot be created … this peer
          has no parent` and times out — it can't parent a flow on its simple caller-receiver
          side (matches the earlier librist↔librist failure). The symmetric fix is still proven
          correct for Simple via a direct UDP probe (phone learns each source port, streams
          back). **Listener is now gated to Main in the UI:** under Simple the mode picker
          offers Caller only (web panel disables the Listener option) with a short "Listener
          needs the Main profile" message, and switching the profile to Simple coerces a
          selected Listener back to Caller — enforced in both UIs and defensively in
          `StreamingService.updaterFor`, so API/import can't persist the invalid pair.
        - Cosmetic `/status` negative-`fps` glitch on (re)start: **fixed** — the rolling FPS
          re-baselines when the encoder's frame counter restarts, and clamps to >= 0.
      Working receiver setup: **phone = Caller -> PC**, receiver listening (`rist://@:5004`).
      VLC's RIST input binds locally and can't dial the phone, so phone-as-Listener won't work
      with VLC.

      **Spec audit (vs VSF TR-06-1 / TR-06-2 PDFs, 2026-05-29):**
        - Caller mode is exactly the TR-06-1 §5.1.1 unicast model (sender -> receiver RTP on
          port P, compound RTCP on P+1; receiver replies to the sender's RTCP source port).
          **Simple Profile has no caller/listener concept** — that's a librist NAT-traversal
          extension. So "Caller" = the spec's normal sender; our **Listener mode is a
          librist-ism**, hence its fragility (librist's caller-receiver puts RTCP on a dynamic
          32768+ port a fixed listener can't predict). UI hint updated to say so.
        - Compound **SR+SDES(CNAME)** at <=100 ms matches §5.2.1/§5.2.5; SDES byte layout matches.
        - NACK formats match: bitmask = Generic NACK PT205/FMT1 (§5.3.2.1); range = APP PT204
          subtype 0 name "RIST" (§5.3.2.2); echo APP PT204 uses other subtypes (§5.2.6) — the
          dispatcher now distinguishes them by the full first byte.
        - **Fixed:** TR-06-1 §5.3.3 SSRC original/retransmit flag — originals now force SSRC
          LSB=0 (even if the user picks an odd port) and retransmits set LSB=1 in **both**
          profiles (previously Simple resends were unmarked).
        - Main Profile crypto matches TR-06-2 §7.2/§7.3 byte-for-byte: AES-128/256-CTR over the
          GRE payload, IV = 32-bit seq as the MSBs + 12 zero bytes (the secure RV>=1 arrangement),
          PBKDF2-HMAC-SHA256 1024 iters with the GRE nonce as salt. The **Annex B official key
          vector** is now pinned as a unit test (`pbkdf2_matchesRistSpecAnnexBVector`) and passes.
          The H bit (key length) is flags2 bit6 per §6.3.

### Engineering robustness

- [x] **Opt-in crash reporter.** `CrashReporter.install(this)` from
      `LenscastApp.onCreate` sets the uncaught-exception handler that writes
      `lenscast-diagnostics.txt` (stack trace + last ~400 logcat lines, capped
      at 200 KB) into app-private files. "Share diagnostics" button in the
      Backup section of Settings shares it via `FileProvider`.
- [x] **JVM unit tests** — `app/src/test/kotlin` now hosts hermetic tests for
      `Sdp.build`, `Rtcp.buildSenderReport` + `parseReceiverReports`, and
      `H264RtpPacketizer.packetize` (FU-A fragmentation, marker-bit, monotonic
      sequence numbers). 18 tests, JUnit 4, runs via `./gradlew :app:testDebugUnitTest`.
      Sdp swapped `android.util.Base64` for `java.util.Base64` so the SPS/PPS
      encoder is JVM-callable. Instrumented tests for the camera + streaming
      pipeline are still deferred — they'd need Robolectric or a real device.
- [x] **Norwegian localization.** `values-nb/strings.xml` (Bokmål). All
      user-facing strings already went through `R.string` so the resource
      override picks them up at runtime when the device locale is `nb`.

## Out of scope / not planned

- **Native UVC gadget from inside Lenscast** — covered and rejected in
  [Roadmap → Planned](Roadmap.md#native-uvc-gadget-from-inside-lenscast--not-feasible-from-a-third-party-app).
- **Native Windows DirectShow / MediaFoundation virtual camera and macOS
  Camera Extension.** Technical work is moderate but the EV code-signing
  (~$300/yr), Apple Developer Program ($99/yr) and ongoing
  notarization / WHQL maintenance overhead aren't a fit for a hobby
  project. OBS Virtual Camera already covers both platforms by
  consuming the MJPEG URL — that's the recommended path.
- **>30 fps MJPEG** — architectural cap in
  [Roadmap → Known architectural cap](Roadmap.md#known-architectural-cap-mjpeg--30-fps).
  Use the RTSP transport for high frame rates.
