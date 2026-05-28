package dev.lenscast.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLContext

/**
 * HTTP control-panel server. Runs on its own port (independent of MJPEG / RTSP) for the
 * entire lifetime of [StreamingService], so the user can start, stop, and tune a stream
 * from any browser on the LAN regardless of which protocol is active or whether a stream
 * is running at all.
 *
 * Endpoints:
 *  - `GET  /`                  — adaptive HTML page (see [renderLandingHtml])
 *  - `GET  /status`            — JSON state for the page's poll
 *  - `POST /control/start`     — kicks off the stream with the saved settings
 *  - `POST /control/stop`      — stops it
 *  - `POST /control/lens`      — switch back/front
 *  - `POST /control/torch`     — toggle torch
 *  - `POST /control/snapshot`  — save the latest JPEG (MJPEG-only)
 *  - `POST /control/zoom?dir=in|out`
 *  - `POST /control/ev?dir=up|down`
 *  - `POST /control/mirror`
 *  - `POST /control/af`         — toggle continuous-AF
 *  - `POST /control/quality?v=10..95` — MJPEG only
 *  - `POST /control/resolution?v=720p|...`
 *  - `POST /control/fps?v=30|60|...`
 *
 * The page's JS shows/hides protocol-specific blocks based on the `protocol` field in
 * `/status` — RTSP-only fields don't appear when MJPEG is active and vice versa.
 */
