# tools/

Developer / CI utilities. Not part of the APK build and not needed by end users.

## `lenscast-rtsp-probe`

A standalone RTSP conformance test client for the `RtspServer` path. Pure Python 3
stdlib — no dependencies.

It exists because **no shipping RTSP player speaks RTSP 2.0** (VLC and OBS use live555,
ffmpeg has its own demuxer — all RTSP 1.0). So the server's RTSP 2.0 (RFC 7826) path
can't be exercised by a real client. This probe sends genuine `RTSP/2.0` requests and is
the only way to verify it; it covers the 1.0 (RFC 2326) path too.

### What it checks

- **Full session lifecycle** for 1.0 and 2.0: `OPTIONS` → `DESCRIBE` → `SETUP` → `PLAY`
  → `PAUSE` → `TEARDOWN`, over TCP-interleaved transport.
- **Media actually flows** on `PLAY` and **stops** on `PAUSE` (regression guard for the
  PAUSE no-op bug).
- **Version-specific headers**: 2.0 gets quoted `RTP-Info` + `ssrc`, `Media-Properties`,
  `Media-Range`, `Accept-Ranges`, `Range: npt=now-`; 1.0 gets the bare forms and **none**
  of the 2.0-only headers leak in.
- **Negotiation & errors**: feature-tag filtering (`Require: play.basic` accepted, unknown
  tags → `551`), `Supported` echo, `454` (unknown Session), `457` (non-NPT range), `406`
  (Accept excludes SDP), `505` (unknown version), and `Timestamp` / `Pipelined-Requests` /
  `Connection: close` echoes.

### Usage

An RTSP stream must be running on the phone (set protocol to RTSP and Start, or
`POST /control/start` on the web-control port). Then point the probe at it — directly via
the phone's LAN IP, or over USB with `adb forward`:

```bash
adb forward tcp:5540 tcp:5540
tools/lenscast-rtsp-probe --host 127.0.0.1 --port 5540
```

Options: `--version 1.0|2.0|both` (default `both`), `--media-secs N` (PLAY/PAUSE measure
window, default 1.2), `--no-color`. Exits `0` if every check passes, `1` on any failure,
`2` if the server is unreachable — so it drops straight into CI.

```
$ tools/lenscast-rtsp-probe --host 127.0.0.1 --port 5540
== RTSP/1.0 session ==
  PASS  [RTSP/1.0] PLAY 200
  ...
== RTSP/2.0 session ==
  PASS  [RTSP/2.0] RTP-Info is 2.0 form (quoted url + ssrc)
  ...
43/43 checks passed, 0 failed
```
