# Testing the streaming protocols from a Linux host

This is a hands-on guide for verifying every Lenscast egress protocol (MJPEG, RTSP, SRT,
RIST, WebRTC) end-to-end from a desktop, driving the phone over its web-control API and
pulling the streams with `ffmpeg`/`ffprobe`/`ffplay` and the librist CLI tools. Every
command below is copy-pasteable; substitute the two addresses in the *Variables* block.

The phone is controlled headlessly through the **web control panel** (`WebControlServer`,
port 8080): `curl` POSTs carry no `Origin` header, so the CSRF guard (`isSameOrigin`) lets
them through. No screen taps are required except for the WebRTC browser playback check.

## Variables

```bash
PHONE_IP=192.168.1.179     # phone's Wi-Fi IP — see "Find the phone IP" below
HOST_IP=192.168.1.117      # this desktop's LAN IP (must share the LAN with the phone for UDP)
WEB=http://$PHONE_IP:8080  # web control panel
```

UDP protocols (SRT, RIST) require the host and phone to be on the **same LAN** —
`adb forward` only tunnels TCP and cannot carry them. MJPEG and RTSP are TCP and also work
over `adb forward` (USB) if you prefer; see *USB alternative*.

## Prerequisites

Host packages: `ffmpeg` (provides `ffprobe`/`ffplay`), `librist` (provides `ristreceiver`/
`ristsender`), `android-tools` (`adb`), `curl`.

```bash
for t in adb curl ffprobe ffplay ffmpeg ristreceiver ristsender; do command -v "$t" || echo "MISSING: $t"; done
```

## One-time setup

```bash
# 1. Phone connected and authorised
adb devices                      # must list a device in "device" state

# 2. Grant the runtime permissions (so the camera/mic work without UI taps)
for p in CAMERA RECORD_AUDIO POST_NOTIFICATIONS; do
  adb shell pm grant guru.freberg.lenscast android.permission.$p
done

# 3. Launch the app (also required after a force-stop: Android forbids starting a
#    foreground service from the background, so the activity must be in front first)
adb shell monkey -p guru.freberg.lenscast -c android.intent.category.LAUNCHER 1
sleep 4

# 4. Confirm the web control server is up
curl -s -m5 $WEB/status -o /dev/null -w "web=%{http_code}\n"   # expect web=200
```

### Find the phone IP

```bash
adb shell ip addr show wlan0 | grep -oE 'inet [0-9.]+'        # -> inet 192.168.1.179
```

### Host LAN IP

```bash
ip -4 addr show | grep -oE 'inet 192[0-9.]+' | head -1        # -> the HOST_IP value
```

## Driving the app over the web API

```bash
# Status (pretty-print the JSON if you have jq)
curl -s $WEB/status

# Set the active protocol (only while idle): mjpeg | rtsp | srt | rist | webrtc
curl -s -X POST "$WEB/control/protocol?v=rtsp"

# Change a setting (only while idle for stream-locked keys): key + v in the QUERY STRING
curl -s -X POST "$WEB/control/setting?key=srtMode&v=caller"

# Start / stop
curl -s -X POST "$WEB/control/start"
curl -s -X POST "$WEB/control/stop"
```

