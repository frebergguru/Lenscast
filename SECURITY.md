# Security policy

Thanks for taking the time to look at Lenscast's security posture.

## Supported versions

This is a small hobby project. Only the **latest release** receives security fixes —
older builds are not patched. See [Releases](https://github.com/frebergguru/Lenscast/releases)
for the current version. The in-app Settings sheet also shows the running version.

| Version  | Supported          |
|----------|--------------------|
| 1.0.2    | :white_check_mark: |
| < 1.0.2  | :x:                |

## Reporting a vulnerability

**Please do not open public GitHub issues for security problems.** Use one of
the private channels below so the issue can be fixed before it's broadly visible.

Preferred: **GitHub private security advisories** at
<https://github.com/frebergguru/Lenscast/security/advisories/new>. This gives us
a private discussion thread tied to the repo with an embargo timeline.

Please include:

- The Lenscast version you're testing (`Settings sheet → bottom of the sheet`,
  or `adb shell dumpsys package guru.freberg.lenscast | grep versionName`).
- Phone make / model and Android version.
- A reproduction recipe — what URL you hit, what payload, what you observed.
- Impact: what an attacker can do (read frames? execute code? drain battery?).
- Whether the attacker needs to be on the same LAN, USB-attached, or remote.

You can expect:

- An acknowledgement within **a few days** (hobby project, not a SLA — but I do read
  every advisory).
- A fix or a "won't fix with rationale" response within **30 days** for things in
  scope.
- A credit in the release notes if you'd like one (anonymous is fine too).

## What's in scope

- Anything that lets an attacker who is **not** authorised on the local network
  view, modify, or replay the camera/microphone feed.
- Any way for a network attacker to crash the streaming service, exhaust memory,
  or pivot from the streaming server into the rest of the device.
- Path-traversal / injection / RCE in the MJPEG, RTSP, or HTTP landing-page
  endpoints.
- Privilege issues with the foreground service, notification, or wake lock.
- Issues in `pc/lenscast-virtualcam` — shell injection through the URL argument,
  unsafe handling of `/dev/video*` paths, escalation via the udev rule, etc.

## What is **not** in scope (these are known design tradeoffs)

- **The MJPEG and RTSP streams are unauthenticated and unencrypted.** Anyone on
  the same Wi-Fi network can reach `http://<phone-ip>:4747/video` or the RTSP
  port and view the feed. This is the *intended* behaviour for the OBS-on-the-LAN
  use case Lenscast is designed for. If you need authentication, run Lenscast on
  a VLAN or behind a reverse proxy that adds it.
- **Anyone on the LAN can hit `/shot.jpg`.** Same reason.
- **The MJPEG `/` landing page returns a simple HTML preview.** Not a vuln; it's
  there on purpose so users can sanity-check the feed in a browser.
- **The PC helper's udev rule tags every v4l2loopback device as `:capture:`.**
  That's the whole point of the rule — PipeWire apps can then enumerate the
  loopback. The rule applies to all v4l2loopback devices system-wide; if that's
  undesirable, the rule is opt-in (`pc/README.md` and `pc/PipeWire.md` are
  explicit about this).
- **Lenscast asks for `RECORD_AUDIO` when RTSP audio is enabled.** Not a vuln;
  it's the documented behaviour and can be toggled off in the Settings sheet.
- **Sideloaded APKs are signed with the debug keystore.** Lenscast is GitHub-only
  for now — not a Play Store app — and the debug keystore is the standard
  hobby-project signing convention. If a real production keystore is ever needed
  (e.g. for Play Store), it would be a separate config swap.

## Notes for security researchers

- Reports for "the LAN stream has no auth" will be closed with a pointer to this
  document. Auth/TLS is a deliberate non-feature for v1; please file it as a
  **feature request** in a public issue if you'd like to advocate for it.
- The `pc/` directory is shell scripts + a udev rule, not bundled into the APK
  build. If you're auditing it, run `shellcheck pc/lenscast-virtualcam` first —
  any failures there are bugs.
- The project is GPL v3. If you fork to ship a patched version, please disclose
  the patch in your fork's release notes too.

## Out-of-band contact

If for some reason you can't use GitHub Security Advisories (e.g. you're
embargoed against a third party), open a regular GitHub issue saying *only*
"I have a security report, please contact me" with no details. I'll reach out
through your GitHub-visible contact channel to set up a secure conversation.