class WebControlServer(
    private val port: Int,
    private val control: MjpegControl,
    /** Phone-side port the MJPEG server listens on. The page builds the URL from this. */
    private val mjpegPort: () -> Int,
    private val rtspPort: () -> Int,
    private val sslContext: SSLContext? = null,
    /** True when MJPEG is also serving HTTPS; the page uses https:// for the preview URL. */
    private val mjpegIsHttps: () -> Boolean = { false },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        acceptJob = scope.launch {
            try {
                val s = if (sslContext != null) {
                    sslContext.serverSocketFactory.createServerSocket().also {
                        it.reuseAddress = true
                        it.bind(InetSocketAddress(port))
                    }
                } else {
                    ServerSocket().also {
                        it.reuseAddress = true
                        it.bind(InetSocketAddress(port))
                    }
                }
                server = s
                val scheme = if (sslContext != null) "https" else "http"
                Log.i(TAG, "Web control $scheme listening on 0.0.0.0:$port")
                while (running && isActive) {
                    val client = try { s.accept() } catch (_: IOException) { break }
                    client.tcpNoDelay = true
                    client.soTimeout = 5_000
                    launch { handle(client) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Web-control accept loop crashed", t)
            }
        }
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Throwable) {}
        server = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
    }

    private suspend fun handle(socket: Socket) = withContext(Dispatchers.IO) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return@withContext
            var contentLength = 0
            // Drain headers, but capture Content-Length so we can read the body when
            // the route needs one (only /import today).
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                }
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val target = parts.getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            val pathOnly = target.substringBefore('?')
            val query = if ('?' in target) target.substringAfter('?') else ""

            // Lazy-read the body so we don't stall on routes that don't need it.
            fun readBody(): String {
                if (contentLength <= 0) return ""
                val buf = CharArray(contentLength)
                var off = 0
                while (off < contentLength) {
                    val n = reader.read(buf, off, contentLength - off)
                    if (n < 0) break
                    off += n
                }
                return String(buf, 0, off)
            }

            when {
                pathOnly == "/status"             && method == "GET"  -> writeStatus(out)
                pathOnly == "/export"             && method == "GET"  -> writeExport(out)
                pathOnly == "/import"             && method == "POST" -> handleControl(out) {
                    val ok = control.importSettingsJson(readBody())
                    if (!ok) error("import rejected: invalid JSON or stream is running")
                }
                pathOnly == "/control/start"      && method == "POST" -> handleControl(out) {
                    val ok = control.startStream()
                    if (!ok) error("cannot start — already streaming or service not ready")
                }
                pathOnly == "/control/stop"       && method == "POST" -> handleControl(out) { control.stopStream() }
                pathOnly == "/control/torch"      && method == "POST" -> handleControl(out) { control.toggleTorch() }
                pathOnly == "/control/lens"       && method == "POST" -> handleControl(out) { control.switchLens() }
                pathOnly == "/control/snapshot"   && method == "POST" -> handleControl(out) {
                    val ok = control.snapshot()
                    if (!ok) error("no frame yet")
                }
                pathOnly == "/control/zoom"       && method == "POST" -> handleControl(out) {
                    val factor = if (queryParam(query, "dir") == "out") 1f / 1.25f else 1.25f
                    control.zoomBy(factor)
                }
                pathOnly == "/control/ev"         && method == "POST" -> handleControl(out) {
                    val delta = if (queryParam(query, "dir") == "down") -1 else 1
                    control.nudgeExposure(delta)
                }
                pathOnly == "/control/mirror"     && method == "POST" -> handleControl(out) { control.toggleMirror() }
                pathOnly == "/control/af"         && method == "POST" -> handleControl(out) { control.toggleContinuousAf() }
                pathOnly == "/control/quality"    && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v")?.toIntOrNull() ?: error("missing v=<10..95>")
                    control.setJpegQuality(v)
                }
                pathOnly == "/control/resolution" && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v") ?: error("missing v=<label>")
                    val ok = control.setResolutionLabel(v)
                    if (!ok) error("unknown resolution: $v")
                }
                pathOnly == "/control/fps"        && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v")?.toIntOrNull() ?: error("missing v=<int>")
                    val ok = control.setFpsValue(v)
                    if (!ok) error("unsupported fps: $v")
                }
                pathOnly == "/control/protocol"   && method == "POST" -> handleControl(out) {
                    val v = queryParam(query, "v") ?: error("missing v=mjpeg|rtsp")
                    val ok = control.setProtocol(v)
                    if (!ok) error("cannot set protocol to '$v' (stream may be running)")
                }
                pathOnly == "/control/setting"    && method == "POST" -> handleControl(out) {
                    val k = queryParam(query, "key") ?: error("missing key=")
                    val v = queryParam(query, "v")   ?: error("missing v=")
                    val ok = control.updateSetting(k, v)
                    if (!ok) error("rejected: key=$k v=$v (locked while streaming or invalid)")
                }
                else -> writeLanding(out)
            }
        } catch (_: SocketException) { /* normal */ }
        catch (t: Throwable) { Log.w(TAG, "Client handler error: ${t.message}") }
        finally { try { socket.close() } catch (_: Throwable) {} }
    }

    private fun queryParam(query: String, key: String): String? {
        if (query.isEmpty()) return null
        for (kv in query.split('&')) {
            val eq = kv.indexOf('=')
            if (eq < 0) continue
            if (kv.substring(0, eq) == key) return kv.substring(eq + 1)
        }
        return null
    }

    private fun handleControl(out: OutputStream, action: () -> Unit) {
        var body = "ok".toByteArray(Charsets.US_ASCII)
        var status = "200 OK"
        try {
            action()
        } catch (t: Throwable) {
            body = (t.message ?: "error").toByteArray(Charsets.UTF_8)
            status = "503 Service Unavailable"
        }
        val header = ("HTTP/1.0 $status\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeStatus(out: OutputStream) {
        val body = control.statusJson().toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeExport(out: OutputStream) {
        val body = control.exportSettingsJson().toByteArray(Charsets.UTF_8)
        // Content-Disposition encourages browsers to "Save As" instead of inlining the
        // raw JSON in a tab — friendlier for a "download backup" workflow.
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Content-Disposition: attachment; filename=\"lenscast-settings.json\"\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeLanding(out: OutputStream) {
        val html = renderLandingHtml().toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Length: ${html.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(html); out.flush() } catch (_: IOException) {}
    }

    /**
     * Single dynamic page. All sections are rendered; the script shows or hides them based
     * on the current `state` (idle vs streaming) and `protocol` from `/status`. The reasons
     * for one page instead of multiple:
     *  - Smoother UX — no full reload when the user starts the stream.
     *  - Easier to keep button bindings consistent across modes.
     *  - The page is small enough that JS-side conditional rendering stays readable.
     */
    private fun renderLandingHtml(): String {
        val mjpegPortNow = mjpegPort()
        val rtspPortNow = rtspPort()
        val initialQuality = control.jpegQuality()
        return """
            <!doctype html>
            <html><head><title>Lenscast Control</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{background:#14121c;color:#eee;font-family:system-ui,sans-serif;margin:0;padding:24px;text-align:center}
              h1{font-weight:600;margin:0 0 12px}
              img{max-width:100%;border-radius:12px;box-shadow:0 8px 32px rgba(120,73,242,0.3)}
              code{background:#262335;padding:2px 8px;border-radius:6px;font-size:13px}
              .controls{display:flex;flex-wrap:wrap;gap:8px;justify-content:center;margin:12px 0}
              .pickers{display:flex;flex-wrap:wrap;gap:12px;justify-content:center;margin:12px 0;color:#bbb;font-size:13px}
              .pickers label{display:flex;flex-direction:column;align-items:flex-start;gap:4px}
              button{background:#262335;color:#eee;border:0;padding:10px 16px;border-radius:8px;font-size:14px;cursor:pointer}
              button:hover{background:#33304a}
              button.danger{background:#5a2330}
              button.danger:hover{background:#7a2f3f}
              button.primary{background:#5a3da8}
              button.primary:hover{background:#7849f2}
              select{background:#262335;color:#eee;border:0;padding:6px 8px;border-radius:6px}
              .msg{min-height:1.2em;color:#a98cff;font-size:13px;margin-top:8px}
              .stats{color:#bba8ff;font-size:13px;margin-top:12px;font-family:ui-monospace,monospace}
              .slider{margin:12px auto;max-width:320px;color:#bbb;font-size:13px;display:flex;flex-direction:column;gap:6px}
              .slider input{width:100%;accent-color:#7849f2}
              .notice{color:#bba8ff;background:#262335;border-radius:12px;padding:14px;max-width:560px;margin:12px auto;font-size:14px;text-align:left}
              .hidden{display:none}
              .preview-wrap{margin:12px auto;max-width:720px}
              details.settings-section{max-width:720px;margin:16px auto;text-align:left;background:#1a1827;border-radius:12px;padding:8px 16px}
              details.settings-section summary{cursor:pointer;font-weight:600;padding:8px 0;color:#ddd}
              details.settings-section fieldset{border:0;margin:8px 0;padding:8px;background:#262335;border-radius:8px}
              details.settings-section legend{padding:0 6px;color:#bba8ff;font-size:13px;font-weight:600;text-transform:uppercase;letter-spacing:.05em}
              details.settings-section label{display:flex;align-items:center;gap:10px;color:#ccc;font-size:13px;padding:5px 0;flex-wrap:wrap}
              details.settings-section select,details.settings-section input[type=number]{background:#1a1827;color:#eee;border:1px solid #3b3754;border-radius:6px;padding:5px 8px;font-size:13px}
              details.settings-section input[type=range]{flex:1;min-width:160px;accent-color:#7849f2}
              details.settings-section input[type=checkbox]{accent-color:#7849f2}
              details.settings-section textarea{flex:1;min-width:240px;background:#1a1827;color:#eee;border:1px solid #3b3754;border-radius:6px;padding:6px;font-family:ui-monospace,monospace;font-size:12px;resize:vertical}
              .btnish{display:inline-block;background:#262335;color:#eee;border:0;padding:10px 16px;border-radius:8px;font-size:14px;cursor:pointer;text-decoration:none}
              .btnish:hover{background:#33304a}
              .pickers label{cursor:pointer}
            </style></head>
            <body>
              <h1>Lenscast Control</h1>
              <div id="stats" class="stats">…</div>

              <!-- IDLE: protocol picker + Start -->
              <div id="idle-block" class="hidden">
                <p class="notice">No stream running. Pick a protocol, then press Start.</p>
                <div class="pickers">
                  <label><input type="radio" name="proto" value="mjpeg"> MJPEG (browser, OBS, ffmpeg, VLC)</label>
                  <label><input type="radio" name="proto" value="rtsp"> RTSP (OBS, ffmpeg, VLC — supports 60/120/240 fps)</label>
                </div>
                <div class="controls">
                  <button data-act="start" class="primary">Start streaming</button>
                </div>
              </div>

              <!-- MJPEG live preview + audio sidecar links -->
              <div id="mjpeg-block" class="hidden preview-wrap">
                <p><code id="mjpeg-video-url"></code></p>
                <img id="preview" alt="Live preview">
                <p>
                  <code id="mjpeg-audio-url"></code> ·
                  <code id="mjpeg-shot-url"></code>
                </p>
              </div>

              <!-- RTSP URL hint -->
              <div id="rtsp-block" class="hidden">
                <p class="notice">
                  RTSP streaming is active. Browsers can't render RTSP — paste the URL into
                  OBS / VLC / ffmpeg.
                </p>
                <p><code id="rtsp-url"></code></p>
              </div>

              <!-- LIVE controls — visible when streaming -->
              <div id="live-block" class="hidden">
                <div class="controls">
                  <button data-act="lens">Switch camera</button>
                  <button data-act="torch">Toggle torch</button>
                  <button data-act="mirror">Mirror</button>
                  <button data-act="af">Continuous AF</button>
                </div>
                <div class="controls">
                  <button data-act="zoom" data-arg="dir=in">Zoom +</button>
                  <button data-act="zoom" data-arg="dir=out">Zoom −</button>
                  <button data-act="ev" data-arg="dir=up">EV +</button>
                  <button data-act="ev" data-arg="dir=down">EV −</button>
                  <button data-act="snapshot" id="snapshot-btn">Snapshot</button>
                  <button data-act="stop" class="danger">Stop</button>
                </div>
                <div class="pickers">
                  <label>Resolution
                    <select id="res-sel"></select>
                  </label>
                  <label>FPS
                    <select id="fps-sel"></select>
                  </label>
                </div>
                <div class="slider" id="quality-slider">
                  <label for="q">JPEG quality <span id="qval">$initialQuality</span></label>
                  <input id="q" type="range" min="10" max="95" value="$initialQuality">
                </div>
              </div>

              <!-- Full Settings parity with the app's Settings sheet. Persisted as soon as
                   the user changes any control; rebinds the camera where relevant. -->
              <details class="settings-section" open>
                <summary>Settings</summary>

                <fieldset>
                  <legend>Backup</legend>
                  <p style="color:#999;font-size:12px;margin:4px 0">
                    Export downloads a JSON file with every setting (including the
                    stream passcode — treat it as sensitive). Import replaces the
                    current settings; the stream must be stopped first.
                  </p>
                  <div class="controls">
                    <a href="/export" download="lenscast-settings.json" class="btnish">Export settings</a>
                  </div>
                  <label>Import JSON
                    <textarea id="import-json" rows="4" placeholder="Paste exported JSON here, then click Import"></textarea>
                  </label>
                  <div class="controls">
                    <button id="import-btn">Import</button>
                  </div>
                </fieldset>

                <fieldset>
                  <legend>Camera</legend>
                  <label>Lens
                    <select data-setting="lens">
                      <option value="back">Back</option>
                      <option value="front">Front</option>
                    </select>
                  </label>
                </fieldset>

                <fieldset>
                  <legend>Image (MJPEG path)</legend>
                  <label><input type="checkbox" data-setting="mirror"> Mirror (horizontal flip)</label>
                  <label><input type="checkbox" data-setting="continuousAf"> Continuous autofocus</label>
                  <label>Exposure compensation
                    <input type="range" min="-12" max="12" step="1" data-setting-range="exposureEv">
                    <span data-rangeval="exposureEv">0</span> EV
                  </label>
                  <label>White balance
                    <select data-setting="whiteBalance">
                      <option value="auto">Auto</option>
                      <option value="incandescent">Tungsten</option>
                      <option value="fluorescent">Fluorescent</option>
                      <option value="daylight">Daylight</option>
                      <option value="cloudy">Cloudy</option>
                      <option value="shade">Shade</option>
                    </select>
                  </label>
                  <label>Anti-flicker
                    <select data-setting="antiBanding">
                      <option value="auto">Auto</option>
                      <option value="hz50">50 Hz</option>
                      <option value="hz60">60 Hz</option>
                      <option value="off">Off</option>
                    </select>
                  </label>
                  <label>Effect
                    <select data-setting="effect">
                      <option value="none">None</option>
                      <option value="mono">Mono</option>
                      <option value="negative">Negative</option>
                      <option value="sepia">Sepia</option>
                      <option value="aqua">Aqua</option>
                      <option value="solarize">Solarize</option>
                      <option value="posterize">Posterize</option>
                      <option value="blackboard">Blackboard</option>
                      <option value="whiteboard">Whiteboard</option>
                    </select>
                  </label>
                  <label>Scene
                    <select data-setting="sceneMode">
                      <option value="disabled">Off</option>
                      <option value="action">Action</option>
                      <option value="portrait">Portrait</option>
                      <option value="landscape">Landscape</option>
                      <option value="night">Night</option>
                      <option value="sports">Sports</option>
                      <option value="theatre">Theatre</option>
                      <option value="fireworks">Fireworks</option>
                      <option value="beach">Beach</option>
                      <option value="snow">Snow</option>
                      <option value="sunset">Sunset</option>
                    </select>
                  </label>
                  <label><input type="checkbox" data-setting="manualFocus"> Manual focus</label>
                  <label>Focus distance (cd, 0=∞)
                    <input type="range" min="0" max="1000" step="50" data-setting-range="manualFocusCentidiopters">
                    <span data-rangeval="manualFocusCentidiopters">0</span>
                  </label>
                  <label id="manual-exp-row"><input type="checkbox" data-setting="manualExposure"> Manual exposure (ISO + shutter)</label>
                  <p id="manual-exp-unsupported" class="hidden" style="color:#bba8ff;font-size:12px">Not supported on this lens.</p>
                  <label id="iso-row">ISO
                    <input type="range" min="50" max="3200" step="50" data-setting-range="iso">
                    <span data-rangeval="iso">100</span>
                  </label>
                  <label id="shutter-row">Shutter (µs)
                    <input type="range" min="100" max="500000" step="100" data-setting-range="shutterUs">
                    <span data-rangeval="shutterUs">16666</span>
                  </label>
                </fieldset>

                <fieldset>
                  <legend>Audio</legend>
                  <label><input type="checkbox" data-setting="audioEnabled"> Stream microphone audio</label>
                  <label>Mic source
                    <select data-setting="micSource">
                      <option value="camcorder">Camcorder</option>
                      <option value="mic">Default mic</option>
                      <option value="voice_recognition">Voice recognition</option>
                      <option value="voice_communication">Voice communication</option>
                      <option value="unprocessed">Unprocessed</option>
                    </select>
                  </label>
                  <label>Gain (dB)
                    <input type="range" min="-24" max="24" step="1" data-setting-range="audioGainDb">
                    <span data-rangeval="audioGainDb">0</span> dB
                  </label>
                  <label><input type="checkbox" data-setting="noiseSuppress"> Noise suppression</label>
                  <label><input type="checkbox" data-setting="echoCancel"> Echo cancellation</label>
                </fieldset>

                <fieldset>
                  <legend>Stream</legend>
                  <label>JPEG quality
                    <input type="range" min="10" max="95" step="1" data-setting-range="jpegQuality">
                    <span data-rangeval="jpegQuality">80</span>
                  </label>
                  <label>RTSP bitrate (kbps, 0=auto)
                    <input type="range" min="0" max="20000" step="500" data-setting-range="rtspBitrateKbps">
                    <span data-rangeval="rtspBitrateKbps">0</span>
                  </label>
                </fieldset>

                <fieldset>
                  <legend>UX</legend>
                  <label><input type="checkbox" data-setting="keepScreenOn"> Keep screen on while streaming</label>
                  <label><input type="checkbox" data-setting="blankPreview"> Hide preview while streaming</label>
                  <label>Rotation lock (MJPEG)
                    <select data-setting="rotationLock">
                      <option value="auto">Auto</option>
                      <option value="portrait">Portrait</option>
                      <option value="landscape_left">Landscape ←</option>
                      <option value="landscape_right">Landscape →</option>
                      <option value="portrait_upside_down">Portrait ⤓</option>
                    </select>
                  </label>
                </fieldset>

                <fieldset>
                  <legend>Automation</legend>
                  <label><input type="checkbox" data-setting="autoStart"> Auto-start on app launch</label>
                  <label><input type="checkbox" data-setting="startOnBoot"> Start streaming on device boot</label>
                  <label><input type="checkbox" data-setting="recordLocally"> Record to MP4 (RTSP)</label>
                </fieldset>

                <fieldset>
                  <legend>Server ports</legend>
                  <label>MJPEG port
                    <input type="number" min="1024" max="65535" data-setting-int="mjpegPort">
                  </label>
                  <label>RTSP port
                    <input type="number" min="1024" max="65535" data-setting-int="rtspPort">
                  </label>
                </fieldset>

                <fieldset>
                  <legend>Security</legend>
                  <label><input type="checkbox" data-setting="httpsEnabled"> HTTPS (self-signed cert)</label>
                  <p style="color:#888;font-size:12px;margin:4px 0">
                    Cert fingerprint:
                    <code id="tls-fingerprint" style="font-size:11px">—</code>
                  </p>
                </fieldset>
              </details>

              <div id="msg" class="msg"></div>

              <script>
                const mjpegPort = $mjpegPortNow;
                const rtspPort = $rtspPortNow;
                const mjpegScheme = ${if (mjpegIsHttps()) "'https'" else "'http'"};
                const host = window.location.hostname;
                const mjpegBase = mjpegScheme + '://' + host + ':' + mjpegPort;
                document.getElementById('preview').src = mjpegBase + '/video';
                document.getElementById('mjpeg-video-url').textContent = mjpegBase + '/video';
                document.getElementById('mjpeg-audio-url').textContent = mjpegBase + '/audio';
                document.getElementById('mjpeg-shot-url').textContent  = mjpegBase + '/shot.jpg';
                document.getElementById('rtsp-url').textContent =
                  'rtsp://' + host + ':' + rtspPort + '/lenscast';

                async function post(act, arg) {
                  const m = document.getElementById('msg');
                  m.textContent = '…';
                  const qs = arg ? ('?' + arg) : '';
                  try {
                    const r = await fetch('/control/' + act + qs, { method: 'POST' });
                    m.textContent = r.ok ? (act + ': ok') : (act + ': ' + (await r.text()));
                  } catch (e) { m.textContent = act + ': ' + e; }
                }

                async function setSetting(key, value) {
                  return post('setting', 'key=' + encodeURIComponent(key) + '&v=' + encodeURIComponent(value));
                }

                document.querySelectorAll('button[data-act]').forEach(btn => {
                  btn.addEventListener('click', () => post(btn.dataset.act, btn.dataset.arg));
                });

                // Protocol radio buttons (idle screen only — the page hides them while live).
                document.querySelectorAll('input[name=proto]').forEach(r => {
                  r.addEventListener('change', () => {
                    if (r.checked) post('protocol', 'v=' + r.value);
                  });
                });

                // Generic setting dispatcher — selects + checkboxes.
                document.querySelectorAll('[data-setting]').forEach(el => {
                  el.addEventListener('change', () => {
                    const key = el.dataset.setting;
                    const value = (el.type === 'checkbox') ? (el.checked ? 'true' : 'false') : el.value;
                    setSetting(key, value);
                  });
                });

                // Range sliders — debounce so dragging doesn't hammer the server.
                document.querySelectorAll('[data-setting-range]').forEach(el => {
                  const key = el.dataset.settingRange;
                  const span = document.querySelector('[data-rangeval="' + key + '"]');
                  let timer = null;
                  el.addEventListener('input', () => {
                    if (span) span.textContent = el.value;
                    clearTimeout(timer);
                    timer = setTimeout(() => setSetting(key, el.value), 150);
                  });
                });

                // Number inputs — commit on blur / Enter.
                document.querySelectorAll('[data-setting-int]').forEach(el => {
                  const key = el.dataset.settingInt;
                  const commit = () => setSetting(key, el.value);
                  el.addEventListener('change', commit);
                  el.addEventListener('blur', commit);
                });

                // Import: POST the textarea body to /import. The phone validates the JSON
                // and falls back to defaults for any missing fields, so a clipped paste
                // still works as long as the JSON itself parses.
                document.getElementById('import-btn').addEventListener('click', async () => {
                  const body = document.getElementById('import-json').value.trim();
                  if (!body) { document.getElementById('msg').textContent = 'import: paste JSON first'; return; }
                  try {
                    const r = await fetch('/import', { method: 'POST', body, headers: { 'Content-Type': 'application/json' } });
                    document.getElementById('msg').textContent = r.ok ? 'import: ok' : ('import: ' + (await r.text()));
                  } catch (e) { document.getElementById('msg').textContent = 'import: ' + e; }
                });

                function show(id, on) {
                  document.getElementById(id).classList.toggle('hidden', !on);
                }

                let lastResOpts = '';
                let lastFpsOpts = '';
                function populateSelect(el, options, current) {
                  const key = options.join(',') + '|' + current;
                  if (el.dataset.key === key) return;
                  el.dataset.key = key;
                  el.innerHTML = '';
                  for (const opt of options) {
                    const o = document.createElement('option');
                    o.value = opt; o.textContent = opt;
                    if (String(opt) === String(current)) o.selected = true;
                    el.appendChild(o);
                  }
                }

                document.getElementById('res-sel').addEventListener('change', e => {
                  post('resolution', 'v=' + encodeURIComponent(e.target.value));
                });
                document.getElementById('fps-sel').addEventListener('change', e => {
                  post('fps', 'v=' + encodeURIComponent(e.target.value));
                });

                async function refresh() {
                  let s;
                  try {
                    const r = await fetch('/status');
                    if (!r.ok) return;
                    s = await r.json();
                  } catch (e) { return; }
                  const live = s.state === 'streaming' || s.state === 'starting';
                  show('idle-block', !live);
                  show('live-block', live);
                  show('mjpeg-block', live && s.protocol === 'mjpeg');
                  show('rtsp-block', live && s.protocol === 'rtsp');
                  show('quality-slider', live && s.protocol === 'mjpeg');
                  const snapBtn = document.getElementById('snapshot-btn');
                  if (snapBtn) snapBtn.style.display = (s.protocol === 'rtsp') ? 'none' : '';

                  if (live && s.availableResolutions) {
                    populateSelect(document.getElementById('res-sel'), s.availableResolutions, s.resolution);
                  }
                  if (live && s.availableFps) {
                    populateSelect(document.getElementById('fps-sel'), s.availableFps, s.targetFps);
                  }

                  // Mirror the protocol radio with the current saved selection. Only writes
                  // when not focused, so a user mid-click on a radio doesn't get bounced.
                  document.querySelectorAll('input[name=proto]').forEach(r => {
                    if (r !== document.activeElement) r.checked = (r.value === s.protocol);
                  });

                  // Sync every Settings control with the server state, skipping any field
                  // the user is currently editing (so we don't fight their typing).
                  function setIfNotFocused(el, value) {
                    if (el === document.activeElement) return;
                    if (el.type === 'checkbox') el.checked = (value === true);
                    else el.value = value;
                  }
                  document.querySelectorAll('[data-setting]').forEach(el => {
                    const key = el.dataset.setting;
                    if (s[key] !== undefined) setIfNotFocused(el, s[key]);
                  });
                  document.querySelectorAll('[data-setting-range]').forEach(el => {
                    const key = el.dataset.settingRange;
                    if (s[key] !== undefined) {
                      setIfNotFocused(el, s[key]);
                      const span = document.querySelector('[data-rangeval="' + key + '"]');
                      if (span) span.textContent = s[key];
                    }
                  });
                  document.querySelectorAll('[data-setting-int]').forEach(el => {
                    const key = el.dataset.settingInt;
                    if (s[key] !== undefined) setIfNotFocused(el, s[key]);
                  });

                  if (s.tlsFingerprint) {
                    document.getElementById('tls-fingerprint').textContent = s.tlsFingerprint;
                  }

                  // Manual exposure: gate the row + clamp slider ranges to what the lens
                  // actually reports. When the lens doesn't support MANUAL_SENSOR at all,
                  // we hide the toggle and grey out the sliders.
                  const manualOk = !!s.supportsManualSensor;
                  show('manual-exp-unsupported', !manualOk);
                  document.querySelector('[data-setting="manualExposure"]').disabled = !manualOk;
                  const isoRow = document.getElementById('iso-row');
                  const shutRow = document.getElementById('shutter-row');
                  const showManual = manualOk && s.manualExposure;
                  isoRow.style.display = showManual ? '' : 'none';
                  shutRow.style.display = showManual ? '' : 'none';
                  if (Array.isArray(s.isoRange)) {
                    const isoEl = document.querySelector('[data-setting-range="iso"]');
                    isoEl.min = s.isoRange[0]; isoEl.max = s.isoRange[1];
                  }
                  if (Array.isArray(s.shutterRangeUs)) {
                    const sEl = document.querySelector('[data-setting-range="shutterUs"]');
                    sEl.min = s.shutterRangeUs[0]; sEl.max = s.shutterRangeUs[1];
                  }

                  const peak = s.audioPeakDbfs;
                  const peakStr = (peak !== undefined && peak > -89) ? (' · ' + peak.toFixed(0) + ' dBFS') : '';
                  document.getElementById('stats').textContent =
                    '[' + (s.state || 'idle') + '] ' + (s.protocol || '—') + ' · ' + (s.lens || '—') +
                    ' · ' + (s.resolution || '—') + ' · ' + (s.fps || 0) + ' fps · ' +
                    (s.clients || 0) + ' clients · zoom ' + (s.zoom || 1).toFixed(2) + 'x · EV ' +
                    (s.ev || 0) + (s.mirror ? ' · mirror' : '') + (s.torch ? ' · torch' : '') + peakStr;
                }

                refresh();
                setInterval(refresh, 1000);

                const q = document.getElementById('q');
                let qTimer = null;
                q.addEventListener('input', () => {
                  document.getElementById('qval').textContent = q.value;
                  clearTimeout(qTimer);
                  qTimer = setTimeout(() => fetch('/control/quality?v=' + q.value, { method: 'POST' }), 150);
                });
              </script>
            </body></html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "WebControlServer"
    }
}
