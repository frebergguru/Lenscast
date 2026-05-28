# Lenscast — Linux virtual camera helper

`lenscast-virtualcam` bridges a running Lenscast MJPEG stream into a Linux v4l2
loopback device, so the phone shows up as an ordinary webcam in Zoom, Google Meet,
Discord, Chrome, OBS, etc. No OBS-in-the-middle required.

It's a thin wrapper around `ffmpeg` + the `v4l2loopback` kernel module. Linux only.

## Install

### 1. Get the kernel module and ffmpeg

| Distro                 | Command                                                           |
|------------------------|-------------------------------------------------------------------|
| Arch / Manjaro         | `sudo pacman -S v4l2loopback-dkms v4l-utils ffmpeg`               |
| Debian / Ubuntu        | `sudo apt install v4l2loopback-dkms v4l-utils ffmpeg`             |
| Fedora                 | `sudo dnf install akmod-v4l2loopback v4l-utils ffmpeg`            |
| openSUSE Tumbleweed    | `sudo zypper install v4l2loopback-kmp-default v4l-utils ffmpeg`   |

### 2. Load the loopback module

```bash
sudo modprobe v4l2loopback video_nr=10 card_label="Lenscast" exclusive_caps=1
```

- `video_nr=10` puts the device at `/dev/video10`. Pick any free number.
- `card_label="Lenscast"` is what apps will show in their camera picker.
- `exclusive_caps=1` is required for Chromium-based apps (Chrome, Discord, Slack,
  Zoom web) to recognise the device as a capture source.

To make it persistent across reboots, drop these two files:

```bash
echo 'v4l2loopback' | sudo tee /etc/modules-load.d/v4l2loopback.conf
echo 'options v4l2loopback video_nr=10 card_label="Lenscast" exclusive_caps=1' \
  | sudo tee /etc/modprobe.d/v4l2loopback.conf
```

### 3. Put the helper on your PATH

From this repo:

```bash
sudo ln -s "$PWD/pc/lenscast-virtualcam" /usr/local/bin/lenscast-virtualcam
```

Or just run `./pc/lenscast-virtualcam` directly.

## Use

1. Start Lenscast on the phone, tap **Start streaming**, note the URL (e.g.
   `http://192.168.1.42:4747/video`).
2. On the PC:

   ```bash
   lenscast-virtualcam http://192.168.1.42:4747/video
   ```

3. Open Zoom / Chrome / Discord — pick **Lenscast** in the camera dropdown.

Ctrl-C in the terminal stops the bridge. The helper auto-reconnects when the
stream drops (phone sleep, Wi-Fi blip), so unplug/replug or app restart is fine.

## Verifying

```bash
# Confirm prerequisites are in place
lenscast-virtualcam --doctor

# List loopback devices
v4l2-ctl --list-devices

# Quick preview of what the virtual camera is producing
ffplay /dev/video10
```

## Flags

```
-d, --device DEV    v4l2 loopback device (default: auto-detect, fallback /dev/video10)
-f, --fps N         Target framerate (default: source's native cadence)
-l, --label NAME    Friendly device label (also used as the PulseAudio device.description
                    when -a is set; default: Lenscast)
-a, --audio         Also forward /audio as a virtual mic (PulseAudio null sink).
                    Derives the audio URL from the video URL by swapping
                    /video for /audio.
    --audio-url URL Explicit audio URL — use if your video URL doesn't end in /video.
    --insecure      Accept self-signed TLS certs (use with https:// URLs from Lenscast's
                    built-in HTTPS toggle).
    --doctor        Check prerequisites and exit
-h, --help          Show this help
```

## Audio (-a) — adds a virtual mic

`lenscast-virtualcam -a <video-url>` adds two things to the regular video bridge:

1. A PulseAudio null sink named `lenscast` (visible as **Lenscast** in Volume Control).
2. A background ffmpeg pulling the WAV stream from `/audio` and writing it into that
   sink.

Pick **Monitor of Lenscast** as your input in Zoom, Discord, Chrome, etc. PipeWire
users get this for free via the pulse compatibility shim — no extra configuration.

When you Ctrl-C the helper, it kills the audio ffmpeg and unloads the null sink so
your audio device list goes back to normal.

