# Streaming to a PC over USB

Lenscast's MJPEG, RTSP, SRT, and web-control servers bind on all interfaces, so you can
reach them from a PC over a plain USB cable using `adb forward` — no Wi-Fi needed. Useful
when the LAN is flaky, when you don't trust the network, or when you want the lowest
possible latency.

## Prerequisites

- USB cable with data lines (not "charge-only").
- `adb` on the PC (`pkg install android-tools` on most distros; bundled with Android
  Studio's command-line tools on Windows / macOS).
- USB debugging enabled on the phone (`Settings → System → Developer options → USB
  debugging`).
- The phone authorized for the PC's adb key on first connection.

## One-shot setup

```bash
adb forward tcp:4747 tcp:4747   # MJPEG
adb forward tcp:5540 tcp:5540   # RTSP
adb forward tcp:9710 tcp:9710   # SRT
adb forward tcp:8080 tcp:8080   # web control panel (optional)
```

After that, the streams are reachable on the PC as if they were local:

```
http://127.0.0.1:4747/video      # MJPEG
rtsp://127.0.0.1:5540/lenscast   # RTSP
srt://127.0.0.1:9710             # SRT (listener mode)
http://127.0.0.1:8080/           # web control panel
```

Use those URLs in OBS (Media Source → uncheck "Local File" → paste), VLC (Media → Open
Network Stream), `ffmpeg`, or the bundled
[`lenscast-virtualcam`](../pc/lenscast-virtualcam) helper on Linux.

## Why `adb forward` and not USB tethering?

USB tethering would put the phone and PC on a shared subnet so the regular Wi-Fi URLs
work, but it requires the phone to be acting as a network gateway — that fights with
whatever other connectivity the PC already has and disables the phone's Wi-Fi/data on
some devices. `adb forward` is a userspace TCP forwarder that runs over the existing adb
USB pipe; nothing else on the system notices.

## Custom ports

If you changed the MJPEG or RTSP port in Lenscast's Settings sheet, change the
`adb forward tcp:<port>` to match. The first `tcp:` is the PC port, the second is the
phone port — keeping them equal makes the URLs predictable.

## Tearing it down

`adb forward --remove tcp:4747` removes a single forward. `adb forward --remove-all`
clears everything. The forwards survive the Lenscast app being stopped and restarted,
but disappear when the cable is unplugged or `adb` is killed.

## Compared to Wi-Fi

| Aspect             | Wi-Fi             | USB (`adb forward`) |
|--------------------|-------------------|---------------------|
| Setup              | Type the IP       | Two `adb` commands  |
| Bandwidth ceiling  | LAN speed         | USB 2.0/3.0         |
| Reliability        | LAN-dependent     | Cable-dependent     |
| Phone battery      | Drains            | Charges             |
| Works without LAN  | No                | Yes                 |

The RTSP path uses a small TCP-RTP-over-RTSP-interleaved channel, so it also works fine
through `adb forward`. UDP-only RTSP would not — but that mode isn't enabled by default.
