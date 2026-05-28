# Using Lenscast as a regular webcam

Lenscast streams MJPEG over HTTP, which OBS can read directly. For apps that don't
have a "Media Source" concept — Zoom, Google Meet, Discord, Chrome, Slack — you
need to expose the stream as a system camera device. There are two ways:

| Approach                          | OSes              | Pros                                  | Cons                                                |
|-----------------------------------|-------------------|---------------------------------------|-----------------------------------------------------|
| **OBS Virtual Camera**            | Linux, macOS, Win | Zero extra software beyond OBS        | Needs OBS running                                   |
| **`lenscast-virtualcam` helper**  | Linux             | Standalone; no OBS                    | Linux only; needs `v4l2loopback`                    |

## Why not a "real" USB webcam (UVC gadget)?

Both options above run on the PC, not the phone. A purer fix would be the phone
presenting itself to USB as a UVC video class device — plug in, no software at
all. The Android public SDK does not expose this to third-party apps; the
`IDeviceAsWebcam` AIDL is `@SystemApi` and only callable by platform-signed
system apps. Doing it from outside the system requires root + custom kernel
configfs work and only a handful of devices cooperate. See
[Roadmap.md](Roadmap.md) for the long-form note.

## Option 1 — OBS Virtual Camera (all OSes)

1. Add a **Media Source** in OBS pointing at `http://<phone-ip>:4747/video`
   (uncheck "Local File"). See [OBS-Integration.md](OBS-Integration.md) for the
   detailed step.
2. **Tools → Start Virtual Camera**. (On Linux this also needs the
   `v4l2loopback` module — see Option 2 step 1.)
3. In Zoom / Chrome / Discord / whatever, pick **OBS Virtual Camera** from the
   camera dropdown.

## Option 2 — `lenscast-virtualcam` (Linux only)

Standalone helper, no OBS in the middle. Full install and usage doc:
[../pc/README.md](../pc/README.md). Short version:

```bash
# One-time
sudo pacman -S v4l2loopback-dkms v4l-utils ffmpeg     # or apt/dnf equivalent
sudo modprobe v4l2loopback video_nr=10 card_label="Lenscast" exclusive_caps=1
sudo ln -s "$PWD/pc/lenscast-virtualcam" /usr/local/bin/lenscast-virtualcam

# Every session
lenscast-virtualcam http://192.168.1.42:4747/video
```

Then pick **Lenscast** in your app's camera dropdown.

If your app is PipeWire-based (kamoso, Chrome/Firefox WebRTC, GNOME Cheese,
anything Qt6 multimedia) it will not see the loopback out of the box — PipeWire
filters v4l2loopback devices. The fix is one extra udev rule; see
[../pc/PipeWire.md](../pc/PipeWire.md).

## Picking between them

- If you already use OBS for streaming/recording: **OBS Virtual Camera**.
- If you just want the phone to act like a webcam without OBS open: **the helper**
  (Linux only).
- If you're on Windows/macOS and don't want OBS open: there isn't a third option
  shipped right now. A signed Windows/macOS virtual-camera driver is a
  multi-week build with code-signing/notarization overhead and isn't on the
  roadmap.