> **Important — the protocol and most settings are STREAM-LOCKED.** `/control/protocol` and
> stream-affecting `/control/setting` keys (`srtMode`, `srtHost`, `srtPort`, `ristMode`,
> `ristProfile`, `ristPort`, ports, audio, …) are **rejected with HTTP 503 while a stream is
> running** — the change is silently ignored and the *old* protocol keeps streaming (so its
> port stays bound and the new protocol's port never binds). Always configure in this order:
>
> 1. **stop** (and wait ~2 s for the port to release),
> 2. set protocol + settings **while idle**,
> 3. **start** (and wait ~4 s for the new port to bind).
>
> Check the `[%{http_code}]` on each POST — a `503` means you tried to change a locked
> setting mid-stream. Confirm the port actually came up after start:
>
> ```bash
> adb shell 'ss -tln' | grep -E ':4747|:5540'    # TCP: MJPEG / RTSP
> adb shell 'ss -uln' | grep -E ':9710|:5004'    # UDP: SRT / RIST
> ```

Reusable helpers used throughout this doc (note `idle` runs **before** configuring, `go`
runs after):

```bash
idle()  { curl -s -X POST "$WEB/control/stop" >/dev/null; sleep 2; }   # stop, then settings are unlocked
go()    { curl -s -X POST "$WEB/control/start" >/dev/null; sleep 4; }  # start and let the port bind
state() { curl -s "$WEB/status" | grep -oE '"state":"[^"]*","protocol":"[^"]*"'; }
```

---

## MJPEG (port 4747)

```bash
idle                                                          # stop first — protocol is stream-locked
curl -s -X POST "$WEB/control/protocol?v=mjpeg"
go
state                                                         # streaming / mjpeg
adb shell 'ss -tln' | grep :4747                              # must be LISTENing

# Probe the stream
ffprobe -v error -i "http://$PHONE_IP:4747/video" \
  -show_entries stream=codec_name,width,height -of default=noprint_wrappers=1
# Expect: codec_name=mjpeg, width/height = the configured resolution (e.g. 720x1280)

# Grab one frame to an image
ffmpeg -y -i "http://$PHONE_IP:4747/video" -frames:v 1 /tmp/mjpeg.jpg && echo OK
```

Live view: `ffplay -i "http://$PHONE_IP:4747/video"`.

## RTSP (port 5540, H.264 + AAC)

```bash
idle                                                          # stop first — protocol is stream-locked
curl -s -X POST "$WEB/control/protocol?v=rtsp"
go
adb shell 'ss -tln' | grep :5540                              # must be LISTENing

ffprobe -v error -rtsp_transport tcp -i "rtsp://$PHONE_IP:5540/video" \
  -show_entries stream=codec_type,codec_name,width,height -of default=noprint_wrappers=1
# Expect: video/h264 (720x1280) and audio/aac if audio is enabled

# Live view (TCP-interleaved transport, most reliable):
ffplay -rtsp_transport tcp -i "rtsp://$PHONE_IP:5540/video"
```

### Regression check — RTSP client-teardown cleanup

A client that drops its TCP connection without sending `TEARDOWN` must be removed from the
server's sink list (it must not leak). `clients` in `/status` is the live sink count.

```bash
clients() { curl -s "$WEB/status" | grep -oE '"clients":[0-9]+'; }
clients                                                       # baseline: "clients":0
for i in 1 2 3; do
  ffmpeg -nostdin -loglevel quiet -rtsp_transport tcp -i "rtsp://$PHONE_IP:5540/video" -f null - & PID=$!
  sleep 4; echo "connected: $(clients)"                       # "clients":1
  kill -9 $PID; sleep 3; echo "after kill: $(clients)"        # must return to "clients":0
done
```

The count must return to `0` after every abrupt kill — it must **not** climb 1, 2, 3.

## SRT (port 9710, H.264 + AAC over MPEG-TS)

### Listener mode (phone listens, host dials in) — the default

```bash
idle                                                          # stop first — protocol/settings are stream-locked
curl -s -X POST "$WEB/control/protocol?v=srt"
curl -s -X POST "$WEB/control/setting?key=srtMode&v=listener"
go
adb shell 'ss -uln' | grep :9710                              # must be listening (UNCONN)

ffprobe -v error -i "srt://$PHONE_IP:9710?mode=caller&latency=200" \
  -show_entries stream=codec_type,codec_name,width,height -of default=noprint_wrappers=1
# Expect: video/h264 (720x1280) + audio/aac

# Live view:
ffplay -i "srt://$PHONE_IP:9710?mode=caller&latency=200"
```

### Caller mode (phone dials out to a host listener)

```bash
curl -s -X POST "$WEB/control/stop"; sleep 2
curl -s -X POST "$WEB/control/setting?key=srtMode&v=caller"
curl -s -X POST "$WEB/control/setting?key=srtHost&v=$HOST_IP"
curl -s -X POST "$WEB/control/setting?key=srtPort&v=9720"

# Start a host-side SRT listener, then start the phone so it connects to us:
ffprobe -v error -i "srt://0.0.0.0:9720?mode=listener&latency=200" \
  -show_entries stream=codec_type,codec_name,width,height -of default=noprint_wrappers=1 & PROBE=$!
sleep 1; curl -s -X POST "$WEB/control/start" >/dev/null
wait $PROBE
# Expect: video/h264 + audio/aac
```

## RIST (port 5004, H.264 + AAC over MPEG-TS) — pure-Kotlin, no librist on the phone

`ffmpeg` has no RIST demuxer, so receive with the librist CLI (`ristreceiver`), re-emit as
plain UDP, and read `ristreceiver`'s JSON `receiver-stats` — `"quality":100` with
`"lost":0` is the pass condition. (`ffprobe` on the re-emitted UDP may warn about mid-GOP
`PPS` until the first keyframe; that's a probe artifact, not a stream fault.)

> Kill stray receivers with `pkill -x ristreceiver` (exact name). Do **not** use
> `pkill -f ristreceiver` — `-f` matches your own shell's command line and kills it.

### Simple profile, phone as caller (host = listener)

```bash
idle                                                          # stop first — protocol/settings are stream-locked
curl -s -X POST "$WEB/control/protocol?v=rist"
curl -s -X POST "$WEB/control/setting?key=ristMode&v=caller"
curl -s -X POST "$WEB/control/setting?key=ristHost&v=$HOST_IP"
curl -s -X POST "$WEB/control/setting?key=ristProfile&v=simple"
go

# '@' = listen. Run for ~10 s and watch the stats.
timeout 12 ristreceiver -p 0 -i "rist://@0.0.0.0:5004" -o "udp://127.0.0.1:7777" -v 5 2>&1 \
  | grep -oE '"quality":[0-9]+|"lost":[0-9]+|FLOW #[0-9]+ created'
# Expect: "FLOW # … created", repeated "quality":100 / "lost":0
```

### Main profile, AES-128 encrypted, phone as caller

```bash
curl -s -X POST "$WEB/control/stop"; sleep 2
curl -s -X POST "$WEB/control/setting?key=ristProfile&v=main"
curl -s -X POST "$WEB/control/setting?key=ristEncryptionPassphrase&v=lenscastTest123"
curl -s -X POST "$WEB/control/setting?key=ristAesKeyBits&v=128"
curl -s -X POST "$WEB/control/start"; sleep 4

timeout 12 ristreceiver -p 1 -s lenscastTest123 -e 128 -i "rist://@0.0.0.0:5004" -o "udp://127.0.0.1:7778" -v 5 2>&1 \
  | grep -oE 'Main Profile Mode|FLOW #[0-9]+ created|"quality":[0-9]+|"lost":[0-9]+'
# Expect: "Main Profile Mode", flow created, "quality":100 / "lost":0
# (Clean decode under encryption proves the GRE-seq / AES-CTR IV is not being reused.)
```

### Main profile, AES-128 encrypted, phone as listener (host = caller)

```bash
idle                                                          # stop first — ristMode is stream-locked
curl -s -X POST "$WEB/control/setting?key=ristMode&v=listener"
go
adb shell 'ss -uln' | grep :5004                              # phone listening

# No '@' = caller. Connect to the phone:
timeout 12 ristreceiver -p 1 -s lenscastTest123 -e 128 -i "rist://$PHONE_IP:5004" -o "udp://127.0.0.1:7779" -v 5 2>&1 \
  | grep -oE 'FLOW #[0-9]+ created|"quality":[0-9]+|"lost":[0-9]+'
# Expect: flow created, "quality":100 / "lost":0
```

> Note: RIST **Simple + Listener** is not interoperable with librist's caller-receiver (a
> librist limitation, not a Lenscast bug). Use Main profile for the listener-mode test.

## WebRTC (served on the web-control port 8080, WHEP)

WebRTC playback needs a browser (no simple CLI WHEP player exists).

```bash
idle                                                          # stop first — protocol is stream-locked
curl -s -X POST "$WEB/control/protocol?v=webrtc"
go
```

Open **`http://$PHONE_IP:8080/webrtc/view`** in a browser — the live video should appear.

> Give the camera session a few seconds to open before stopping. `capturer.stopCapture()`
> blocks "waiting for session to open", so a `stop` issued immediately after `start` (before
> the Camera2 session finishes opening) can hang until it does. Normal use is unaffected.

### Regression check — no leaked capture threads across start/stop

Each WebRTC session creates one `SurfaceTextureHelper` (a HandlerThread named
`LenscastWebRtcCapture`); it must be disposed on stop. Count those threads across cycles —
the count must stay at 1 while streaming and drop to 0 after stop, **not** accumulate.

```bash
PID=$(adb shell pidof guru.freberg.lenscast | tr -d '\r')
threads() { adb shell "for t in /proc/$PID/task/*/comm; do cat \$t; done" | grep -c WebRtcC; }
for i in 1 2 3 4; do
  curl -s -X POST "$WEB/control/start" >/dev/null; sleep 3
  echo "cycle $i streaming: $(threads)"          # expect 1 each time (not 1,2,3,4)
  curl -s -X POST "$WEB/control/stop" >/dev/null; sleep 2
done
echo "after stop: $(threads)"                     # expect 0
```

## Screenshots

```bash
adb exec-out screencap -p > /tmp/lenscast.png
```

## USB alternative (TCP protocols only)

Without a shared LAN you can reach MJPEG/RTSP/web over USB:

```bash
for p in 4747 5540 8080; do adb forward tcp:$p tcp:$p; done
# then use localhost instead of $PHONE_IP, e.g. ffprobe ... http://localhost:4747/video
```

SRT and RIST cannot be tested this way (UDP).

## Cleanup — restore defaults

```bash
pkill -x ristreceiver 2>/dev/null
curl -s -X POST "$WEB/control/stop"
for kv in srtMode=listener srtHost= srtPort=9710 ristMode=listener ristHost= \
          ristPort=5004 ristProfile=simple ristEncryptionPassphrase=; do
  curl -s -X POST "$WEB/control/setting?key=${kv%%=*}&v=${kv#*=}" >/dev/null
done
curl -s -X POST "$WEB/control/protocol?v=mjpeg"
adb forward --remove-all 2>/dev/null
```
