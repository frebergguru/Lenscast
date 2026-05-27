# Using Lenscast with OBS Studio

Lenscast exposes the phone's camera as an MJPEG-over-HTTP stream. OBS consumes it through
its built-in Media Source — no custom OBS plugin and no PC-side helper app required.

## Flow

1. On the phone, open Lenscast.
2. Grant camera + notifications permission on first launch.
3. Tap **Start streaming**. The connection card on the home screen shows the URLs that
   work for your current network state.
4. In OBS: **Sources → +** → **Media Source** → name it → OK.
5. Uncheck **Local File**, paste the URL into **Input**, click OK.
6. Optionally enable **Reconnect** with a 1-second delay so OBS recovers cleanly if the
   phone briefly drops off the network (e.g., when you switch the camera on the phone).

## Wi-Fi

The card shows your phone's current LAN IP. Example:

```
http://192.168.1.42:4747/video
```

Phone and PC must be on the same Wi-Fi network. If the URL doesn't load, check that the
LAN doesn't have client isolation enabled (common on guest networks).

## USB via `adb reverse`

When you'd rather not depend on Wi-Fi:

1. Connect the phone via USB with USB debugging enabled.
2. On the PC, set up an `adb forward` so the PC can reach the phone's RTSP/MJPEG port:
   ```
   adb forward tcp:4747 tcp:4747   # MJPEG
   adb forward tcp:5540 tcp:5540   # RTSP
   ```
3. In OBS / VLC / ffplay, use `http://localhost:4747/video` or `rtsp://localhost:5540/`.

> **Note:** Use `adb forward`, not `adb reverse`. `forward` tunnels **PC → phone**
> (PC opens a local port that maps to the phone), which is what we want here.
> `reverse` does the opposite direction (phone → PC).

The forward mapping does **not** survive USB unplug or `adb kill-server` — re-run
it whenever you reconnect.

## Source type: Media Source, not VLC Source

On Linux OBS the VLC Source backend handles `multipart/x-mixed-replace` (MJPEG) streams
poorly. The FFmpeg-backed **Media Source** is reliable. On Windows OBS both work, but
Media Source is still the recommended choice.

## Sanity check in a browser

Before involving OBS, open the URL in a desktop browser. Lenscast serves a small HTML
landing page at `/` with a live `<img src="/video">` preview. If that renders, the
network and the stream are healthy.

Snapshot endpoint: `/shot.jpg` returns a single JPEG.

## Port

Default 4747 (same as DroidCam, for muscle-memory). Configurable in the Settings sheet if
you have a port conflict on the LAN.

## Troubleshooting

| Symptom                                | Likely cause                                          |
|----------------------------------------|-------------------------------------------------------|
| OBS shows "Connecting" then black      | URL typo, or PC and phone on different networks       |
| Browser loads `/` but `/video` stalls  | OBS or VLC competing for the stream — only one client at a time on some firmware |
| Feed sideways                          | If you find an orientation bug, file it — frames are pre-rotated server-side and should be upright |
| Feed freezes after switching the lens  | Brief CameraX rebind — OBS reconnect delay covers it; if it sticks, stop + start streaming |
| Phone wakes up showing "OBSCam"        | You're running the old install; uninstall `dev.obscam.debug` |
