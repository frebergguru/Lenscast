package dev.lenscast.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
class MjpegServer(
    private val broadcaster: FrameBroadcaster,
    private val port: Int,
    private val targetFps: Int,
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
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                server = s
                Log.i(TAG, "MJPEG server listening on 0.0.0.0:$port")
                while (running && isActive) {
                    val client = try { s.accept() } catch (e: IOException) { break }
                    client.tcpNoDelay = true
                    client.soTimeout = 5_000
                    launch { handle(client) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Accept loop crashed", t)
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
        broadcaster.onClientConnected()
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return@withContext
            // Drain remaining headers; we don't actually use them.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val out = socket.getOutputStream()
            when {
                path.startsWith("/video") -> writeMjpegStream(out)
                path.startsWith("/shot")  -> writeSingleShot(out)
                else                       -> writeLanding(out)
            }
        } catch (_: SocketException) {
            // Client disconnected — normal.
        } catch (t: Throwable) {
            Log.w(TAG, "Client handler error: ${t.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
            broadcaster.onClientDisconnected()
        }
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
                } catch (_: IOException) {
                    return
                }
            }
            val elapsed = System.currentTimeMillis() - tickStart
            val sleep = frameIntervalMs - elapsed
            if (sleep > 0) delay(sleep)
        }
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

    private fun writeLanding(out: OutputStream) {
        out.write(LANDING_RESPONSE)
        out.flush()
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

        private val LANDING_RESPONSE: ByteArray = run {
            val html = """
                <!doctype html>
                <html><head><title>Lenscast</title>
                <style>
                  body{background:#14121c;color:#eee;font-family:system-ui,sans-serif;margin:0;padding:24px;text-align:center}
                  h1{font-weight:600;margin:0 0 12px}
                  img{max-width:100%;border-radius:12px;box-shadow:0 8px 32px rgba(120,73,242,0.3)}
                  code{background:#262335;padding:2px 8px;border-radius:6px}
                </style></head>
                <body>
                  <h1>Lenscast</h1>
                  <p>MJPEG stream at <code>/video</code> · snapshot at <code>/shot.jpg</code></p>
                  <img src="/video" alt="Live preview">
                </body></html>
            """.trimIndent().toByteArray()
            val header = ("HTTP/1.0 200 OK\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${html.size}\r\n\r\n").toByteArray(Charsets.US_ASCII)
            header + html
        }
    }
}
