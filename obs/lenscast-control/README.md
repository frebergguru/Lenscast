# Lenscast Control — OBS Studio plugin

A native OBS Studio plugin that adds a **Lenscast Control** dock: start/stop streaming,
switch camera, torch, zoom, snapshot and pick the resolution on a [Lenscast](../../README.md)
phone — all from inside OBS over the phone's REST API — plus a one-click **Add / refresh Media
Source** that points OBS at the phone's stream URL, and bindable **hotkeys**.

OBS already plays the video itself via a Media Source; this plugin is the *control* layer on
top of that, driving the same REST API documented in [`Docs/API.md`](../../Docs/API.md).

## What it does

- **Control panel** — Start/Stop, Switch camera, Torch, Snapshot, Zoom ±, Resolution, with a
  live status line (state · protocol · resolution) polled every 2 s.
- **Auto Media Source** — creates (or refreshes) an OBS Media Source named `Lenscast` in the
  current scene, pointing at the phone's current stream URL. Supports **MJPEG, RTSP and SRT**
  (RIST/WebRTC can't be played by a plain Media Source).
- **Hotkeys** — Start/stop, Switch camera, Toggle torch, Snapshot. Bind them under
  **Settings → Hotkeys** (search "Lenscast").

## Requirements

- OBS Studio **28+** (Qt6 frontend). Built and verified against OBS **32.1.2**.
- On the phone: enable **Settings → REST API**, then **Copy token**. The dock needs the
  phone's IP, the API port (default **8088**) and that token.

## Build (Linux)

Needs the OBS dev headers (`libobs` + `obs-frontend-api` pkg-config), Qt6 Widgets + Network,
CMake and a C++17 compiler.

```bash
cd obs/lenscast-control
cmake -B build -G Ninja -DCMAKE_BUILD_TYPE=Release
cmake --build build
cmake --install build      # → ~/.config/obs-studio/plugins/lenscast-control/
```

Restart OBS. You should see `[lenscast-control] loaded` in the OBS log and a **Lenscast
Control** entry under the **Docks** menu.

> Arch/Manjaro note: the packaged `libobs.pc` reports `-I/usr/include` (which pkg-config
> strips as a default path) while the headers live in `/usr/include/obs`, so the CMake adds
> `${OBS_INCLUDEDIR}/obs` explicitly. If your distro installs the headers elsewhere, pass
> `-DOBS_INCLUDEDIR=/path/to/include`.

To install somewhere else, set `-DOBS_PLUGIN_DESTINATION=/path/to/plugin-dir`.

### Windows / macOS

The `CMakeLists.txt` is standard and platform-neutral, but Windows/macOS builds need the OBS
plugin SDK / dependencies set up the usual way (see the upstream
[obs-plugintemplate](https://github.com/obsproject/obs-plugintemplate)). Only the Linux build
is verified here.

## Use

1. **Docks → Lenscast Control** (enable it if hidden).
2. Enter the **Phone IP**, **API port** (8088) and **Token**, then **Connect / Save** — the
   status line should show `State: idle · mjpeg …`.
3. Drive the camera with the buttons, or **Add / refresh OBS Media Source** to drop the live
   feed into your current scene.
4. Optionally bind the hotkeys in **Settings → Hotkeys**.

Connection details are saved to `~/.config/obs-studio/plugins/lenscast-control/config/`
(the token is stored locally in that file — treat it like any saved credential).

## Layout

```
src/plugin-main.cpp     module entry, dock + hotkey registration
src/lenscast-client.*   async REST client (Qt Network, bearer-token)
src/lenscast-dock.*     the dock UI, status polling, Media Source creation, config
data/locale/en-US.ini   module description
CMakeLists.txt          build
```
