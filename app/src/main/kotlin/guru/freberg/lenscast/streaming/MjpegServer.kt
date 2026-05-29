package guru.freberg.lenscast.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
 * Minimal MJPEG-over-HTTP server.
 *
 * Serves three endpoints:
 *   GET /          → small HTML landing page (handy for sanity-checking in a browser)
 *   GET /video     → multipart/x-mixed-replace MJPEG stream
 *   GET /shot.jpg  → single JPEG snapshot
 *
 * Each client gets its own coroutine that reads the latest frame from [broadcaster] on a
 * configurable cadence. Slow clients drop frames automatically.
 */
/**
 * Bridge that lets [MjpegServer]'s control endpoints reach back into the running app
 * without taking a direct dependency on `StreamingService` (which would create a circular
 * import). The service supplies one of these when starting the MJPEG path.
 */
interface MjpegControl {
    fun lensIsBack(): Boolean
    fun torchIsOn(): Boolean
    fun toggleTorch()
    fun switchLens()
    fun snapshot(): Boolean
    /** Returns false if a stream is already running or if the service can't start one. */
    fun startStream(): Boolean
    fun stopStream()
    fun zoomBy(factor: Float)
    fun nudgeExposure(delta: Int)
    fun toggleMirror()
    fun toggleContinuousAf()
    fun setJpegQuality(value: Int)
    fun jpegQuality(): Int
    /** Apply a resolution by label ("720p", "1080p", ...). Returns false if unknown. */
    fun setResolutionLabel(label: String): Boolean
    /** Apply a target FPS by value. Returns false if not in [Fps.entries]. */
    fun setFpsValue(value: Int): Boolean
    /** Switch the saved protocol ("mjpeg" or "rtsp"); clamps FPS to the new path's range. */
    fun setProtocol(value: String): Boolean
    /**
     * Update an arbitrary Settings field by name. Keys mirror the snake-case form used in
     * Settings UI; values are stringly-typed (bool: "true"/"false", enum: lowercase name,
     * int: decimal). Returns false on unknown key / parse failure / disallowed change
     * (e.g. ports while streaming). Implementation lives in StreamingService where it
     * can call into SettingsRepository + rebind the camera.
     */
    fun updateSetting(key: String, value: String): Boolean
    /** Single-line JSON for the browser to poll. Keys match the data-act button names. */
    fun statusJson(): String
    /** Pretty JSON dump of every Settings field, suitable for export. */
    fun exportSettingsJson(): String
    /** Replace the saved settings from a JSON blob. Returns false on parse failure or
     *  when the swap is not allowed mid-stream. */
    fun importSettingsJson(body: String): Boolean
    /** Close the streaming socket whose remote address matches `host:port`. Returns
     *  false when nothing matched (already disconnected, racing, or typo). */
    fun kickClient(remote: String): Boolean
    /** Browser-side WebRTC handshake. Pass the offer SDP + the HTTP peer's host:port for
     *  bookkeeping; receive back the SDP answer with all ICE candidates inlined. */
    fun webRtcAnswer(offerSdp: String, remoteHostPort: String): String?
    /** WHEP (WebRTC-HTTP Egress Protocol) session create. Pass the player's offer SDP + its
     *  host:port; receive `(resourceId, answerSdp)` so the server can return a `201 Created`
     *  with a `Location: /whep/<resourceId>` the player can later DELETE. Null if WebRTC
     *  isn't running. */
    fun webRtcWhepCreate(offerSdp: String, remoteHostPort: String): Pair<String, String>?
    /** WHEP resource teardown by id (the `DELETE /whep/<id>` path tail). False if no match. */
    fun webRtcWhepDelete(resourceId: String): Boolean
    /** Re-queue the last finished MP4 for SFTP upload. Returns false if nothing to upload. */
    fun retryLastSftpUpload(): Boolean
    /** One-line JSON snapshot of the SFTP queue + last error / success. */
    fun sftpStatusJson(): String
    /** Save the current streaming-shape settings (protocol/resolution/fps/lens) as a named
     *  preset. Returns false on a blank name or while streaming. */
    fun savePreset(name: String): Boolean
    /** Apply a saved preset by name (sets protocol/resolution/fps/lens). Returns false on an
     *  unknown name or while streaming. */
    fun applyPreset(name: String): Boolean
    /** Delete a saved preset by name. Returns false if no preset matched. */
    fun deletePreset(name: String): Boolean
}

