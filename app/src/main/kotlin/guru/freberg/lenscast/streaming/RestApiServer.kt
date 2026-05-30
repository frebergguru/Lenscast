package guru.freberg.lenscast.streaming

import android.util.Log
import guru.freberg.lenscast.net.AuthUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import javax.net.ssl.SSLContext

/**
 * Machine-facing REST API for third-party control apps and plugins (full reference in
 * Docs/API.md). Runs on its own port, independent of the MJPEG / RTSP / web-control ports,
 * for the whole lifetime of [StreamingService].
 *
 * Design notes:
 *  - **Everything routes through [MjpegControl]** — the same bridge the browser panel uses.
 *    That is deliberate: every blocking gate (stream-locked settings, the RIST listener⇒Main
 *    coercion, the "can't change protocol / import / save preset mid-stream" guards, the
 *    start-once guard) lives behind that interface, so this server inherits all of them
 *    without re-implementing any. A `false` return from a control method becomes a `409`/`400`.
 *  - **JSON in, JSON out**, with real HTTP status codes — friendlier for SDK/plugin authors
 *    than the panel's `text/plain "ok"` convention.
 *  - **Bearer-token auth on every route.** The token (256-bit, sealed at rest) is compared in
 *    constant time. Because the credential is a header the caller must already hold — never an
 *    ambient cookie — cross-site requests can't forge it, so no CSRF Origin check is needed and
 *    permissive CORS is safe (browser plugins can call it). The server is only ever started by
 *    [StreamingService] when a non-blank token exists (fail-closed); the blank-token check here
 *    is just defence in depth.
 */
