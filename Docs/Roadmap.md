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

- **Tap-to-focus.** CameraX `MeteringPointFactory` + `FocusMeteringAction`. Wire into
  `PreviewSurface` so a tap forwards a focus request to `CameraController`.
- **Pinch-to-zoom.** `Camera.cameraControl.setZoomRatio(...)`.
- **Snapshot button.** Save a frame to `Pictures/Lenscast/` via MediaStore.
- **Light theme polish.** The Material 3 light scheme is wired but rarely used; tweak
  contrast on the connection card.
- **Settings: encoder bitrate cap** for users on constrained networks.
- **Tile widget.** Quick toggle for "Start streaming" from the notification shade.
