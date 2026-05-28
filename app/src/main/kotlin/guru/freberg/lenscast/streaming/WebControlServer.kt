package guru.freberg.lenscast.streaming

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
    /** App version string (e.g. "1.0.1") — rendered in the page footer. */
    private val appVersion: () -> String = { "" },
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
     *
     * Layout: CSS-grid two-column on desktop (preview/hero on the left, stats + quick
     * controls on the right), single-column stack on mobile. Settings live below the
     * fold as a tabbed panel — Camera / Image / Audio / Stream / UX / System — so the
     * full Settings sheet doesn't have to be a 1000-line vertical scroll on a phone
     * browser.
     */
    private fun renderLandingHtml(): String {
        val mjpegPortNow = mjpegPort()
        val rtspPortNow = rtspPort()
        val initialQuality = control.jpegQuality()
        val version = appVersion().ifEmpty { "" }
        return """
            <!doctype html>
            <html><head><title>Lenscast Control</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              /* Design tokens — single source of truth for colours and spacing. The
                 dark scheme is the default and the only one we ship; brand violet
                 matches the in-app Material 3 theme. */
              :root{
                --bg:#0E0B17; --bg-elev:#14121C; --surface:#1A1827; --surface-2:#262335;
                --surface-3:#33304A; --border:#3b3754;
                --text:#EDEAF7; --text-dim:#bba8ff; --text-mute:#9089a8;
                --accent:#9B7CFF; --accent-strong:#7849F2; --accent-soft:rgba(120,73,242,.15);
                --danger:#FF5E5B; --danger-bg:#5a2330;
                --ok:#66BB6A; --warn:#FFB300; --alert:#E53935;
                --r-sm:8px; --r-md:12px; --r-lg:16px;
                --gap-1:6px; --gap-2:10px; --gap-3:14px; --gap-4:20px;
              }
              *{box-sizing:border-box}
              html,body{margin:0;padding:0;background:var(--bg);color:var(--text);
                        font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;
                        font-size:14px;line-height:1.45}
              a{color:var(--accent);text-decoration:none}
              a:hover{text-decoration:underline}
              code{background:var(--surface-2);padding:2px 6px;border-radius:6px;
                   font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}
              img{max-width:100%;display:block}

              /* Page chrome ----------------------------------------------------- */
              header{display:flex;align-items:center;gap:var(--gap-2);
                     padding:14px 20px;border-bottom:1px solid var(--surface-2);
                     position:sticky;top:0;background:var(--bg);z-index:5}
              header .logo{width:28px;height:28px;border-radius:8px;
                           background:linear-gradient(135deg,var(--accent),var(--accent-strong));
                           display:grid;place-items:center;color:#fff;font-weight:700;
                           font-size:15px}
              header h1{margin:0;font-size:16px;font-weight:600;letter-spacing:-.01em;flex:1}
              header .state-badge{display:inline-flex;align-items:center;gap:6px;
                                  padding:4px 10px;border-radius:999px;font-size:12px;
                                  font-weight:600;background:var(--surface-2);
                                  color:var(--text-mute)}
              header .state-badge .dot{width:8px;height:8px;border-radius:50%;
                                       background:var(--text-mute)}
              header .state-badge[data-state=streaming]{background:rgba(102,187,106,.15);
                                                       color:var(--ok)}
              header .state-badge[data-state=streaming] .dot{background:var(--ok);
                                                            animation:pulse 1.5s ease-in-out infinite}
              header .state-badge[data-state=starting]{background:rgba(255,179,0,.15);color:var(--warn)}
              header .state-badge[data-state=starting] .dot{background:var(--warn)}
              header .state-badge[data-state=error]{background:rgba(229,57,53,.15);color:var(--alert)}
              @keyframes pulse{50%{opacity:.4}}

              main{max-width:1100px;margin:0 auto;padding:20px;
                   display:grid;grid-template-columns:1fr;gap:var(--gap-4)}
              @media (min-width:900px){
                main{grid-template-columns:minmax(0,1.4fr) minmax(0,1fr)}
              }

              /* Card primitive -------------------------------------------------- */
              .card{background:var(--surface);border:1px solid var(--surface-2);
                    border-radius:var(--r-lg);padding:var(--gap-4)}
              .card h2{margin:0 0 var(--gap-3) 0;font-size:13px;font-weight:600;
                       text-transform:uppercase;letter-spacing:.07em;color:var(--text-dim)}
              .card .subtitle{color:var(--text-mute);margin:0 0 var(--gap-3);font-size:13px}

              /* Hero (preview / start) ---------------------------------------- */
              .hero{grid-column:1/-1}
              @media (min-width:900px){.hero{grid-column:1/2}}
              .hero .preview-frame{position:relative;background:#000;border-radius:var(--r-md);
                                   overflow:hidden;aspect-ratio:16/9}
              .hero .preview-frame img{width:100%;height:100%;object-fit:contain}
              .hero .preview-frame .overlay{position:absolute;inset:0;display:grid;
                                            place-items:center;text-align:center;padding:24px;
                                            background:radial-gradient(circle at center, rgba(120,73,242,.18), transparent 70%)}
              .hero .preview-frame .overlay .big{font-size:18px;font-weight:600;color:#fff;
                                                 margin-bottom:6px}
              .hero .preview-frame .overlay .small{color:#bba8ff;font-size:13px}
              .hero .links{display:flex;flex-wrap:wrap;gap:var(--gap-2);
                           margin-top:var(--gap-3);font-size:12px}
              .hero .links code{font-size:11px}

              /* Idle hero: protocol picker + Start */
              .idle-picker{display:flex;flex-direction:column;gap:var(--gap-3)}
              .idle-picker .proto-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:var(--gap-2)}
              .idle-picker .proto{cursor:pointer;border:2px solid var(--surface-2);
                                  background:var(--surface-2);border-radius:var(--r-md);
                                  padding:var(--gap-3);text-align:left;transition:all .15s}
              .idle-picker .proto:hover{border-color:var(--surface-3)}
              .idle-picker .proto input{display:none}
              .idle-picker .proto input:checked + .proto-inner{color:var(--text)}
              .idle-picker .proto:has(input:checked){border-color:var(--accent-strong);
                                                     background:var(--accent-soft)}
              .idle-picker .proto .proto-name{font-weight:600;margin-bottom:4px}
              .idle-picker .proto .proto-desc{color:var(--text-mute);font-size:12px;line-height:1.4}

              /* Quick controls + stats ---------------------------------------- */
              .side{display:flex;flex-direction:column;gap:var(--gap-4)}

              .stats-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:var(--gap-2)}
              .stat{background:var(--surface-2);border-radius:var(--r-md);
                    padding:var(--gap-2) var(--gap-3)}
              .stat .label{font-size:11px;color:var(--text-mute);text-transform:uppercase;
                           letter-spacing:.05em}
              .stat .value{font-size:16px;font-weight:600;color:var(--text);
                           font-variant-numeric:tabular-nums;line-height:1.2}
              .stat.full{grid-column:1/-1}
              .vu{height:8px;background:var(--surface-3);border-radius:4px;overflow:hidden;
                  margin-top:6px}
              .vu .fill{height:100%;background:var(--ok);transition:width .1s linear,
                                                                    background-color .2s}

              .quick-controls{display:grid;grid-template-columns:repeat(2,1fr);gap:var(--gap-2)}
              .quick-controls.row3{grid-template-columns:repeat(3,1fr)}

              /* Buttons -------------------------------------------------------- */
              .btn{display:inline-flex;align-items:center;justify-content:center;gap:6px;
                   background:var(--surface-2);color:var(--text);border:0;
                   padding:10px 14px;border-radius:var(--r-md);font-size:13px;
                   font-weight:500;cursor:pointer;font-family:inherit;
                   transition:background .15s,transform .05s}
              .btn:hover{background:var(--surface-3)}
              .btn:active{transform:scale(.98)}
              .btn.primary{background:var(--accent-strong);color:#fff}
              .btn.primary:hover{background:#9061ff}
              .btn.danger{background:var(--danger-bg);color:#fff}
              .btn.danger:hover{background:#7a2f3f}
              .btn.ghost{background:transparent;border:1px solid var(--surface-2)}
              .btn:disabled{opacity:.4;cursor:not-allowed}
              .btn.big{padding:14px 18px;font-size:15px}

              /* Tabs ----------------------------------------------------------- */
              .settings-card{grid-column:1/-1}
              .tabs{display:flex;gap:2px;border-bottom:1px solid var(--surface-2);
                    overflow-x:auto;margin-bottom:var(--gap-3);
                    scrollbar-width:thin}
              .tab{flex:none;background:none;border:0;border-bottom:2px solid transparent;
                   color:var(--text-mute);padding:10px 14px;font-size:13px;cursor:pointer;
                   font-family:inherit;font-weight:500;white-space:nowrap;transition:all .15s}
              .tab:hover{color:var(--text)}
              .tab[aria-selected=true]{color:var(--accent);border-bottom-color:var(--accent)}
              .panel{display:none;flex-direction:column;gap:var(--gap-3)}
              .panel[data-active]{display:flex}

              /* Form rows ------------------------------------------------------ */
              .field{display:flex;align-items:center;gap:var(--gap-3);
                     padding:var(--gap-2) 0;flex-wrap:wrap}
              .field > .l{flex:1;min-width:140px;color:var(--text)}
              .field > .l small{display:block;color:var(--text-mute);font-size:11px;font-weight:400;line-height:1.4}
              .field > .c{display:flex;align-items:center;gap:var(--gap-2);flex-wrap:wrap}
              .field input[type=range]{accent-color:var(--accent-strong);min-width:160px}
              .field input[type=checkbox]{accent-color:var(--accent-strong);width:18px;height:18px}
              .field select,.field input[type=number],.field input[type=text]{
                background:var(--bg-elev);color:var(--text);border:1px solid var(--border);
                border-radius:6px;padding:6px 10px;font-size:13px;font-family:inherit}
              .field .val{font-variant-numeric:tabular-nums;color:var(--text-mute);
                          min-width:50px;text-align:right;font-size:12px}
              .field textarea{width:100%;background:var(--bg-elev);color:var(--text);
                              border:1px solid var(--border);border-radius:6px;padding:8px;
                              font-family:ui-monospace,monospace;font-size:12px;resize:vertical;
                              min-height:80px}
              .divider{height:1px;background:var(--surface-2);margin:var(--gap-2) 0}

              /* Notice / message ---------------------------------------------- */
              .notice{background:var(--surface-2);border-radius:var(--r-md);
                      padding:var(--gap-3);font-size:13px;color:var(--text-dim)}
              .toast{position:fixed;bottom:20px;left:50%;transform:translateX(-50%);
                     background:var(--surface);border:1px solid var(--surface-2);
                     border-radius:var(--r-md);padding:10px 16px;color:var(--text);
                     box-shadow:0 8px 24px rgba(0,0,0,.4);font-size:13px;
                     opacity:0;pointer-events:none;transition:opacity .2s;z-index:10}
              .toast.show{opacity:1}
              .toast.error{border-color:var(--alert);color:#ffd1d0}

              .hidden{display:none!important}
              .row{display:flex;gap:var(--gap-2);flex-wrap:wrap}

              footer{max-width:1100px;margin:0 auto;padding:16px 20px 32px;
                     color:var(--text-mute);font-size:12px}
              footer code{font-size:10px;word-break:break-all}
            </style></head>
            <body>
              <header>
                <div class="logo">L</div>
                <h1>Lenscast Control</h1>
                <span id="state-badge" class="state-badge" data-state="idle">
                  <span class="dot"></span><span id="state-text">idle</span>
                </span>
              </header>

              <main>
                <!-- HERO ============================================== -->
                <section class="card hero">
                  <h2 id="hero-title">Live preview</h2>

                  <!-- Idle hero: protocol picker + Start -->
                  <div id="idle-block" class="hidden idle-picker">
                    <p class="subtitle">Pick a streaming protocol and tap Start.</p>
                    <div class="proto-grid">
                      <label class="proto">
                        <input type="radio" name="proto" value="mjpeg">
                        <div class="proto-inner">
                          <div class="proto-name">MJPEG</div>
                          <div class="proto-desc">Universal: browser &lt;img&gt;, OBS, VLC. PCM audio sidecar. ≤30 fps.</div>
                        </div>
                      </label>
                      <label class="proto">
                        <input type="radio" name="proto" value="rtsp">
                        <div class="proto-inner">
                          <div class="proto-name">RTSP</div>
                          <div class="proto-desc">H.264 + AAC. OBS / VLC / ffmpeg. Up to 240 fps. Landscape only.</div>
                        </div>
                      </label>
                    </div>
                    <button data-act="start" class="btn primary big">Start streaming</button>
                  </div>

                  <!-- Live MJPEG hero -->
                  <div id="mjpeg-block" class="hidden">
                    <div class="preview-frame">
                      <img id="preview" alt="Live preview">
                    </div>
                    <div class="links">
                      <code id="mjpeg-video-url"></code>
                      <code id="mjpeg-audio-url"></code>
                      <code id="mjpeg-shot-url"></code>
                    </div>
                  </div>

                  <!-- Live RTSP hero -->
                  <div id="rtsp-block" class="hidden">
                    <div class="preview-frame">
                      <div class="overlay">
                        <div>
                          <div class="big">RTSP is live</div>
                          <div class="small">Open in OBS / VLC / ffmpeg — browsers can't render RTSP natively.</div>
                        </div>
                      </div>
                    </div>
                    <div class="links"><code id="rtsp-url"></code></div>
                  </div>
                </section>

                <!-- SIDE: stats + quick controls ======================= -->
                <aside class="side">
                  <section class="card hidden" id="live-card">
                    <h2>Status</h2>
                    <div class="stats-grid">
                      <div class="stat"><div class="label">Protocol</div><div class="value" id="s-proto">—</div></div>
                      <div class="stat"><div class="label">Lens</div><div class="value" id="s-lens">—</div></div>
                      <div class="stat"><div class="label">FPS</div><div class="value" id="s-fps">—</div></div>
                      <div class="stat"><div class="label">Clients</div><div class="value" id="s-clients">0</div></div>
                      <div class="stat"><div class="label">Resolution</div><div class="value" id="s-res">—</div></div>
                      <div class="stat"><div class="label">Zoom · EV</div><div class="value" id="s-zoomev">—</div></div>
                      <div class="stat full" id="audio-stat">
                        <div class="label">Audio peak <span id="s-peak" style="float:right;color:var(--text-mute)">—</span></div>
                        <div class="vu"><div class="fill" id="vu-fill" style="width:0%"></div></div>
                      </div>
                    </div>
                  </section>

                  <section class="card hidden" id="live-controls-card">
                    <h2>Quick controls</h2>
                    <div class="quick-controls" id="quick-row1">
                      <button class="btn" data-act="lens">Switch camera</button>
                      <button class="btn" data-act="torch">Torch</button>
                      <button class="btn" data-act="mirror">Mirror</button>
                      <button class="btn" data-act="af">Continuous AF</button>
                    </div>
                    <div class="row" style="margin-top:var(--gap-2)">
                      <button class="btn" data-act="zoom" data-arg="dir=in">Zoom +</button>
                      <button class="btn" data-act="zoom" data-arg="dir=out">Zoom −</button>
                      <button class="btn" data-act="ev"   data-arg="dir=up">EV +</button>
                      <button class="btn" data-act="ev"   data-arg="dir=down">EV −</button>
                    </div>
                    <div class="row" style="margin-top:var(--gap-2)">
                      <button class="btn" data-act="snapshot" id="snapshot-btn" style="flex:1">Snapshot</button>
                      <button class="btn danger" data-act="stop" style="flex:1">Stop streaming</button>
                    </div>
                    <div class="divider"></div>
                    <div class="field">
                      <div class="l">Resolution</div>
                      <div class="c"><select id="res-sel"></select></div>
                    </div>
                    <div class="field">
                      <div class="l">FPS</div>
                      <div class="c"><select id="fps-sel"></select></div>
                    </div>
                    <div class="field" id="quality-row">
                      <div class="l">JPEG quality<small>MJPEG only</small></div>
                      <div class="c">
                        <input type="range" min="10" max="95" value="$initialQuality" id="q">
                        <span class="val" id="qval">$initialQuality</span>
                      </div>
                    </div>
                  </section>
                </aside>

                <!-- TABBED SETTINGS ==================================== -->
                <section class="card settings-card">
                  <h2>Settings</h2>
                  <div class="tabs" role="tablist">
                    <button class="tab" role="tab" data-tab="camera" aria-selected="true">Camera</button>
                    <button class="tab" role="tab" data-tab="image">Image</button>
                    <button class="tab" role="tab" data-tab="audio">Audio</button>
                    <button class="tab" role="tab" data-tab="stream">Stream</button>
                    <button class="tab" role="tab" data-tab="ux">UX</button>
                    <button class="tab" role="tab" data-tab="system">System</button>
                  </div>

                  <!-- Camera tab -->
                  <div class="panel" data-panel="camera" data-active>
                    <div class="field">
                      <div class="l">Lens</div>
                      <div class="c">
                        <select data-setting="lens">
                          <option value="back">Back</option>
                          <option value="front">Front</option>
                        </select>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Resolution<small>Available list comes from the active lens's capability map</small></div>
                      <div class="c"><select id="res-sel-tab"></select></div>
                    </div>
                    <div class="field">
                      <div class="l">FPS<small>Valid range depends on lens × resolution × protocol</small></div>
                      <div class="c"><select id="fps-sel-tab"></select></div>
                    </div>
                    <div class="field">
                      <div class="l">Rotation lock<small>MJPEG output direction</small></div>
                      <div class="c">
                        <select data-setting="rotationLock">
                          <option value="auto">Auto</option>
                          <option value="portrait">Portrait</option>
                          <option value="landscape_left">Landscape ←</option>
                          <option value="landscape_right">Landscape →</option>
                          <option value="portrait_upside_down">Portrait ⤓</option>
                        </select>
                      </div>
                    </div>
                  </div>

                  <!-- Image tab -->
                  <div class="panel" data-panel="image">
                    <div class="field">
                      <div class="l">Mirror (horizontal flip)</div>
                      <div class="c"><input type="checkbox" data-setting="mirror"></div>
                    </div>
                    <div class="field">
                      <div class="l">Continuous autofocus</div>
                      <div class="c"><input type="checkbox" data-setting="continuousAf"></div>
                    </div>
                    <div class="field">
                      <div class="l">Exposure compensation</div>
                      <div class="c">
                        <input type="range" min="-12" max="12" step="1" data-setting-range="exposureEv">
                        <span class="val" data-rangeval="exposureEv">0</span><span class="val">EV</span>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">White balance</div>
                      <div class="c">
                        <select data-setting="whiteBalance">
                          <option value="auto">Auto</option>
                          <option value="incandescent">Tungsten</option>
                          <option value="fluorescent">Fluorescent</option>
                          <option value="daylight">Daylight</option>
                          <option value="cloudy">Cloudy</option>
                          <option value="shade">Shade</option>
                        </select>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Anti-flicker</div>
                      <div class="c">
                        <select data-setting="antiBanding">
                          <option value="auto">Auto</option>
                          <option value="hz50">50 Hz</option>
                          <option value="hz60">60 Hz</option>
                          <option value="off">Off</option>
                        </select>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Effect</div>
                      <div class="c">
                        <select data-setting="effect">
                          <option value="none">None</option><option value="mono">Mono</option>
                          <option value="negative">Negative</option><option value="sepia">Sepia</option>
                          <option value="aqua">Aqua</option><option value="solarize">Solarize</option>
                          <option value="posterize">Posterize</option><option value="blackboard">Blackboard</option>
                          <option value="whiteboard">Whiteboard</option>
                        </select>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Scene mode</div>
                      <div class="c">
                        <select data-setting="sceneMode">
                          <option value="disabled">Off</option><option value="action">Action</option>
                          <option value="portrait">Portrait</option><option value="landscape">Landscape</option>
                          <option value="night">Night</option><option value="sports">Sports</option>
                          <option value="theatre">Theatre</option><option value="fireworks">Fireworks</option>
                          <option value="beach">Beach</option><option value="snow">Snow</option>
                          <option value="sunset">Sunset</option>
                        </select>
                      </div>
                    </div>
                    <div class="divider"></div>
                    <div class="field">
                      <div class="l">Manual focus</div>
                      <div class="c"><input type="checkbox" data-setting="manualFocus"></div>
                    </div>
                    <div class="field">
                      <div class="l">Focus distance<small>0 = infinity</small></div>
                      <div class="c">
                        <input type="range" min="0" max="1000" step="50" data-setting-range="manualFocusCentidiopters">
                        <span class="val" data-rangeval="manualFocusCentidiopters">0</span>
                      </div>
                    </div>
                    <div class="field" id="manual-exp-row">
                      <div class="l">Manual exposure<small id="manual-exp-unsupported" class="hidden">Not supported on this lens</small></div>
                      <div class="c"><input type="checkbox" data-setting="manualExposure"></div>
                    </div>
                    <div class="field" id="iso-row">
                      <div class="l">ISO</div>
                      <div class="c">
                        <input type="range" min="50" max="3200" step="50" data-setting-range="iso">
                        <span class="val" data-rangeval="iso">100</span>
                      </div>
                    </div>
                    <div class="field" id="shutter-row">
                      <div class="l">Shutter (µs)</div>
                      <div class="c">
                        <input type="range" min="100" max="500000" step="100" data-setting-range="shutterUs">
                        <span class="val" data-rangeval="shutterUs">16666</span>
                      </div>
                    </div>
                  </div>

                  <!-- Audio tab -->
                  <div class="panel" data-panel="audio">
                    <div class="field">
                      <div class="l">Stream microphone audio</div>
                      <div class="c"><input type="checkbox" data-setting="audioEnabled"></div>
                    </div>
                    <div class="field">
                      <div class="l">Mic source</div>
                      <div class="c">
                        <select data-setting="micSource">
                          <option value="camcorder">Camcorder</option>
                          <option value="mic">Default mic</option>
                          <option value="voice_recognition">Voice recognition</option>
                          <option value="voice_communication">Voice communication</option>
                          <option value="unprocessed">Unprocessed</option>
                        </select>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Gain</div>
                      <div class="c">
                        <input type="range" min="-24" max="24" step="1" data-setting-range="audioGainDb">
                        <span class="val" data-rangeval="audioGainDb">0</span><span class="val">dB</span>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Noise suppression</div>
                      <div class="c"><input type="checkbox" data-setting="noiseSuppress"></div>
                    </div>
                    <div class="field">
                      <div class="l">Echo cancellation</div>
                      <div class="c"><input type="checkbox" data-setting="echoCancel"></div>
                    </div>
                  </div>

                  <!-- Stream tab -->
                  <div class="panel" data-panel="stream">
                    <div class="field">
                      <div class="l">JPEG quality<small>MJPEG output</small></div>
                      <div class="c">
                        <input type="range" min="10" max="95" step="1" data-setting-range="jpegQuality">
                        <span class="val" data-rangeval="jpegQuality">80</span>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">RTSP bitrate cap<small>0 = auto from resolution × fps</small></div>
                      <div class="c">
                        <input type="range" min="0" max="20000" step="500" data-setting-range="rtspBitrateKbps">
                        <span class="val" data-rangeval="rtspBitrateKbps">0</span><span class="val">kbps</span>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Record to MP4 while streaming<small>RTSP only · saves to Movies/Lenscast/</small></div>
                      <div class="c"><input type="checkbox" data-setting="recordLocally"></div>
                    </div>
                  </div>

                  <!-- UX tab -->
                  <div class="panel" data-panel="ux">
                    <div class="field">
                      <div class="l">Keep screen on while streaming</div>
                      <div class="c"><input type="checkbox" data-setting="keepScreenOn"></div>
                    </div>
                    <div class="field">
                      <div class="l">Hide preview while streaming<small>Battery saver</small></div>
                      <div class="c"><input type="checkbox" data-setting="blankPreview"></div>
                    </div>
                    <div class="field">
                      <div class="l">Auto-start streaming on app launch</div>
                      <div class="c"><input type="checkbox" data-setting="autoStart"></div>
                    </div>
                    <div class="field">
                      <div class="l">Start on device boot</div>
                      <div class="c"><input type="checkbox" data-setting="startOnBoot"></div>
                    </div>
                  </div>

                  <!-- System / Advanced tab -->
                  <div class="panel" data-panel="system">
                    <div class="field">
                      <div class="l">MJPEG port</div>
                      <div class="c"><input type="number" min="1024" max="65535" data-setting-int="mjpegPort"></div>
                    </div>
                    <div class="field">
                      <div class="l">RTSP port</div>
                      <div class="c"><input type="number" min="1024" max="65535" data-setting-int="rtspPort"></div>
                    </div>
                    <div class="divider"></div>
                    <div class="field">
                      <div class="l">HTTPS<small>Self-signed cert; receivers click through the warning once</small></div>
                      <div class="c"><input type="checkbox" data-setting="httpsEnabled"></div>
                    </div>
                    <div class="field">
                      <div class="l">Cert fingerprint</div>
                      <div class="c"><code id="tls-fingerprint" style="font-size:10px">—</code></div>
                    </div>
                    <div class="divider"></div>
                    <div class="field">
                      <div class="l">Incoming call behavior<small>Needs READ_PHONE_STATE; DROP also needs ANSWER_PHONE_CALLS</small></div>
                      <div class="c">
                        <select data-setting="callBehavior">
                          <option value="ignore">Ignore (audio keeps flowing)</option>
                          <option value="mute_stream">Mute the stream</option>
                          <option value="drop_call">Drop the call</option>
                        </select>
                      </div>
                    </div>
                    <div class="field">
                      <div class="l">Keep web panel reachable in background<small>Adds a persistent low-priority notification</small></div>
                      <div class="c"><input type="checkbox" data-setting="persistentWebControl"></div>
                    </div>
                    <div class="divider"></div>
                    <div class="field">
                      <div class="l">Export settings</div>
                      <div class="c"><a href="/export" download="lenscast-settings.json" class="btn">Download JSON</a></div>
                    </div>
                    <div class="field">
                      <div class="l">Import settings<small>Replaces the current settings — stream must be stopped first</small></div>
                      <div class="c" style="flex-direction:column;align-items:stretch;width:100%">
                        <textarea id="import-json" placeholder="Paste exported JSON here, then click Import"></textarea>
                        <button id="import-btn" class="btn" style="align-self:flex-end;margin-top:6px">Import</button>
                      </div>
                    </div>
                  </div>
                </section>
              </main>

              <footer id="footer-text"></footer>
              <div id="toast" class="toast"></div>

              <script>
                // ── URL prep ──────────────────────────────────────────
                const mjpegPort = $mjpegPortNow;
                const rtspPort = $rtspPortNow;
                const mjpegScheme = ${if (mjpegIsHttps()) "'https'" else "'http'"};
                const host = window.location.hostname;
                const mjpegBase = mjpegScheme + '://' + host + ':' + mjpegPort;
                // NOTE: don't set preview.src here — at page-load time the MjpegServer
                // probably isn't running yet, the browser caches the failed connect, and
                // won't retry when streaming starts. The refresh() loop swaps in a
                // cache-busting URL on the idle → MJPEG-live transition instead.
                document.getElementById('mjpeg-video-url').textContent = mjpegBase + '/video';
                document.getElementById('mjpeg-audio-url').textContent = mjpegBase + '/audio';
                document.getElementById('mjpeg-shot-url').textContent  = mjpegBase + '/shot.jpg';
                document.getElementById('rtsp-url').textContent =
                  'rtsp://' + host + ':' + rtspPort + '/lenscast';
                document.getElementById('footer-text').textContent =
                  'Lenscast ${if (version.isEmpty()) "" else "v$version "}· control panel · ' + host;

                // ── Toast (replaces the old #msg single-line strip) ──
                const toast = document.getElementById('toast');
                let toastTimer = null;
                function showToast(text, isError) {
                  toast.textContent = text;
                  toast.classList.toggle('error', !!isError);
                  toast.classList.add('show');
                  clearTimeout(toastTimer);
                  toastTimer = setTimeout(() => toast.classList.remove('show'), 2400);
                }

                async function post(act, arg) {
                  const qs = arg ? ('?' + arg) : '';
                  try {
                    const r = await fetch('/control/' + act + qs, { method: 'POST' });
                    if (!r.ok) showToast(act + ': ' + (await r.text()), true);
                  } catch (e) { showToast(act + ': ' + e, true); }
                }
                async function setSetting(key, value) {
                  return post('setting', 'key=' + encodeURIComponent(key) + '&v=' + encodeURIComponent(value));
                }

                // ── Buttons ──────────────────────────────────────────
                document.querySelectorAll('button[data-act]').forEach(btn => {
                  btn.addEventListener('click', () => post(btn.dataset.act, btn.dataset.arg));
                });
                document.querySelectorAll('input[name=proto]').forEach(r => {
                  r.addEventListener('change', () => {
                    if (r.checked) post('protocol', 'v=' + r.value);
                  });
                });

                // ── Tabs ─────────────────────────────────────────────
                document.querySelectorAll('.tab').forEach(t => {
                  t.addEventListener('click', () => {
                    document.querySelectorAll('.tab').forEach(x => x.setAttribute('aria-selected', 'false'));
                    document.querySelectorAll('.panel').forEach(p => p.removeAttribute('data-active'));
                    t.setAttribute('aria-selected', 'true');
                    document.querySelector('[data-panel=' + t.dataset.tab + ']').setAttribute('data-active', '');
                  });
                });

                // ── Generic setting dispatchers ───────────────────────
                document.querySelectorAll('[data-setting]').forEach(el => {
                  el.addEventListener('change', () => {
                    const key = el.dataset.setting;
                    const value = (el.type === 'checkbox') ? (el.checked ? 'true' : 'false') : el.value;
                    setSetting(key, value);
                  });
                });
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
                document.querySelectorAll('[data-setting-int]').forEach(el => {
                  const key = el.dataset.settingInt;
                  const commit = () => setSetting(key, el.value);
                  el.addEventListener('change', commit);
                  el.addEventListener('blur', commit);
                });

                // ── Import button ─────────────────────────────────────
                document.getElementById('import-btn').addEventListener('click', async () => {
                  const body = document.getElementById('import-json').value.trim();
                  if (!body) { showToast('import: paste JSON first', true); return; }
                  try {
                    const r = await fetch('/import', { method: 'POST', body, headers: { 'Content-Type': 'application/json' } });
                    showToast(r.ok ? 'Settings imported' : 'import: ' + (await r.text()), !r.ok);
                  } catch (e) { showToast('import: ' + e, true); }
                });

                // ── JPEG quality slider (live, debounced) ─────────────
                const q = document.getElementById('q');
                let qTimer = null;
                q.addEventListener('input', () => {
                  document.getElementById('qval').textContent = q.value;
                  clearTimeout(qTimer);
                  qTimer = setTimeout(() => fetch('/control/quality?v=' + q.value, { method: 'POST' }), 150);
                });

                // ── /status refresh + live wiring ─────────────────────
                function show(id, on) { document.getElementById(id).classList.toggle('hidden', !on); }
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
                ['res-sel', 'res-sel-tab'].forEach(id => document.getElementById(id)
                  .addEventListener('change', e => post('resolution', 'v=' + encodeURIComponent(e.target.value))));
                ['fps-sel', 'fps-sel-tab'].forEach(id => document.getElementById(id)
                  .addEventListener('change', e => post('fps', 'v=' + encodeURIComponent(e.target.value))));

                function setIfNotFocused(el, value) {
                  if (el === document.activeElement) return;
                  if (el.type === 'checkbox') el.checked = (value === true);
                  else el.value = value;
                }

                async function refresh() {
                  let s;
                  try {
                    const r = await fetch('/status');
                    if (!r.ok) return;
                    s = await r.json();
                  } catch (e) { return; }

                  const live = s.state === 'streaming' || s.state === 'starting';

                  // Header badge
                  const badge = document.getElementById('state-badge');
                  badge.dataset.state = s.state || 'idle';
                  document.getElementById('state-text').textContent = (s.state || 'idle').toUpperCase();

                  // Hero title
                  document.getElementById('hero-title').textContent = live
                    ? (s.protocol === 'mjpeg' ? 'Live preview' : 'RTSP active')
                    : 'Ready';

                  // Hero swap
                  show('idle-block', !live);
                  show('mjpeg-block', live && s.protocol === 'mjpeg');
                  show('rtsp-block',  live && s.protocol === 'rtsp');

                  // Side cards — status + quick controls — only make sense while live.
                  show('live-card', live);
                  show('live-controls-card', live);

                  // Live preview hookup: only attach <img src=/video> when we know the
                  // MJPEG server is up, and bust the cache so the browser doesn't reuse
                  // a stale failed connection from idle state. Detach on stop so it
                  // doesn't endlessly retry against a dead server.
                  const mjpegLive = live && s.protocol === 'mjpeg';
                  const previewEl = document.getElementById('preview');
                  if (mjpegLive && !previewEl.dataset.live) {
                    previewEl.src = mjpegBase + '/video?t=' + Date.now();
                    previewEl.dataset.live = '1';
                  } else if (!mjpegLive && previewEl.dataset.live) {
                    previewEl.removeAttribute('src');
                    delete previewEl.dataset.live;
                  }

                  // Hide the snapshot button on RTSP — broadcaster has no frames there.
                  const snap = document.getElementById('snapshot-btn');
                  if (snap) snap.style.display = (s.protocol === 'rtsp') ? 'none' : '';

                  // JPEG quality slider only matters for MJPEG
                  show('quality-row', live && s.protocol === 'mjpeg');

                  // Resolution / FPS pickers — populated whenever the status carries the
                  // capability lists, so the Camera tab picker works idle *and* live, and
                  // the Quick-controls picker mirrors it while streaming.
                  if (Array.isArray(s.availableResolutions)) {
                    populateSelect(document.getElementById('res-sel'),     s.availableResolutions, s.resolution);
                    populateSelect(document.getElementById('res-sel-tab'), s.availableResolutions, s.resolution);
                  }
                  if (Array.isArray(s.availableFps)) {
                    populateSelect(document.getElementById('fps-sel'),     s.availableFps, s.targetFps);
                    populateSelect(document.getElementById('fps-sel-tab'), s.availableFps, s.targetFps);
                  }

                  // Protocol radios (idle screen only)
                  document.querySelectorAll('input[name=proto]').forEach(r => {
                    if (r !== document.activeElement) r.checked = (r.value === s.protocol);
                  });

                  // Stats grid
                  document.getElementById('s-proto').textContent = (s.protocol || '—').toUpperCase();
                  document.getElementById('s-lens').textContent = (s.lens || '—');
                  document.getElementById('s-fps').textContent = (s.fps || 0) + ' / ' + (s.targetFps || 0);
                  document.getElementById('s-clients').textContent = (s.clients || 0);
                  document.getElementById('s-res').textContent = (s.resolution || '—');
                  document.getElementById('s-zoomev').textContent =
                    (s.zoom != null ? s.zoom.toFixed(2) : '1.00') + 'x · ' +
                    (s.ev >= 0 ? '+' : '') + (s.ev || 0) + ' EV';

                  // VU meter
                  const peak = (s.audioPeakDbfs != null) ? s.audioPeakDbfs : -90;
                  const audioOn = !!s.audioEnabled && live;
                  document.getElementById('audio-stat').style.display = audioOn ? '' : 'none';
                  if (audioOn) {
                    const frac = Math.max(0, Math.min(1, (peak + 60) / 60));
                    const fill = document.getElementById('vu-fill');
                    fill.style.width = (frac * 100).toFixed(0) + '%';
                    fill.style.background = peak > -3 ? 'var(--alert)'
                      : peak > -12 ? 'var(--warn)' : 'var(--ok)';
                    document.getElementById('s-peak').textContent =
                      (peak <= -89) ? '—' : peak.toFixed(0) + ' dBFS';
                  }

                  // Mirror all Settings widgets with the server state.
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

                  // TLS fingerprint in the System tab
                  if (s.tlsFingerprint)
                    document.getElementById('tls-fingerprint').textContent = s.tlsFingerprint;

                  // Manual exposure: gate the row + clamp slider ranges
                  const manualOk = !!s.supportsManualSensor;
                  document.getElementById('manual-exp-unsupported').classList.toggle('hidden', manualOk);
                  document.querySelector('[data-setting="manualExposure"]').disabled = !manualOk;
                  const showManual = manualOk && s.manualExposure;
                  document.getElementById('iso-row').style.display = showManual ? '' : 'none';
                  document.getElementById('shutter-row').style.display = showManual ? '' : 'none';
                  if (Array.isArray(s.isoRange)) {
                    const isoEl = document.querySelector('[data-setting-range="iso"]');
                    isoEl.min = s.isoRange[0]; isoEl.max = s.isoRange[1];
                  }
                  if (Array.isArray(s.shutterRangeUs)) {
                    const sEl = document.querySelector('[data-setting-range="shutterUs"]');
                    sEl.min = s.shutterRangeUs[0]; sEl.max = s.shutterRangeUs[1];
                  }
                }

                refresh();
                setInterval(refresh, 1000);
              </script>
            </body></html>
        """.trimIndent()
    }

    companion object {
        private const val TAG = "WebControlServer"
    }
}