## HTTPS (--insecure)

Lenscast's built-in HTTPS toggle issues a self-signed cert. ffmpeg refuses
self-signed certificates by default; `--insecure` passes `-tls_verify 0` so it
accepts the cert. The traffic is still encrypted — you're just opting out of CA
verification (which can't work for a self-signed cert anyway).

```bash
lenscast-virtualcam -a https://192.168.1.42:4747/video --insecure
```

## GUI wrapper

`lenscast-virtualcam-gui` is a single-file Python/GTK3 wrapper around the bash
script — useful if you'd rather not remember CLI flags. It gives you:

- A URL field with a **Find on LAN** button (mDNS / Bonjour scan via `avahi-browse`).
- A loopback-device dropdown, auto-detected from `v4l2-ctl --list-devices`.
- Checkboxes for **audio forwarding** (`-a`) and **HTTPS** (`--insecure`).
- Start / Stop with live log output.
- Settings persist to `~/.config/lenscast/gui.json` so the window remembers your
  last setup.

Run it from the repo:

```bash
./pc/lenscast-virtualcam-gui
```

Or symlink it onto your `$PATH` next to the CLI script:

```bash
sudo ln -s "$PWD/pc/lenscast-virtualcam-gui" /usr/local/bin/lenscast-virtualcam-gui
```

Dependencies: Python 3, **PyGObject** + **GTK 3** (preinstalled on most desktop
distros that ship GNOME, KDE, XFCE, MATE, or Cinnamon). Optional: `avahi-browse`
for the **Find on LAN** button.

## How it works

```
phone (Lenscast MJPEG server)
   │  http://phone-ip:4747/video
   ▼
ffmpeg -f mjpeg -i URL  -c:v rawvideo -pix_fmt yuv420p  -f v4l2 /dev/video10
   │
   ▼
v4l2loopback device  ←  Zoom / Chrome / OBS / etc. open it like any webcam
```

The MJPEG stream is decoded on the PC; the decoded frames are written to the
v4l2loopback device as raw `yuv420p`. CPU cost is whatever JPEG decoding costs at
your chosen Lenscast resolution and framerate — at 1080p30 expect a few percent of
one core.

## PipeWire-based apps (kamoso, Chrome/Firefox WebRTC, Qt6)

Apps that talk to `/dev/video*` directly — OBS, cheese, guvcview, mpv, ffplay —
see the loopback immediately. Apps that go through PipeWire don't, because
udev tags v4l2loopback devices as output-only at creation. There's a one-line
udev rule that fixes it; the rule and instructions live in
[PipeWire.md](PipeWire.md).

## Limitations

- **Linux only.** Windows DirectShow and macOS CoreMediaIO virtual cameras need
  signed/notarized drivers; the path forward there is "use OBS's built-in Virtual
  Camera" — point OBS at the Lenscast MJPEG URL, then Tools → Start Virtual Camera.
- **MJPEG only.** This helper does not consume the RTSP stream; for low-latency
  webcam use, MJPEG is the right choice anyway (ffplay shows ~1 frame of latency
  in practice).
- **Audio (-a) requires PulseAudio or PipeWire-pulse.** Pure ALSA / JACK setups
  aren't covered; on those, just pipe `/audio` into your favourite audio chain
  manually (`ffplay /audio` works as a quick test).
- **One loopback device, one source.** Running two instances against the same
  `/dev/videoN` will fight. Use different `video_nr` values if you want multiple
  Lenscast phones simultaneously.

## Troubleshooting

**"$device does not exist"** — the kernel module isn't loaded. Run the `modprobe`
command from step 2 above.

**Zoom/Chrome don't see the device** — you forgot `exclusive_caps=1` when loading
the module. Unload (`sudo modprobe -r v4l2loopback`) and reload with the flag.

**Preview is black** — the phone isn't streaming, or the URL is wrong. Open the
URL in a browser first; you should see a tiny Lenscast landing page with the live
feed embedded.

**Choppy / dropped frames** — Wi-Fi is the usual culprit. Try `--fps 24` to give
the network some slack, or use USB tethering (`adb reverse tcp:4747 tcp:4747` then
`lenscast-virtualcam http://127.0.0.1:4747/video`).