class MjpegServer(
    private val broadcaster: FrameBroadcaster,
    private val port: Int,
    private val targetFps: Int,
    /** HTTP Basic auth username — only meaningful when [password] is non-empty. */
    private val username: String = "Lenscast",
    /** Optional HTTP Basic auth password. Empty = open access; [username] is then unused. */
    private val password: String = "",
    /** Non-null when MJPEG-path audio is also being streamed. Drives the `/audio` endpoint. */
    private val audioBroadcaster: AudioBroadcaster? = null,
    /** When non-null, the server serves HTTPS instead of HTTP. */
    private val sslContext: SSLContext? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    @Volatile private var running = false
    private val txBytes = java.util.concurrent.atomic.AtomicLong(0)

    // Only sockets currently streaming /video or /audio — short-lived shot/landing/control
    // requests would otherwise dominate the list and the "Drop client" button wouldn't do
    // anything useful for the user.
    private val streamingClients: MutableSet<Socket> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())

    /** Total bytes shipped to clients (MJPEG + audio + landing/status) since [start]. */
    fun bytesSent(): Long = txBytes.get()

    /** `host:port` of every client currently consuming `/video` or `/audio`. */
    fun clientAddresses(): List<String> = streamingClients.mapNotNull { s ->
        (s.remoteSocketAddress as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" }
    }

    /** Closes the streaming socket matching `host:port`. Returns true if one was closed. */
    fun kickClient(remote: String): Boolean {
        val match = streamingClients.firstOrNull { s ->
            (s.remoteSocketAddress as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" } == remote
        } ?: return false
        try { match.close() } catch (_: Throwable) {}
        return true
    }

    fun start() {
        if (running) return
        running = true
        acceptJob = scope.launchHttpAcceptLoop(
            port, sslContext, TAG, "MJPEG",
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
        // Count this connection as a "client" only if it ends up consuming the MJPEG
        // stream. Control / status / landing / snapshot are short-lived; counting them
        // would make /status itself appear as a phantom client on every 1 Hz poll.
        var countedAsClient = false
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return@withContext
            // Capture Authorization header before discarding the rest — needed for Basic auth.
            var authorization: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Authorization:", ignoreCase = true)) {
                    authorization = line.substringAfter(':').trim()
                }
            }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            if (password.isNotEmpty() && !isAuthorized(authorization)) {
                writeUnauthorized(out)
                return@withContext
            }
            when {
                path.startsWith("/video") -> {
                    countedAsClient = true
                    streamingClients.add(socket)
                    broadcaster.onClientConnected()
                    writeMjpegStream(out)
                }
                path.startsWith("/audio") -> {
                    val ab = audioBroadcaster
                    if (ab == null) writeAudioNotAvailable(out)
                    else {
                        streamingClients.add(socket)
                        ab.onClientConnected()
                        try { writeAudioStream(out, ab) } finally { ab.onClientDisconnected() }
                    }
                }
                path.startsWith("/shot")  -> writeSingleShot(out)
                path.startsWith("/speedtest") -> writeSpeedTest(out, path)
                else -> writeSimpleLanding(out)
            }
        } catch (_: SocketException) {
            // Client disconnected — normal.
        } catch (t: Throwable) {
            Log.w(TAG, "Client handler error: ${t.message}")
        } finally {
            streamingClients.remove(socket)
            try { socket.close() } catch (_: Throwable) {}
            if (countedAsClient) broadcaster.onClientDisconnected()
        }
    }

    private fun isAuthorized(headerValue: String?): Boolean {
        if (headerValue == null) return false
        if (!headerValue.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            val raw = headerValue.substringAfter(' ').trim()
            String(android.util.Base64.decode(raw, android.util.Base64.NO_WRAP))
        } catch (_: Throwable) { return false }
        val colon = decoded.indexOf(':')
        if (colon < 0) return false
        val user = decoded.substring(0, colon)
        val pwd = decoded.substring(colon + 1)
        return guru.freberg.lenscast.net.AuthUtils.constantTimeEquals(user, username) &&
            guru.freberg.lenscast.net.AuthUtils.constantTimeEquals(pwd, password)
    }

    private fun writeUnauthorized(out: OutputStream) {
        val body = "Authentication required".toByteArray(Charsets.US_ASCII)
        val header = ("HTTP/1.0 401 Unauthorized\r\n" +
            "WWW-Authenticate: Basic realm=\"Lenscast\", charset=\"UTF-8\"\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try {
            out.write(header)
            out.write(body)
            out.flush()
        } catch (_: IOException) { /* ignored */ }
    }

    private suspend fun writeMjpegStream(out: OutputStream) {
        out.write(STREAM_RESPONSE_HEADER)
        out.flush()

        val frameIntervalMs = (1000L / targetFps.coerceAtLeast(1))
        var lastSeq = -1L
        val partPrefix = PART_PREFIX
        // Reusable scratch for building "<size>\r\n\r\n" between the prefix and the JPEG.
        // Size grows naturally as resolutions change; never per-frame allocation after warmup.
        val sizeSuffix = StringBuilder(16)
        while (running) {
            val tickStart = System.currentTimeMillis()
            val frame = broadcaster.latest()
            if (frame != null && frame.second != lastSeq) {
                val (bytes, seq) = frame
                lastSeq = seq
                sizeSuffix.setLength(0)
                sizeSuffix.append(bytes.size).append("\r\n\r\n")
                val sizeBytes = sizeSuffix.toString().toByteArray(Charsets.US_ASCII)
                // Single coalesced buffer: --boundary\r\nContent-Type: image/jpeg\r\nContent-Length: N\r\n\r\n<JPEG>\r\n
                val total = partPrefix.size + sizeBytes.size + bytes.size + TRAILER.size
                val packet = ByteArray(total)
                var off = 0
                System.arraycopy(partPrefix, 0, packet, off, partPrefix.size); off += partPrefix.size
                System.arraycopy(sizeBytes, 0, packet, off, sizeBytes.size); off += sizeBytes.size
                System.arraycopy(bytes, 0, packet, off, bytes.size); off += bytes.size
                System.arraycopy(TRAILER, 0, packet, off, TRAILER.size)
                try {
                    out.write(packet)
                    out.flush()
                    txBytes.addAndGet(packet.size.toLong())
                } catch (_: IOException) {
                    return
                }
            }
            val elapsed = System.currentTimeMillis() - tickStart
            val sleep = frameIntervalMs - elapsed
            if (sleep > 0) delay(sleep)
        }
    }

    /**
     * Sinks `bytes=N` (default 10 MB, clamped to 100 MB) of zeros so the receiver can measure
     * raw LAN throughput. Pure write loop — no camera frames involved. The body counts toward
     * `bytesSent()` so it shows up in the stats bitrate chip if anyone runs the test live.
     */
    private fun writeSpeedTest(out: OutputStream, path: String) {
        val requested = path.substringAfter('?', "").split('&')
            .firstOrNull { it.startsWith("bytes=") }?.substringAfter('=')?.toLongOrNull()
            ?: 10_000_000L
        val total = requested.coerceIn(1_000L, 100_000_000L)
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: application/octet-stream\r\n" +
            "Content-Length: $total\r\n\r\n").toByteArray(Charsets.US_ASCII)
        out.write(header)
        txBytes.addAndGet(header.size.toLong())
        val chunk = ByteArray(64 * 1024)
        var remaining = total
        while (remaining > 0) {
            val n = minOf(chunk.size.toLong(), remaining).toInt()
            out.write(chunk, 0, n)
            remaining -= n
            txBytes.addAndGet(n.toLong())
        }
        out.flush()
    }

    private fun writeSingleShot(out: OutputStream) {
        val frame = broadcaster.latest()
        if (frame == null) {
            out.write(SHOT_503_HEADER)
            out.write(NO_FRAME_BODY)
            out.flush()
            return
        }
        val bytes = frame.first
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: ${bytes.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        out.write(header)
        out.write(bytes)
        out.flush()
    }

    /**
     * Stream PCM-16LE wrapped in a WAV container. The RIFF / data chunk sizes are set to
     * `0xFFFFFFFF` so receivers treat the response as an open-ended live stream rather
     * than trying to seek to a finite end — this is the standard "streaming WAV" trick.
     *
     * Why not AAC: codec lookahead plus receiver-side AAC buffering produced multi-hundred
     * millisecond lag. PCM has no codec stage and players (browsers' `<audio>`, VLC, ffplay)
     * treat WAV as low-buffer live audio. 44.1 kHz mono is ~88 KB/s — trivial overhead
     * next to the MJPEG video.
     */
    private suspend fun writeAudioStream(out: OutputStream, ab: AudioBroadcaster) {
        val sampleRate = ab.sampleRate
        val channels = ab.channels
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Server: Lenscast\r\n" +
            "Cache-Control: no-cache, no-store\r\n" +
            "Connection: close\r\n" +
            "Content-Type: audio/wav\r\n\r\n").toByteArray(Charsets.US_ASCII)
        val wavHeader = makeStreamingWavHeader(sampleRate, channels)
        try {
            out.write(header)
            out.write(wavHeader)
            out.flush()
        } catch (_: IOException) { return }

        val ch = ab.subscribe()
        try {
            while (running) {
                val chunk = ch.receiveCatching().getOrNull() ?: return
                try {
                    out.write(chunk)
                    out.flush()
                    txBytes.addAndGet(chunk.size.toLong())
                } catch (_: IOException) { return }
            }
        } finally {
            ab.unsubscribe(ch)
        }
    }

    /**
     * 44-byte WAV header for 16-bit PCM with claimed-infinite chunk sizes. Bits per
     * sample fixed at 16; sample rate and channels parameterised. Players that respect
     * the `0xFFFFFFFF` length sentinel keep reading until the socket closes.
     */
    private fun makeStreamingWavHeader(sampleRate: Int, channels: Int): ByteArray {
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val h = ByteArray(44)
        // RIFF chunk
        h[0]='R'.code.toByte();   h[1]='I'.code.toByte();   h[2]='F'.code.toByte();   h[3]='F'.code.toByte()
        putI32(h, 4, -1)           // size sentinel
        h[8]='W'.code.toByte();   h[9]='A'.code.toByte();   h[10]='V'.code.toByte();  h[11]='E'.code.toByte()
        // fmt sub-chunk
        h[12]='f'.code.toByte();  h[13]='m'.code.toByte();  h[14]='t'.code.toByte();  h[15]=' '.code.toByte()
        putI32(h, 16, 16)          // PCM fmt chunk size
        putI16(h, 20, 1)           // audio format = PCM
        putI16(h, 22, channels)
        putI32(h, 24, sampleRate)
        putI32(h, 28, byteRate)
        putI16(h, 32, blockAlign)
        putI16(h, 34, bitsPerSample)
        // data sub-chunk
        h[36]='d'.code.toByte();  h[37]='a'.code.toByte();  h[38]='t'.code.toByte();  h[39]='a'.code.toByte()
        putI32(h, 40, -1)          // size sentinel
        return h
    }

    private fun putI16(h: ByteArray, off: Int, v: Int) {
        h[off]     = (v and 0xFF).toByte()
        h[off + 1] = ((v shr 8) and 0xFF).toByte()
    }
    private fun putI32(h: ByteArray, off: Int, v: Int) {
        h[off]     = (v and 0xFF).toByte()
        h[off + 1] = ((v shr 8) and 0xFF).toByte()
        h[off + 2] = ((v shr 16) and 0xFF).toByte()
        h[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    private fun writeAudioNotAvailable(out: OutputStream) {
        val body = "Audio is disabled. Enable it in Settings.".toByteArray(Charsets.US_ASCII)
        val header = ("HTTP/1.0 503 Service Unavailable\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try { out.write(header); out.write(body); out.flush() } catch (_: IOException) {}
    }

    /**
     * Lightweight landing page for the MJPEG port. The rich control panel lives on the
     * separate [WebControlServer] port — this one is just the video / audio / snapshot
     * endpoints. Direct visitors get a one-line pointer and an embedded preview.
     */
    private fun writeSimpleLanding(out: OutputStream) {
        val html = """
            <!doctype html>
            <html><head><title>Lenscast</title>
            <meta name="viewport" content="width=device-width,initial-scale=1">
            <style>
              body{background:#14121c;color:#eee;font-family:system-ui,sans-serif;margin:0;padding:24px;text-align:center}
              img{max-width:100%;border-radius:12px;box-shadow:0 8px 32px rgba(120,73,242,0.3)}
              code{background:#262335;padding:2px 8px;border-radius:6px}
            </style></head>
            <body>
              <h1>Lenscast — MJPEG endpoint</h1>
              <p><code>/video</code> · <code>/shot.jpg</code> · <code>/audio</code> (when enabled)</p>
              <img src="/video" alt="Live preview">
            </body></html>
        """.trimIndent().toByteArray(Charsets.UTF_8)
        val header = ("HTTP/1.0 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Content-Length: ${html.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
        try {
            out.write(header)
            out.write(html)
            out.flush()
        } catch (_: IOException) {}
    }

    companion object {
        private const val TAG = "MjpegServer"
        private const val BOUNDARY = "lenscastframe"
        private val TRAILER = "\r\n".toByteArray(Charsets.US_ASCII)

        private val STREAM_RESPONSE_HEADER = (
            "HTTP/1.0 200 OK\r\n" +
            "Server: Lenscast\r\n" +
            "Cache-Control: no-cache, no-store, must-revalidate\r\n" +
            "Pragma: no-cache\r\n" +
            "Connection: close\r\n" +
            "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

        // The fixed prefix of every multipart part: boundary line + image content-type +
        // "Content-Length: ". The numeric length + CRLF CRLF is appended per-frame.
        private val PART_PREFIX = (
            "--$BOUNDARY\r\n" +
            "Content-Type: image/jpeg\r\n" +
            "Content-Length: "
        ).toByteArray(Charsets.US_ASCII)

        private val SHOT_503_HEADER = (
            "HTTP/1.0 503 Service Unavailable\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: 12\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)
        private val NO_FRAME_BODY = "No frame yet".toByteArray(Charsets.US_ASCII)
    }
}