class RestApiServer(
    private val port: Int,
    private val control: MjpegControl,
    /** The bearer token, read live so a rotation takes effect without restarting the server. */
    private val bearerToken: () -> String,
    /** When non-null, serve HTTPS — protecting the bearer token in transit. */
    private val sslContext: SSLContext? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        acceptJob = scope.launchHttpAcceptLoop(
            port, sslContext, TAG, "REST API",
            isRunning = { running },
            onBound = { server = it },
            handle = ::handle,
        )
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
            var authorization: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                } else if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authorization = line.substringAfter(':').trim()
                }
            }
            val parts = requestLine.split(' ')
            val method = parts.getOrNull(0).orEmpty().uppercase()
            val target = parts.getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            val path = target.substringBefore('?')

            // CORS preflight — answer before auth so browser-based plugins can discover the
            // API; the credentialed real request that follows is authenticated normally.
            if (method == "OPTIONS") { writePreflight(out); return@withContext }

            val token = bearerToken()
            if (token.isEmpty()) { // fail-closed: should never be reached (server wouldn't bind)
                writeError(out, "503 Service Unavailable", "api_disabled", "API token not configured")
                return@withContext
            }
            if (!isAuthorized(authorization, token)) {
                writeUnauthorized(out)
                return@withContext
            }

            if (contentLength > MAX_BODY_BYTES) {
                writeError(out, "413 Payload Too Large", "too_large",
                    "body exceeds $MAX_BODY_BYTES bytes")
                return@withContext
            }
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

            route(out, method, path, ::readBody)
        } catch (_: SocketException) { /* client vanished — normal */ }
        catch (t: Throwable) { Log.w(TAG, "Client handler error: ${t.message}") }
        finally { try { socket.close() } catch (_: Throwable) {} }
    }

    private fun route(out: OutputStream, method: String, path: String, readBody: () -> String) {
        // Only the v1 namespace is served; anything else is a clear 404 for forward-compat.
        if (path != "/api/v1" && !path.startsWith("/api/v1/")) {
            writeError(out, "404 Not Found", "not_found", "no such endpoint: $path")
            return
        }
        when (path) {
            "/api/v1", "/api/v1/" -> if (method == "GET") writeRaw(out, "200 OK", DISCOVERY)
                else writeMethodNotAllowed(out)

            // ── Read-only state ────────────────────────────────────────────────────────────
            "/api/v1/status" -> if (method == "GET") writeRaw(out, "200 OK", control.statusJson())
                else writeMethodNotAllowed(out)
            "/api/v1/settings" -> when (method) {
                "GET" -> writeRaw(out, "200 OK", control.exportSettingsJson())
                "PUT" -> {
                    val ok = runCatching { control.importSettingsJson(readBody()) }.getOrDefault(false)
                    if (ok) writeOk(out)
                    // false = bad JSON or a mid-stream swap; the latter is the common case.
                    else if (isStreaming()) writeError(out, "409 Conflict", "stream_locked",
                        "stop the stream before importing settings")
                    else writeError(out, "400 Bad Request", "invalid", "malformed settings JSON")
                }
                "PATCH" -> patchSettings(out, readBody())
                else -> writeMethodNotAllowed(out)
            }

            // ── Stream lifecycle ───────────────────────────────────────────────────────────
            "/api/v1/stream/start" -> requirePost(out, method) {
                if (control.startStream()) writeOk(out)
                else writeError(out, "409 Conflict", "already_streaming",
                    "cannot start — already streaming or service not ready")
            }
            "/api/v1/stream/stop" -> requirePost(out, method) { control.stopStream(); writeOk(out) }

            // ── Camera quick-controls ──────────────────────────────────────────────────────
            "/api/v1/camera/lens" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                if (o.has("lens")) {
                    if (control.updateSetting("lens", o.getString("lens"))) writeOk(out)
                    else writeError(out, "400 Bad Request", "invalid", "lens must be 'back' or 'front'")
                } else { control.switchLens(); writeOk(out) }
            }
            "/api/v1/camera/torch" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                if (o.has("on")) { if (o.getBoolean("on") != control.torchIsOn()) control.toggleTorch() }
                else control.toggleTorch()
                writeOk(out, JSONObject().put("torch", control.torchIsOn()))
            }
            "/api/v1/camera/mirror" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                if (o.has("on")) control.updateSetting("mirror", o.getBoolean("on").toString())
                else control.toggleMirror()
                writeOk(out)
            }
            "/api/v1/camera/af" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                if (o.has("on")) control.updateSetting("continuousAf", o.getBoolean("on").toString())
                else control.toggleContinuousAf()
                writeOk(out)
            }
            "/api/v1/camera/zoom" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val factor = o.optDouble("factor", Double.NaN)
                if (factor.isNaN() || factor <= 0) {
                    writeError(out, "400 Bad Request", "invalid", "body needs a positive {\"factor\":<number>}")
                } else { control.zoomBy(factor.toFloat()); writeOk(out) }
            }
            "/api/v1/camera/exposure" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                when {
                    o.has("ev")    -> { control.updateSetting("exposureEv", o.getInt("ev").toString()); writeOk(out) }
                    o.has("delta") -> { control.nudgeExposure(o.getInt("delta")); writeOk(out) }
                    else -> writeError(out, "400 Bad Request", "invalid",
                        "body needs {\"ev\":<int>} (absolute) or {\"delta\":<int>} (relative)")
                }
            }
            "/api/v1/camera/resolution" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val v = o.optString("value", "")
                if (v.isNotEmpty() && control.setResolutionLabel(v)) writeOk(out)
                else writeError(out, "400 Bad Request", "invalid", "unknown resolution label: '$v'")
            }
            "/api/v1/camera/fps" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val v = o.optInt("value", -1)
                if (v > 0 && control.setFpsValue(v)) writeOk(out)
                else writeError(out, "400 Bad Request", "invalid", "unsupported fps: $v")
            }
            "/api/v1/camera/protocol" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val v = o.optString("value", "")
                if (v.isNotEmpty() && control.setProtocol(v)) writeOk(out)
                // setProtocol rejects unknown values and mid-stream switches alike.
                else if (isStreaming()) writeError(out, "409 Conflict", "stream_locked",
                    "stop the stream before changing protocol")
                else writeError(out, "400 Bad Request", "invalid",
                    "protocol must be one of mjpeg|rtsp|webrtc|srt|rist")
            }
            "/api/v1/camera/quality" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val v = o.optInt("value", -1)
                if (v in 1..100) { control.setJpegQuality(v); writeOk(out) }
                else writeError(out, "400 Bad Request", "invalid", "value must be 10..95")
            }
            "/api/v1/camera/snapshot" -> requirePost(out, method) {
                if (control.snapshot()) writeOk(out)
                else writeError(out, "409 Conflict", "no_frame",
                    "no frame available (snapshot needs MJPEG or the sidecar running)")
            }

            // ── Clients / SFTP / presets ───────────────────────────────────────────────────
            "/api/v1/clients/kick" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val remote = o.optString("remote", "")
                if (remote.isNotEmpty() && control.kickClient(remote)) writeOk(out)
                else writeError(out, "404 Not Found", "no_match", "no client matches '$remote'")
            }
            "/api/v1/sftp" -> if (method == "GET") writeRaw(out, "200 OK", control.sftpStatusJson())
                else writeMethodNotAllowed(out)
            "/api/v1/sftp/retry" -> requirePost(out, method) {
                if (control.retryLastSftpUpload()) writeOk(out)
                else writeError(out, "409 Conflict", "nothing_to_upload", "no recent recording to upload")
            }
            "/api/v1/presets/save" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val name = o.optString("name", "")
                if (control.savePreset(name)) writeOk(out)
                else writeError(out, "409 Conflict", "rejected", "blank name or stream running")
            }
            "/api/v1/presets/apply" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val name = o.optString("name", "")
                if (control.applyPreset(name)) writeOk(out)
                else writeError(out, "409 Conflict", "rejected", "unknown preset or stream running")
            }
            "/api/v1/presets/delete" -> requirePost(out, method) {
                val o = parseBody(out, readBody()) ?: return@requirePost
                val name = o.optString("name", "")
                if (control.deletePreset(name)) writeOk(out)
                else writeError(out, "404 Not Found", "no_match", "no preset named '$name'")
            }

            else -> writeError(out, "404 Not Found", "not_found", "no such endpoint: $path")
        }
    }

    /**
     * `PATCH /settings` — a partial settings update. Body is a flat JSON object of
     * `{ key: value }`, each routed through [MjpegControl.updateSetting] (so every per-key
     * gate applies). Always replies `200` with a per-key breakdown rather than failing the
     * whole batch on one bad key — a plugin can then surface exactly which knobs were locked.
     */
    private fun patchSettings(out: OutputStream, body: String) {
        val o = parseBody(out, body) ?: return
        val streaming = isStreaming()
        val applied = org.json.JSONArray()
        val rejected = JSONObject()
        for (key in o.keys()) {
            // Settings values are bool/int/long/string; updateSetting takes them stringly-typed
            // (enums lowercased internally), so a plain toString covers every case.
            val value = o.get(key).toString()
            if (control.updateSetting(key, value)) {
                applied.put(key)
            } else {
                rejected.put(key, if (streaming) "locked while streaming" else "unknown key or invalid value")
            }
        }
        val payload = JSONObject()
            .put("ok", rejected.length() == 0)
            .put("applied", applied)
            .put("rejected", rejected)
        writeRaw(out, "200 OK", payload.toString())
    }

    /** True when a stream is live — used to phrase a rejection as "locked" vs "invalid",
     *  mirroring the web panel. Derived from the canonical status payload. */
    private fun isStreaming(): Boolean =
        control.statusJson().contains("\"state\":\"streaming\"")

    private inline fun requirePost(out: OutputStream, method: String, action: () -> Unit) {
        if (method == "POST") action() else writeMethodNotAllowed(out)
    }

    /** Parse a request body as a JSON object. Empty body → `{}` (routes treat absent fields as
     *  defaults). Malformed JSON writes a 400 and returns null so the caller bails out. */
    private fun parseBody(out: OutputStream, body: String): JSONObject? {
        if (body.isBlank()) return JSONObject()
        return try { JSONObject(body) } catch (_: Throwable) {
            writeError(out, "400 Bad Request", "invalid", "malformed JSON body"); null
        }
    }

    private fun isAuthorized(header: String?, token: String): Boolean {
        if (header == null || !header.startsWith("Bearer ", ignoreCase = true)) return false
        return AuthUtils.constantTimeEquals(header.substringAfter(' ').trim(), token)
    }

    // ── Response writers ────────────────────────────────────────────────────────────────────
    // Permissive CORS throughout: the API is guarded by a bearer token (not a cookie), so a
    // cross-origin caller gains nothing without the secret it must already hold.

    private fun writeOk(out: OutputStream, extra: JSONObject? = null) {
        val o = (extra ?: JSONObject()).put("ok", true)
        writeRaw(out, "200 OK", o.toString())
    }

    private fun writeError(out: OutputStream, status: String, code: String, message: String) {
        val o = JSONObject().put("ok", false).put("error", code).put("message", message)
        writeRaw(out, status, o.toString())
    }

    private fun writeRaw(out: OutputStream, status: String, json: String) {
        val body = json.toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 $status\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeUnauthorized(out: OutputStream) {
        val body = "{\"ok\":false,\"error\":\"unauthorized\",\"message\":\"missing or invalid bearer token\"}"
            .toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 401 Unauthorized\r\n" +
            "WWW-Authenticate: Bearer realm=\"Lenscast API\"\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Content-Length: ${body.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    private fun writeMethodNotAllowed(out: OutputStream) =
        writeError(out, "405 Method Not Allowed", "method_not_allowed", "method not allowed on this endpoint")

    private fun writePreflight(out: OutputStream) {
        val header = ("HTTP/1.0 204 No Content\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Methods: GET, POST, PUT, PATCH, OPTIONS\r\n" +
            "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
            "Access-Control-Max-Age: 86400\r\n" +
            "Content-Length: 0\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.flush() } catch (_: IOException) {}
    }

    private companion object {
        const val TAG = "RestApiServer"
        // SDP-less control payloads are tiny; a settings import is the largest body and is a
        // few KB. Cap well below that ceiling to stop an unbounded-allocation attempt.
        const val MAX_BODY_BYTES = 256 * 1024

        // Static discovery document for `GET /api/v1` — lets a client confirm it's talking to
        // Lenscast and enumerate the surface without baking the list into the plugin.
        val DISCOVERY = JSONObject()
            .put("name", "Lenscast REST API")
            .put("version", "v1")
            .put("docs", "https://github.com/ — see Docs/API.md")
            .put("endpoints", org.json.JSONArray(listOf(
                "GET /api/v1/status", "GET /api/v1/settings", "PUT /api/v1/settings",
                "PATCH /api/v1/settings", "POST /api/v1/stream/start", "POST /api/v1/stream/stop",
                "POST /api/v1/camera/lens", "POST /api/v1/camera/torch", "POST /api/v1/camera/mirror",
                "POST /api/v1/camera/af", "POST /api/v1/camera/zoom", "POST /api/v1/camera/exposure",
                "POST /api/v1/camera/resolution", "POST /api/v1/camera/fps", "POST /api/v1/camera/protocol",
                "POST /api/v1/camera/quality", "POST /api/v1/camera/snapshot", "POST /api/v1/clients/kick",
                "GET /api/v1/sftp", "POST /api/v1/sftp/retry", "POST /api/v1/presets/save",
                "POST /api/v1/presets/apply", "POST /api/v1/presets/delete",
            )))
            .toString()
    }
}
