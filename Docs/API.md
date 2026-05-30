# Lenscast REST API

A JSON-over-HTTP control surface for building your own control app or plugin — anything from
a Stream Deck button to an OBS dock to a shell script. It exposes the same actions as the
built-in web control panel (start/stop, lens, torch, zoom, exposure, resolution/fps/protocol,
the full settings surface, presets, SFTP), but as a clean machine API with JSON bodies and
real HTTP status codes.

It runs on **its own port** (default **8088**), independent of the MJPEG / RTSP / SRT / RIST
media ports and the web-control port, for the entire lifetime of the streaming service — so a
plugin can start a stream cold, not just tune one that's already running.

> Everything below goes through the same internal control bridge as the web panel, so **every
> safety gate applies identically** — see [Blocking gates](#blocking-gates).

## Enabling the API

On the phone: **Settings → REST API**.

1. Toggle **Enable the REST API**. A 256-bit token is generated automatically the moment you
   enable it.
2. Note the **API port** (default 8088).
3. Tap **Copy token**. This token is shown **only here** — it is stored encrypted on the
   device and is never sent back over the network. **Regenerate** mints a new one and instantly
   invalidates the old.

You can also flip it on remotely from the web panel (or another API client) via the generic
settings update — set `apiEnabled=true`, which auto-provisions a token — but you'll then need
local access to the phone to read that token out of the Settings sheet, since it is never
returned over the wire.

The API is **fail-closed**: the server only binds when it is both enabled *and* has a token.

## Base URL & authentication

```
http://<phone-ip>:8088/api/v1
```

Every request must carry the bearer token:

```
Authorization: Bearer <token>
```

A missing or wrong token returns `401`. There are no other credentials and no cookies, which
is also why no CSRF/Origin check is needed and CORS is open (`Access-Control-Allow-Origin: *`)
— a cross-origin caller still can't do anything without the token. That makes it safe to call
the API directly from a browser-based plugin.

### Use HTTPS for anything beyond a trusted LAN

If you enable **HTTPS** in Settings (Security section), the API serves TLS on the same port
with the app's self-signed certificate — the bearer token then never crosses the network in
the clear. Over plain HTTP the token is only as private as your LAN. The fingerprint to pin is
shown under the HTTPS toggle and in `GET /api/v1/status` (`tlsFingerprint`).

## Quick start

```bash
TOKEN="paste-your-token"
HOST="http://192.168.1.42:8088/api/v1"

# What's the camera doing right now?
curl -s -H "Authorization: Bearer $TOKEN" $HOST/status | jq .

# Start streaming with the saved settings
curl -s -X POST -H "Authorization: Bearer $TOKEN" $HOST/stream/start

# Switch to the front camera and turn on the torch
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"lens":"front"}' $HOST/camera/lens
curl -s -X POST -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -d '{"on":true}' $HOST/camera/torch

# Stop
curl -s -X POST -H "Authorization: Bearer $TOKEN" $HOST/stream/stop
```

## Response shape

Action endpoints return `{"ok": true}` on success, or an error envelope:

```json
{ "ok": false, "error": "stream_locked", "message": "stop the stream before changing protocol" }
```

`error` is a stable machine code; `message` is human-readable and may change. The HTTP status
code carries the category (see [status codes](#status-codes)). Read-only endpoints
(`/status`, `/settings`, `/sftp`) return their payload directly.

## Endpoints

| Method & path | Body | What it does |
|---|---|---|
| `GET /api/v1` | — | Discovery: name, version, endpoint list |
| `GET /api/v1/status` | — | Full live state (see below) |
| `GET /api/v1/settings` | — | Current settings as JSON (secrets omitted) |
| `PUT /api/v1/settings` | settings JSON | Replace **all** settings (import). Rejected mid-stream |
| `PATCH /api/v1/settings` | `{key: value, …}` | Update individual settings; per-key result |
| `POST /api/v1/stream/start` | — | Start streaming with the saved settings |
| `POST /api/v1/stream/stop` | — | Stop streaming |
| `POST /api/v1/camera/lens` | `{"lens":"back"\|"front"}` *(optional)* | Set lens; omit body to toggle |
| `POST /api/v1/camera/torch` | `{"on":bool}` *(optional)* | Set torch; omit body to toggle |
| `POST /api/v1/camera/mirror` | `{"on":bool}` *(optional)* | Set mirror; omit body to toggle |
| `POST /api/v1/camera/af` | `{"on":bool}` *(optional)* | Set continuous-AF; omit body to toggle |
| `POST /api/v1/camera/zoom` | `{"factor":1.25}` | Multiply current zoom by `factor` (relative) |
| `POST /api/v1/camera/exposure` | `{"ev":0}` or `{"delta":1}` | Absolute EV, or relative nudge |
| `POST /api/v1/camera/resolution` | `{"value":"720p"}` | Set resolution by label (clamped to caps) |
| `POST /api/v1/camera/fps` | `{"value":30}` | Set target fps (clamped to caps) |
| `POST /api/v1/camera/protocol` | `{"value":"rtsp"}` | Set protocol. Rejected mid-stream |
| `POST /api/v1/camera/quality` | `{"value":80}` | JPEG quality 10–95 (MJPEG/sidecar) |
| `POST /api/v1/camera/snapshot` | — | Save the latest JPEG to the gallery |
| `POST /api/v1/clients/kick` | `{"remote":"host:port"}` | Drop a connected streaming client |
| `GET /api/v1/sftp` | — | SFTP upload-queue status |
| `POST /api/v1/sftp/retry` | — | Re-queue the last recording for upload |
| `POST /api/v1/presets/save` | `{"name":"…"}` | Save current shape (protocol/res/fps/lens). Idle-only |
| `POST /api/v1/presets/apply` | `{"name":"…"}` | Apply a preset. Idle-only |
| `POST /api/v1/presets/delete` | `{"name":"…"}` | Delete a preset |

### `GET /api/v1/status`

The canonical state object the panel polls ~1 Hz. Includes (non-exhaustive): `state`
(`idle`/`starting`/`streaming`/`error`), `protocol`, `lens`, `torch`, `resolution`,
`availableResolutions`, `availableFps`, `fps` (live), `targetFps`, `clients`, `clientList`,
`txKbps`, `dropped`, `rttMs`, every image control, `supportsManualSensor`/`isoRange`/
`shutterRangeUs` (device capabilities), audio fields, all ports, the SRT/RIST blocks, presets,
and `tlsFingerprint`.

API-specific fields:

```json
{ "apiEnabled": true, "apiPort": 8088, "apiTokenSet": true }
```

The token value itself is **never** present — `apiTokenSet` only tells you whether one exists.
Likewise SRT/RIST passphrases are always echoed as `""`.

### `PATCH /api/v1/settings`

Send a flat object of setting keys to values. Each key is applied independently through the
same path the web panel uses, so each one is gated individually. The response reports exactly
what happened:

```bash
curl -s -X PATCH -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"whiteBalance":"daylight","exposureEv":-1,"srtPort":9999}' $HOST/settings
```

```json
{ "ok": false, "applied": ["whiteBalance","exposureEv"], "rejected": { "srtPort": "locked while streaming" } }
```

`ok` is true only when nothing was rejected. A key is rejected when it's unknown, the value is
invalid/out of range, or it's [stream-locked](#blocking-gates) and a stream is running.

Keys are the camelCase settings names. Values are plain JSON; enums are their lowercased name.
Common keys:

| Key | Values |
|---|---|
| `lens` | `back` \| `front` |
| `mirror`, `continuousAf`, `manualFocus`, `manualExposure` | `true` \| `false` |
| `exposureEv` | integer |
| `whiteBalance` | `auto` `incandescent` `fluorescent` `daylight` `cloudy` `shade` |
| `antiBanding` | `auto` `hz50` `hz60` `off` |
| `effect` | `none` `mono` `negative` `sepia` `aqua` `solarize` `posterize` `blackboard` `whiteboard` |
| `sceneMode` | `disabled` `action` `portrait` `landscape` `night` `sports` `theatre` `fireworks` `beach` `snow` `sunset` |
| `iso`, `shutterUs`, `manualFocusCentidiopters` | integer |
| `watermarkText`, `languageTag` | string |
| `audioGainDb` | −24…24 |
| `rotationLock` | `auto` `portrait` `landscape_left` `landscape_right` `portrait_upside_down` |
| `callBehavior` | `ignore` `mute_stream` `drop_call` |
| `srtMode`, `ristMode` | `caller` \| `listener` |
| `ristProfile` | `simple` \| `main` |
| `apiEnabled` | `true` \| `false` |

> For `resolution`, `fps`, and `protocol`, prefer the dedicated `POST /camera/*` endpoints —
> they clamp to what the current lens/protocol actually supports, which a raw settings write
> does not.

## Blocking gates

These are not API-specific — they're the app's core rules, enforced in one place, so the API,
the web panel, and the on-device UI all behave the same.

**Rejected while a stream is running** (`state` is `streaming` or `starting`) → `409`:

- `POST /camera/protocol`, `PUT /settings` (import), `POST /presets/save`, `POST /presets/apply`.
- `PATCH /settings` for any of these **stream-locked keys**: `httpsEnabled`, `audioEnabled`,
  `micSource`, `noiseSuppress`, `echoCancel`, `rtspBitrateKbps`, `recordLocally`, `mjpegPort`,
  `rtspPort`, `streamUsername`, `streamPassword`, and **all SRT/RIST transport keys**
  (`srtMode`, `srtHost`, `srtPort`, `srtPassphrase`, `srtLatencyMs`, `srtStreamId`,
  `ristMode`, `ristHost`, `ristPort`, `ristProfile`, `ristEncryptionPassphrase`,
  `ristBufferMs`, `ristAesKeyBits`).

Everything else — image controls, watermark, audio gain, JPEG quality, ports for other
servers, SFTP, the API's own settings — is editable mid-stream.

**Always enforced (any state):**

- `POST /stream/start` returns `409` if a stream is already up.
- **RIST Listener requires the Main profile.** Setting `ristMode=listener` while the profile is
  Simple (or `ristProfile=simple` while the mode is Listener) silently coerces the mode back to
  `caller`. (librist's caller-receiver can't parent a Simple-profile listener.) The write
  succeeds; read `/status` back to see the effective pair.
- `streamUsername` can't be blanked — it coerces back to `Lenscast` so you can't lock yourself
  out of an auth-gated media URL.
- Ports are validated (1024–65535 for the HTTP servers; SRT/RIST/SFTP have their own ranges);
  out-of-range values are rejected.

**Runtime preconditions** (these don't fail the HTTP call — they surface in `/status`):

- If the **camera permission** isn't granted, a remote `start` will move `state` to `error`
  with an `errorMessage` — there's no way to grant the permission remotely, so check `/status`
  after starting. Same for SRT/RIST **caller** mode with no host set, or a fps/resolution combo
  the device can't do.
- With **audio** enabled but `RECORD_AUDIO` not granted, the stream still starts, video-only.

## Status codes

| Code | Meaning |
|---|---|
| `200` | Success (action ran, or payload returned) |
| `400` | Bad request — malformed JSON, unknown key, or invalid/out-of-range value |
| `401` | Missing or invalid bearer token |
| `404` | Unknown endpoint, or no matching client/preset |
| `405` | Wrong method for that endpoint |
| `409` | Conflict — blocked by a gate (already streaming, stream-locked, idle-only, no frame) |
| `413` | Body larger than 256 KiB |
| `503` | API has no token configured (shouldn't happen — fail-closed) |

## A minimal plugin

```python
import requests

class Lenscast:
    def __init__(self, host, token):
        self.base = f"http://{host}:8088/api/v1"
        self.s = requests.Session()
        self.s.headers["Authorization"] = f"Bearer {token}"

    def status(self):  return self.s.get(f"{self.base}/status").json()
    def start(self):   return self.s.post(f"{self.base}/stream/start").json()
    def stop(self):    return self.s.post(f"{self.base}/stream/stop").json()
    def lens(self, which=None):
        body = {"lens": which} if which else {}
        return self.s.post(f"{self.base}/camera/lens", json=body).json()
    def set(self, **kw):
        return self.s.patch(f"{self.base}/settings", json=kw).json()

cam = Lenscast("192.168.1.42", "paste-your-token")
cam.set(whiteBalance="daylight", exposureEv=-1)
cam.start()
print(cam.status()["state"])
```

## Versioning

The path is versioned (`/api/v1`). New fields may be **added** to responses without a version
bump, so parse leniently and ignore unknown keys. Breaking changes would ship under `/api/v2`.

## See also

- [Architecture.md](Architecture.md) — where the API server sits (`streaming/RestApiServer.kt`)
- [OBS-Integration.md](OBS-Integration.md) — consuming the actual video once you've started it
- The web control panel (default port 8080) — the same actions, for humans
