# Roadmap

## Done

- MJPEG-over-HTTP server, Wi-Fi + USB transports
- CameraX preview before streaming, switchable lens mid-stream
- Foreground service that survives screen-lock
- Tap-to-copy connection URLs, settings sheet, Material 3 UI
- One-port-conflict-friendly settings (port is configurable)

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

### USB UVC webcam mode

Android 14+ supports the `UsbManager` UVC (USB Video Class) gadget mode on devices with
USB role-switch capability. The phone could present itself to a connected PC as a
plug-and-play webcam — no `adb reverse`, no app on the PC. The OS picks it up like any
other webcam.

Sketch of the integration:
- New "USB Webcam" protocol entry alongside MJPEG in `Settings`.
- A `UvcGadgetSession` that, when activated, programs the device's USB role to gadget,
  exposes the video function, and pipes CameraX frames into the gadget surface via the
  `DeviceAsWebcam` AIDL interface (`android.companion.virtual` / `IDeviceAsWebcam`).
- UI: a third tile in the Settings protocol selector, or an "advanced" toggle.

Caveats:
- Not all devices ship the `DeviceAsWebcam` system service. Pixel 8+ does; many OEMs
  don't. Detect and gracefully fall back to MJPEG.
- Requires a real cable that supports OTG and works as data, not just charging.

### RTSP with H.264 + AAC audio

The plumbing for a second protocol lives in `StreamingService.startStreaming` — the
`when` branch on `Protocol.RTSP` currently throws. Re-enabling needs:

1. A working RTSP server library. The pre-rename code used
   [pedroSG94/RootEncoder](https://github.com/pedroSG94/RootEncoder), but the
   `rtspserver` submodule wasn't published on JitPack at the version we targeted (2.5.1).
   Options:
   - Wait for a version where the `rtspserver` submodule is published.
   - Use a fork that does publish it.
   - Hand-roll an RTSP server on top of `MediaCodec` (~2–4 weeks of work — non-trivial RTP
     packetization for H.264 + AAC).

2. Restore `app/src/main/kotlin/dev/lenscast/streaming/RtspManager.kt` (deleted; the file
   wrapped `RtspServerCamera2`).

3. Restore the protocol selector in `SettingsSheet.kt`.

4. Add back the manifest entries we removed: `RECORD_AUDIO`,
   `FOREGROUND_SERVICE_MICROPHONE`, and `android:foregroundServiceType="camera|microphone"`
   on the service.

5. `StreamingService.startForegroundWithType` already branches port + label per protocol —
   just put back the FGS type bitmask combination for the microphone case.

### Minor polish

- **Tap-to-focus.** CameraX `MeteringPointFactory` + `FocusMeteringAction`. Wire into
  `PreviewSurface` so a tap forwards a focus request to `CameraController`.
- **Pinch-to-zoom.** `Camera.cameraControl.setZoomRatio(...)`.
- **Snapshot button.** Save a frame to `Pictures/Lenscast/` via MediaStore.
- **Light theme polish.** The Material 3 light scheme is wired but rarely used; tweak
  contrast on the connection card.
- **Settings: encoder bitrate cap** for users on constrained networks.
- **Tile widget.** Quick toggle for "Start streaming" from the notification shade.
