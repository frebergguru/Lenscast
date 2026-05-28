# Making Lenscast visible to PipeWire apps (kamoso, Chrome, Firefox, …)

By default, `lenscast-virtualcam` produces frames into a `v4l2loopback` device
(e.g. `/dev/video10`). Apps that read `/dev/video*` directly — **OBS, cheese,
guvcview, mpv, ffplay** — see it immediately, no extra setup.

Apps that go through **PipeWire** do not:

- Kamoso (KDE webcam)
- GNOME Cheese on newer GNOME builds
- Chrome / Chromium / Edge WebRTC
- Firefox WebRTC
- Anything Qt6 multimedia–based

This page explains why, and how to fix it with a one-line udev rule.

## Why PipeWire skips the loopback

PipeWire enumerates V4L2 devices via udev. At device-creation time, udev runs
`v4l_id` against each `/dev/videoN`, which queries the kernel for capabilities
and writes `ID_V4L_CAPABILITIES` into the udev database. For
`v4l2loopback` devices, no producer is writing yet, so the kernel reports
**only** `V4L2_CAP_VIDEO_OUTPUT` — udev tags the device as
`ID_V4L_CAPABILITIES=:video_output:`.

PipeWire's V4L2 monitor only enumerates devices tagged `:capture:`. Once tagged,
udev does not re-evaluate when the kernel later flips to advertising capture caps
(which it does the moment `lenscast-virtualcam` opens the device for writing,
thanks to `exclusive_caps=1`).

You can confirm the diagnosis on your own machine:

```bash
udevadm info /dev/video10 | grep ID_V4L_CAPABILITIES
# Expected output before the fix:
#   E: ID_V4L_CAPABILITIES=:video_output:
```

Compare with a real camera:

```bash
udevadm info /dev/video0 | grep ID_V4L_CAPABILITIES
#   E: ID_V4L_CAPABILITIES=:capture:
```

## The fix: a udev rule that tags v4l2loopback as `:capture:`

This repo ships the rule at `pc/99-v4l2loopback-capture.rules`. Install it:

```bash
sudo install -m 644 pc/99-v4l2loopback-capture.rules /etc/udev/rules.d/
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=video4linux
systemctl --user restart wireplumber pipewire
```

What the rule does:

```
SUBSYSTEM=="video4linux", DEVPATH=="*/virtual/video4linux/*", ENV{ID_V4L_CAPABILITIES}=":capture:"
```

It matches any V4L2 device whose udev `DEVPATH` lives under
`/devices/virtual/video4linux/` — that's the path the kernel uses for
v4l2loopback (and any other virtual V4L2 driver). It does **not** affect real
USB / built-in webcams, which sit under `/devices/pci.../usb.../`.

## Verifying

After the restart, list what PipeWire/GStreamer now see:

```bash
gst-device-monitor-1.0 Video/Source | grep -E 'name|object.path'
```

The Lenscast loopback should appear (look for `v4l2:/dev/video10`).

Then:

1. Start the helper:
   ```bash
   ./pc/lenscast-virtualcam http://<phone-ip>:4747/video
   ```
2. Launch kamoso (or any PipeWire-aware app). The loopback shows up in the
   webcam dropdown — pick it.

If kamoso was already open, close it fully and reopen — Qt multimedia caches
the device list at startup.

## Troubleshooting

**The device still doesn't appear after restarting wireplumber.**
Confirm the rule actually applied:

```bash
udevadm info /dev/video10 | grep ID_V4L_CAPABILITIES
# Should now show:  E: ID_V4L_CAPABILITIES=:capture:
```

If it still shows `:video_output:`, the rule file is missing, has a typo, or
the udev trigger didn't re-evaluate. Re-run the three `sudo udevadm` /
`systemctl` commands above.

**`gst-device-monitor-1.0` shows the loopback but kamoso still doesn't.**
Kamoso enumerates cameras at process start. Close every kamoso window
(`pkill kamoso` if needed) and relaunch *while the helper is running*.

**WebRTC apps (Chrome/Firefox) still don't see it.**
Browsers run their own media stack in a sandbox. Restart the browser fully
after the wireplumber restart.

**Reverting the change.**
Delete the rule and reload:

```bash
sudo rm /etc/udev/rules.d/99-v4l2loopback-capture.rules
sudo udevadm control --reload-rules
sudo udevadm trigger --subsystem-match=video4linux
systemctl --user restart wireplumber pipewire
```

## Why this isn't done automatically by the helper

Adding a udev rule is a system-wide change that requires root and affects every
v4l2loopback device on the machine, including ones created by other tools (OBS
Studio's installer creates one too). We don't want `lenscast-virtualcam` to make
that change silently. Run the install command above once, on machines where you
want PipeWire apps to see virtual cameras.
