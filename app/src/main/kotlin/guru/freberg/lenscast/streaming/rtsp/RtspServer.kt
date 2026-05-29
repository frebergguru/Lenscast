package guru.freberg.lenscast.streaming.rtsp

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import java.security.SecureRandom

/**
 * Minimal multi-client RTSP/1.0 server. The streaming path is **TCP-interleaved** —
 * RTP packets share the RTSP control TCP socket, framed by a 4-byte `$<channel><len16>`
 * preamble per RFC 2326 §10.12. Two-port UDP is also supported per-session.
 *
 * Every new RTSP connection is served on its own coroutine; the manager fans each
 * encoder NAL/AAC packet out to every active session's [OutgoingPacketSink]. Channels
 * are 0/1 for video RTP/RTCP and 2/3 for audio RTP/RTCP.
 */
class RtspServer(
    private val port: Int,
    private val streamProvider: StreamProvider,
    /** Optional HTTP Basic auth credentials. Empty password = open access. */
    private val authUsername: String = "Lenscast",
    private val authPassword: String = "",
    /** When non-null the server speaks `rtsps://` instead of `rtsp://` — uses the same
     *  SSLContext we already built for HTTPS. */
    private val sslContext: javax.net.ssl.SSLContext? = null,
) {
    /** Provides per-session information: SDP, the SPS/PPS bytes, and a starter hook. */
    interface StreamProvider {
        /** Latest video parameters; null if the encoder hasn't produced SPS/PPS yet. */
        fun videoTrack(): Sdp.VideoTrack?
        /** Latest audio parameters; null if audio is disabled or not yet configured. */
        fun audioTrack(): Sdp.AudioTrack?
        /** Next RTP seq/timestamp pair for each stream — used to populate `RTP-Info` in PLAY responses. */
        fun videoRtpInfo(): RtpInfo
        fun audioRtpInfo(): RtpInfo
        /**
         * Called when a client transitions to PLAY. The manager wires the encoder's NAL/AAC
         * outputs into the returned sink so packets flow.
         */
        fun onClientPlay(sink: OutgoingPacketSink)
        /** Called when this particular client tears down or disconnects. */
        fun onClientTeardown(sink: OutgoingPacketSink)
        /**
         * Called whenever a client reports back via RTCP RR. Fed by the per-session RR
         * reader (UDP path today; TCP-interleaved RR is still on the to-do). The manager
         * uses these for the adaptive-bitrate loop.
         */
        fun onReceiverReport(report: Rtcp.ReceiverReport) {}
    }

    data class RtpInfo(val seq: Int, val rtpTime: Int, val ssrc: Int)

    /**
     * Channel mapping for TCP-interleaved frames. The manager pushes RTP packets here;
     * the server prepends the `$<channel><len>` preamble and writes them.
     */
    interface OutgoingPacketSink {
        fun sendVideo(rtp: ByteArray)
        fun sendAudio(rtp: ByteArray)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var acceptJob: Job? = null
    private val activeClients: MutableSet<Socket> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var running = false
    @Volatile var playingClients: Int = 0
        private set
    private val txBytes = AtomicLong(0)

    /** Total bytes shipped to any RTSP client (RTP + RTSP responses) since [start]. */
    fun bytesSent(): Long = txBytes.get()

    fun start() {
        if (running) return
        running = true
        acceptJob = scope.launch {
            try {
                val s = if (sslContext != null) {
                    sslContext.serverSocketFactory.createServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                } else {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(port))
                    }
                }
                server = s
                Log.i(TAG, "RTSP${if (sslContext != null) "S" else ""} listening on 0.0.0.0:$port")
                while (running && isActive) {
                    val client = try { s.accept() } catch (_: IOException) { break }
                    client.tcpNoDelay = true
                    client.soTimeout = 0
                    activeClients.add(client)
                    launch { handle(client) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Accept loop crashed", t)
            }
        }
    }

    fun stop() {
        running = false
        for (c in activeClients) try { c.close() } catch (_: Throwable) {}
        activeClients.clear()
        try { server?.close() } catch (_: Throwable) {}
        server = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
    }

    private suspend fun handle(socket: Socket) {
        Log.i(TAG, "Client connected from ${socket.remoteSocketAddress}")
        val session = RtspSession(socket, streamProvider, authUsername, authPassword, txBytes)
        try {
            session.serve()
        } catch (_: SocketException) {
            // Normal disconnect.
        } catch (t: Throwable) {
            Log.w(TAG, "Session error: ${t.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
            session.onClosed()
            activeClients.remove(socket)
            Log.i(TAG, "Client ${socket.remoteSocketAddress} disconnected")
        }
    }

    /** Number of TCP-connected RTSP clients (any state, including pre-PLAY). */
    fun connectedClientCount(): Int = activeClients.size

    /** Snapshot of every connected client's remote address, in `host:port` form. */
    fun clientAddresses(): List<String> = activeClients.mapNotNull { sock ->
        (sock.remoteSocketAddress as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" }
    }

    /** Closes the socket whose remote address matches `host:port`. Returns true if one was closed. */
    fun kickClient(remote: String): Boolean {
        val match = activeClients.firstOrNull { sock ->
            (sock.remoteSocketAddress as? InetSocketAddress)?.let { "${it.address.hostAddress}:${it.port}" } == remote
        } ?: return false
        try { match.close() } catch (_: Throwable) {}
        return true
    }

    companion object {
        private const val TAG = "RtspServer"
    }
}

/**
 * Per-client RTSP state machine. Lives for one TCP connection's lifetime.
 */
class RtspSession(
    private val socket: Socket,
    private val provider: RtspServer.StreamProvider,
    private val authUsername: String = "Lenscast",
    private val authPassword: String = "",
    /** Shared with [RtspServer]; sessions add their own send counts here. */
    private val serverTxBytes: AtomicLong = AtomicLong(0),
) {
    private enum class State { INIT, READY, PLAYING, TEARDOWN }

    /**
     * Per-stream transport setup. Either TCP-interleaved (uses the RTSP socket with a
     * `$<channel><len>` preamble) or UDP (sends RTP to the client's `client_port`).
     */
    private sealed class StreamTransport {
        data class Tcp(val rtpChannel: Int, val rtcpChannel: Int) : StreamTransport()
        /**
         * UDP transport with separate RTP and RTCP sockets on consecutive ports
         * (RTP on even, RTCP on RTP+1). VLC and other strict clients drop RTCP that
         * doesn't arrive on the `server_port + 1` they were told to expect.
         */
        class Udp(val clientHost: InetAddress, val clientRtpPort: Int, val clientRtcpPort: Int) : StreamTransport() {
            val rtpSocket: DatagramSocket
            val rtcpSocket: DatagramSocket
            init {
                // Try up to ~50 times to find a consecutive RTP/RTCP port pair (RTP even, RTCP odd).
                var rtp: DatagramSocket? = null
                var rtcp: DatagramSocket? = null
                for (attempt in 0 until 64) {
                    val candidate = DatagramSocket()
                    val p = candidate.localPort
                    if (p % 2 != 0) {
                        // Odd port — close and try again so we end up RTP-even, RTCP-odd.
                        candidate.close()
                        continue
                    }
                    val pairCandidate = runCatching { DatagramSocket(p + 1) }.getOrNull()
                    if (pairCandidate == null) {
                        candidate.close()
                        continue
                    }
                    rtp = candidate
                    rtcp = pairCandidate
                    break
                }
                // Fallback: any port pair, even if not consecutive — best effort.
                if (rtp == null || rtcp == null) {
                    rtp = DatagramSocket()
                    rtcp = DatagramSocket()
                }
                rtpSocket = rtp
                rtcpSocket = rtcp
            }
            val serverRtpPort: Int get() = rtpSocket.localPort
            val serverRtcpPort: Int get() = rtcpSocket.localPort
        }
    }

    // Session id is the only token guarding SETUP/PLAY/PAUSE/TEARDOWN against a foreign
    // client on the LAN. SecureRandom (not kotlin.random) so it isn't predictable from
    // observed ids; full 63-bit space instead of a 10-digit range.
    private val sessionNumeric: Long = SecureRandom().nextLong() and Long.MAX_VALUE
    private val sessionId: String = sessionNumeric.toString()
    private var state = State.INIT
    private var videoTransport: StreamTransport? = null
    private var audioTransport: StreamTransport? = null
    @Volatile private var sink: RtspServer.OutgoingPacketSink? = null
    // Buffer the socket so a stream of small RTP packets coalesces into MTU-sized TCP
    // segments instead of one syscall + one network frame per RTP packet. We control the
    // flush cadence below: video frames flush only on the RTP marker bit (end of frame),
    // RTCP/RTSP responses flush immediately. Sized at 64 KB so one 1080p60 keyframe
    // (~30–60 KB) fits in a single buffer-flush cycle.
    private val out = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
    private val writeLock = Any()
    private val bytesSent = AtomicLong(0)
    // Reusable 4-byte $<channel><len16> interleave header; mutated under writeLock.
    private val interleaveHeader = ByteArray(4)

    // Per-stream stats for RTCP Sender Reports — incremented on every RTP packet sent.
    // "Octet count" is RTP payload bytes (header stripped), per RFC 3550.
    private val videoPacketCount = AtomicLong(0)
    private val videoOctetCount = AtomicLong(0)
    private val audioPacketCount = AtomicLong(0)
    private val audioOctetCount = AtomicLong(0)
    private var rtcpThread: Thread? = null

    fun serve() {
        // PushbackInputStream lets us peek the first byte of each "message" so we can
        // distinguish a `$<channel><len>` interleaved frame (binary, RTCP RR coming back
        // from a TCP-only client like OBS) from an RTSP control line (text).
        val raw = java.io.PushbackInputStream(java.io.BufferedInputStream(socket.getInputStream(), 8192), 1)
        while (!socket.isClosed) {
            val first = raw.read()
            if (first < 0) break
            if (first == '$'.code) {
                // Interleaved binary frame: channel (1B) + length (2B BE) + payload.
                val channel = raw.read()
                val hi = raw.read()
                val lo = raw.read()
                if (channel < 0 || hi < 0 || lo < 0) break
                val len = (hi shl 8) or lo
                val payload = ByteArray(len)
                var off = 0
                while (off < len) {
                    val r = raw.read(payload, off, len - off)
                    if (r < 0) return
                    off += r
                }
                // Channels 1 + 3 are RTCP for video / audio respectively (RTP=0/2).
                if (channel == 1 || channel == 3) {
                    var o = 0
                    while (o + 4 <= len) {
                        // (((hi shl 8) or lo) + 1) * 4 — the `+ 1` applies to the full 16-bit
                        // length word, not folded into `lo` (infix `or` binds looser than `+`).
                        val subLen = ((((payload[o + 2].toInt() and 0xFF) shl 8) or
                            (payload[o + 3].toInt() and 0xFF)) + 1) * 4
                        if (o + subLen > len) break
                        for (r in Rtcp.parseReceiverReports(payload, o, subLen)) provider.onReceiverReport(r)
                        o += subLen
                    }
                }
                continue
            }
            // Text path: push the first byte back, read the request line + headers.
            raw.unread(first)
            val request = readRequest(raw) ?: break
            handle(request)
            // RFC 7826 §18.10: a client may ask us to drop the persistent connection after
            // this exchange. We echo `Connection: close` (see echoHeaders) and close here.
            if (request.headers["connection"]?.contains("close", ignoreCase = true) == true) break
        }
    }

    fun onClosed() {
        sink = null
        rtcpThread?.interrupt()
        rtcpThread = null
        for (t in rtcpReaderThreads) t.interrupt()
        rtcpReaderThreads.clear()
        (videoTransport as? StreamTransport.Udp)?.let { it.rtpSocket.close(); it.rtcpSocket.close() }
        (audioTransport as? StreamTransport.Udp)?.let { it.rtpSocket.close(); it.rtcpSocket.close() }
        videoTransport = null
        audioTransport = null
        val s = sink
        if (state == State.PLAYING && s != null) {
            state = State.TEARDOWN
            provider.onClientTeardown(s)
        }
        sink = null
    }

    private data class Request(
        val method: String,
        val uri: String,
        /** Protocol token from the request line, e.g. "RTSP/1.0" or "RTSP/2.0". Echoed verbatim
         *  in the response status line so 1.0 clients get 1.0 and 2.0 clients get 2.0. */
        val version: String,
        val headers: Map<String, String>,
    ) {
        fun cseq(): String = headers["cseq"] ?: "0"
    }

    private fun readRequest(input: java.io.InputStream): Request? {
        var startLine: String? = null
        while (true) {
            val l = readAsciiLine(input) ?: return null
            if (l.isNotBlank()) { startLine = l; break }
        }
        val parts = startLine!!.split(' ')
        if (parts.size < 3) return null
        val method = parts[0]
        val uri = parts[1]
        val version = parts[2]
        val headers = mutableMapOf<String, String>()
        while (true) {
            // Cap the header count so a client can't stream headers forever and exhaust the
            // map / heap before the (unauthenticated) request is even dispatched.
            if (headers.size >= MAX_HEADERS) return null
            val l = readAsciiLine(input) ?: break
            if (l.isEmpty()) break
            val idx = l.indexOf(':')
            if (idx <= 0) continue
            headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }
        // Consume any entity body so it can't be misread as the next request. RTSP messages
        // carry a body only when Content-Length says so (e.g. some clients' SET_PARAMETER
        // pings); leaving those bytes in the stream desynchronises the parser (RFC 7826 §20.2).
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength > 0) {
            var remaining = contentLength
            val skip = ByteArray(minOf(remaining, 8192))
            while (remaining > 0) {
                val r = input.read(skip, 0, minOf(remaining, skip.size))
                if (r < 0) break
                remaining -= r
            }
        }
        return Request(method, uri, version, headers)
    }

    /** Reads bytes up to and including \n, returns the line minus trailing \r\n. Caps the
     *  line at [MAX_LINE_BYTES]: without it a client that never sends a newline grows the
     *  StringBuilder unboundedly (OOM the foreground service). */
    private fun readAsciiLine(input: java.io.InputStream): String? {
        val sb = StringBuilder(64)
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) {
                if (sb.isNotEmpty() && sb[sb.length - 1] == '\r') sb.setLength(sb.length - 1)
                return sb.toString()
            }
            if (sb.length >= MAX_LINE_BYTES) throw java.io.IOException("RTSP line too long")
            sb.append(b.toChar())
        }
    }

    private fun handle(req: Request) {
        android.util.Log.i("RtspSession", "${req.method} ${req.uri} ${req.version} transport=${req.headers["transport"]}")
        // Version negotiation: we speak both 1.0 (RFC 2326) and 2.0 (RFC 7826). Anything
        // else gets 505 so a future 3.x client fails cleanly rather than being misparsed.
        if (req.version != "RTSP/1.0" && req.version != "RTSP/2.0") {
            respondVersionNotSupported(req); return
        }
        val method = req.method.uppercase()
        // OPTIONS stays unauthenticated so clients can negotiate before the user types
        // creds — mirrors every IP camera (Hikvision, Axis, Reolink). Everything else
        // requires Basic auth when a password is set.
        if (method != "OPTIONS" && !isAuthorized(req)) {
            respondUnauthorized(req)
            return
        }
        // Feature negotiation (RFC 7826 §18.43 / RFC 2326 §12.32): refuse only the tags we
        // don't implement. Refusing one we DO support (e.g. play.basic) would itself violate
        // the spec, so we filter against SUPPORTED_FEATURES and 551 only the leftovers.
        val required = (req.headers["require"].orEmpty() + "," + req.headers["proxy-require"].orEmpty())
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val unsupported = required.filter { it !in SUPPORTED_FEATURES }
        if (unsupported.isNotEmpty()) {
            respond(req, 551, "Option not supported", extra = "Unsupported: ${unsupported.joinToString(", ")}\r\n")
            return
        }
        // A Session header naming an id we never issued is a stale or foreign session
        // (RFC 7826 §18.49 / RFC 2326 §11.3.7). Clients using Pipelined-Requests carry no
        // Session id yet, so an absent header is fine — the state machine guards those.
        val sid = req.headers["session"]?.substringBefore(';')?.trim()
        if (!sid.isNullOrEmpty() && sid != sessionId) {
            respond(req, 454, "Session Not Found"); return
        }
        when (method) {
            "OPTIONS" -> respond(req, 200, "OK", extra = "Public: $PUBLIC_METHODS\r\n")
            "DESCRIBE" -> handleDescribe(req)
            "SETUP" -> handleSetup(req)
            "PLAY" -> handlePlay(req)
            "PAUSE" -> handlePause(req)
            "TEARDOWN" -> {
                respond(req, 200, "OK", extra = "Session: $sessionId\r\n")
                state = State.TEARDOWN
                val s = sink
                sink = null
                if (s != null) provider.onClientTeardown(s)
                try { socket.close() } catch (_: Throwable) {}
            }
            "GET_PARAMETER", "SET_PARAMETER" -> {
                respond(req, 200, "OK", extra = "Session: $sessionId\r\n")
            }
            else -> respond(req, 501, "Not Implemented", extra = "Allow: $PUBLIC_METHODS\r\n")
        }
    }

    private fun handlePause(req: Request) {
        // PAUSE needs an established session: in Init it is out of sequence (RFC 7826
        // §13.6 / §17.1 state table). In Ready it's a harmless no-op; in Play it stops media.
        if (state == State.INIT) {
            respond(req, 455, "Method Not Valid in This State", extra = "Allow: $PUBLIC_METHODS\r\n"); return
        }
        if (state == State.PLAYING) { state = State.READY; pauseDelivery() }
        respond(req, 200, "OK", extra = "Session: $sessionId\r\n")
    }

    /**
     * Stops RTP/RTCP delivery to this client without dropping the session (used by PAUSE,
     * and to clear stale wiring before a re-PLAY). Unwires the sink so the manager stops
     * fanning packets here, and tears down the RTCP SR sender + UDP RR readers — both of
     * which key off [State.PLAYING] and would otherwise leave a dead thread that
     * [startRtcp]'s `rtcpThread != null` guard refuses to replace on resume.
     */
    private fun pauseDelivery() {
        val s = sink
        sink = null
        if (s != null) provider.onClientTeardown(s)
        rtcpThread?.interrupt(); rtcpThread = null
        for (t in rtcpReaderThreads) t.interrupt()
        rtcpReaderThreads.clear()
    }

    private fun isAuthorized(req: Request): Boolean {
        if (authPassword.isEmpty()) return true
        val header = req.headers["authorization"] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(android.util.Base64.decode(header.substringAfter(' ').trim(), android.util.Base64.NO_WRAP))
        } catch (_: Throwable) { return false }
        val colon = decoded.indexOf(':')
        if (colon < 0) return false
        return guru.freberg.lenscast.net.AuthUtils.constantTimeEquals(decoded.substring(0, colon), authUsername) &&
            guru.freberg.lenscast.net.AuthUtils.constantTimeEquals(decoded.substring(colon + 1), authPassword)
    }

    private fun respondUnauthorized(req: Request) {
        respond(req, 401, "Unauthorized", extra = "WWW-Authenticate: Basic realm=\"Lenscast\"\r\n")
    }

    private fun handleDescribe(req: Request) {
        // We only describe via SDP. If the client's Accept rules SDP out entirely, say so
        // rather than send a body it asked us not to (RFC 7826 §18.1).
        val accept = req.headers["accept"]
        if (accept != null && !accept.contains("application/sdp", ignoreCase = true) &&
            !accept.contains("*/*") && !accept.contains("application/*", ignoreCase = true)
        ) {
            respond(req, 406, "Not Acceptable"); return
        }
        val video = provider.videoTrack()
        if (video == null) {
            android.util.Log.w("RtspSession", "DESCRIBE rejected: no video track available yet")
            respond(req, 503, "Service Unavailable", extra = "Retry-After: 1\r\n")
            return
        }
        val audio = provider.audioTrack()
        val sdp = Sdp.build(sessionNumeric, video, audio)
        android.util.Log.i("RtspSession", "DESCRIBE response SDP:\n$sdp")
        val body = sdp.toByteArray(Charsets.US_ASCII)
        val base = if (req.uri.endsWith("/")) req.uri else "${req.uri}/"
        val header = (
            "Content-Type: application/sdp\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Content-Base: $base\r\n"
        )
        respondWithBody(req, 200, "OK", header, body)
    }

    private fun handleSetup(req: Request) {
        val transport = req.headers["transport"] ?: run {
            respond(req, 400, "Bad Request"); return
        }
        val tParts = transport.split(';').map { it.trim() }
        val isAudio = req.uri.contains("trackID=1") || req.uri.contains("streamid=1") || req.uri.endsWith("/audio")
        val isTcp = tParts.any { it.equals("RTP/AVP/TCP", ignoreCase = true) }
        val isUdp = tParts.any { it.equals("RTP/AVP", ignoreCase = true) || it.equals("RTP/AVP/UDP", ignoreCase = true) }
        val ssrc = if (isAudio) provider.audioRtpInfo().ssrc else provider.videoRtpInfo().ssrc

        val (chosen, responseTransport) = when {
            isTcp -> setupTcp(tParts, ssrc) ?: run { respond(req, 400, "Bad Request"); return }
            isUdp -> setupUdp(tParts, ssrc) ?: run { respond(req, 400, "Bad Request"); return }
            else -> { respond(req, 461, "Unsupported Transport"); return }
        }

        if (isAudio) {
            (audioTransport as? StreamTransport.Udp)?.let { it.rtpSocket.close(); it.rtcpSocket.close() }
            audioTransport = chosen
        } else {
            (videoTransport as? StreamTransport.Udp)?.let { it.rtpSocket.close(); it.rtcpSocket.close() }
            videoTransport = chosen
        }
        state = State.READY
        // 2.0 carries the live-source descriptors on SETUP too (RFC 7826 §18.29/§18.5).
        val liveProps = if (req.version == "RTSP/2.0")
            "Media-Properties: No-Seeking, Time-Progressing\r\nAccept-Ranges: NPT\r\n" else ""
        respond(
            req, 200, "OK",
            extra = "Transport: $responseTransport\r\nSession: $sessionId;timeout=60\r\n$liveProps",
        )
    }

    /** Returns null if the transport string doesn't have a valid `interleaved=A-B`. */
    private fun setupTcp(tParts: List<String>, ssrc: Int): Pair<StreamTransport, String>? {
        val interleaved = tParts.firstOrNull { it.startsWith("interleaved=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.split('-')
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        if (interleaved.size != 2) return null
        val transport = StreamTransport.Tcp(interleaved[0], interleaved[1])
        val resp = "RTP/AVP/TCP;unicast;interleaved=${interleaved[0]}-${interleaved[1]};ssrc=${"%08x".format(ssrc)}"
        return transport to resp
    }

    /** Returns null if the transport string doesn't have a valid `client_port=A-B`. */
    private fun setupUdp(tParts: List<String>, ssrc: Int): Pair<StreamTransport, String>? {
        val clientPorts = tParts.firstOrNull { it.startsWith("client_port=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.split('-')
            ?.mapNotNull { it.toIntOrNull() }
            ?: emptyList()
        if (clientPorts.isEmpty()) return null
        val rtpPort = clientPorts[0]
        val rtcpPort = clientPorts.getOrElse(1) { rtpPort + 1 }
        val clientAddr = (socket.remoteSocketAddress as? InetSocketAddress)?.address
            ?: return null
        val udp = StreamTransport.Udp(clientAddr, rtpPort, rtcpPort)
        val resp = "RTP/AVP/UDP;unicast;client_port=$rtpPort-$rtcpPort;server_port=${udp.serverRtpPort}-${udp.serverRtcpPort};ssrc=${"%08x".format(ssrc)}"
        return udp to resp
    }

    private fun handlePlay(req: Request) {
        if (state != State.READY && state != State.PLAYING) {
            respond(req, 455, "Method Not Valid in This State", extra = "Allow: $PUBLIC_METHODS\r\n"); return
        }
        // This is a live, non-seekable source, so the only range unit we can honour is NPT
        // (and only its open/`now` forms). A smpte/clock/utc range can't be satisfied
        // against a stream with no stored timeline (RFC 7826 §18.40 → 457).
        val reqRange = req.headers["range"]?.trim()
        if (reqRange != null && !reqRange.startsWith("npt", ignoreCase = true)) {
            respond(req, 457, "Invalid Range"); return
        }
        // Re-PLAY while already playing (or after PAUSE): drop the old wiring first so we
        // don't register a second sink and double-send every packet.
        if (state == State.PLAYING) pauseDelivery()
        state = State.PLAYING
        val is20 = req.version == "RTSP/2.0"

        // Build RTP-Info — VLC won't render frames without it because it can't synchronize
        // RTP timestamps with the wall-clock start of playback. RTSP 2.0 (RFC 7826 §18.45)
        // quotes the url and carries per-stream ssrc; 1.0 (RFC 2326 §12.33) uses the bare form.
        val base = req.uri.trimEnd('/')
        val rtpInfoParts = mutableListOf<String>()
        fun rtpInfoEntry(track: Int, info: RtspServer.RtpInfo): String =
            if (is20) "url=\"$base/trackID=$track\";seq=${info.seq};rtptime=${info.rtpTime};ssrc=${"%08x".format(info.ssrc)}"
            else "url=$base/trackID=$track;seq=${info.seq};rtptime=${info.rtpTime}"
        if (videoTransport != null) rtpInfoParts += rtpInfoEntry(0, provider.videoRtpInfo())
        if (audioTransport != null) rtpInfoParts += rtpInfoEntry(1, provider.audioRtpInfo())
        val rtpInfoHeader = if (rtpInfoParts.isNotEmpty()) "RTP-Info: ${rtpInfoParts.joinToString(",")}\r\n" else ""
        // Live source: open-ended, non-seekable, clock-advancing. 2.0 clients consume these
        // to set their seek UI and playback clock; 1.0 has no equivalent so the bare Range stands.
        val rangeHeader = if (is20) "Range: npt=now-\r\n" else "Range: npt=0.000-\r\n"
        // Media-Range advertises what's currently available; for a time-progressing live
        // edge that's "now" onward (RFC 7826 §18.30).
        val liveProps = if (is20)
            "Media-Properties: No-Seeking, Time-Progressing\r\nMedia-Range: npt=now-\r\nAccept-Ranges: NPT\r\n"
        else ""

        sink = object : RtspServer.OutgoingPacketSink {
            override fun sendVideo(rtp: ByteArray) {
                if (sendOnTransport(videoTransport, rtp)) {
                    videoPacketCount.incrementAndGet()
                    videoOctetCount.addAndGet((rtp.size - RtpStream.HEADER_SIZE).coerceAtLeast(0).toLong())
                }
            }
            override fun sendAudio(rtp: ByteArray) {
                if (sendOnTransport(audioTransport, rtp)) {
                    audioPacketCount.incrementAndGet()
                    audioOctetCount.addAndGet((rtp.size - RtpStream.HEADER_SIZE).coerceAtLeast(0).toLong())
                }
            }
        }
        respond(req, 200, "OK", extra = "Session: $sessionId\r\n$rangeHeader$liveProps$rtpInfoHeader")
        provider.onClientPlay(sink!!)
        startRtcp()
        startUdpRtcpReader(videoTransport)
        startUdpRtcpReader(audioTransport)
    }

    /**
     * Per-UDP-session listener that reads RTCP RR packets back from the client. TCP-interleaved
     * RR isn't routed here — adding it means demultiplexing `$<channel>` frames from the RTSP
     * text stream, which is a bigger change. For now, UDP transports get adaptive bitrate;
     * TCP transports just enjoy the static configured bitrate.
     */
    private val rtcpReaderThreads = mutableListOf<Thread>()
    private fun startUdpRtcpReader(transport: StreamTransport?) {
        if (transport !is StreamTransport.Udp) return
        val sock = transport.rtcpSocket
        val expectedSource = transport.clientHost
        val t = Thread({
            val buf = ByteArray(2048)
            val packet = java.net.DatagramPacket(buf, buf.size)
            while (!sock.isClosed && state == State.PLAYING) {
                try {
                    sock.receive(packet)
                    // Only trust RTCP from the client we did SETUP with. A spoofed RR from any
                    // other source could otherwise drive the adaptive-bitrate loop (e.g. pin
                    // the encoder to its floor by faking 100% packet loss).
                    if (packet.address != expectedSource) continue
                    var o = packet.offset
                    val end = packet.offset + packet.length
                    while (o + 4 <= end) {
                        // `+ 1` applies to the full 16-bit length word, not folded into the
                        // low byte (infix `or` binds looser than `+`).
                        val len = ((((buf[o + 2].toInt() and 0xFF) shl 8) or (buf[o + 3].toInt() and 0xFF)) + 1) * 4
                        if (o + len > end) break
                        for (r in Rtcp.parseReceiverReports(buf, o, len)) provider.onReceiverReport(r)
                        o += len
                    }
                } catch (_: java.io.IOException) { break }
                catch (_: Throwable) { /* swallow per-packet errors */ }
            }
        }, "LenscastRtcpReader").apply { isDaemon = true; start() }
        rtcpReaderThreads.add(t)
    }

    /**
     * Periodic RTCP Sender Report sender (every 5 seconds, per stream). Without these
     * VLC won't display video — it relies on the SR's NTP↔RTP timestamp mapping to lock
     * a playback clock. ffmpeg/ffplay is lenient and doesn't require them.
     */
    private fun startRtcp() {
        if (rtcpThread != null) return
        rtcpThread = Thread({
            try {
                // First SR right away — VLC won't begin rendering until it has at least one
                // SR to anchor its playback clock. Subsequent SRs every 5s per RFC 3550.
                if (state == State.PLAYING) sendSenderReports()
                while (state == State.PLAYING) {
                    Thread.sleep(5_000)
                    if (state == State.PLAYING) sendSenderReports()
                }
            } catch (_: InterruptedException) {
                // Stopped.
            }
        }, "LenscastRtcp").apply { isDaemon = true; start() }
    }

    private fun sendSenderReports() {
        val now = System.currentTimeMillis()
        videoTransport?.let { t ->
            val info = provider.videoRtpInfo()
            val sr = Rtcp.buildSenderReport(
                ssrc = info.ssrc,
                ntpMillis = now,
                rtpTimestamp = info.rtpTime,
                packetCount = videoPacketCount.get(),
                octetCount = videoOctetCount.get(),
            )
            sendRtcp(t, sr)
            android.util.Log.i("RtspSession", "RTCP SR video: pkts=${videoPacketCount.get()}, octets=${videoOctetCount.get()}, ts=${info.rtpTime}")
        }
        audioTransport?.let { t ->
            val info = provider.audioRtpInfo()
            val sr = Rtcp.buildSenderReport(
                ssrc = info.ssrc,
                ntpMillis = now,
                rtpTimestamp = info.rtpTime,
                packetCount = audioPacketCount.get(),
                octetCount = audioOctetCount.get(),
            )
            sendRtcp(t, sr)
            android.util.Log.i("RtspSession", "RTCP SR audio: pkts=${audioPacketCount.get()}, octets=${audioOctetCount.get()}, ts=${info.rtpTime}")
        }
    }

    private fun sendRtcp(t: StreamTransport, sr: ByteArray) {
        when (t) {
            is StreamTransport.Tcp -> sendInterleaved(t.rtcpChannel, sr, forceFlush = true)
            is StreamTransport.Udp -> {
                try {
                    t.rtcpSocket.send(DatagramPacket(sr, sr.size, t.clientHost, t.clientRtcpPort))
                } catch (_: IOException) {}
            }
        }
    }

    private fun sendOnTransport(t: StreamTransport?, rtp: ByteArray): Boolean = when (t) {
        // RTP path: let the buffered stream coalesce packets; flush only when the marker
        // bit signals end-of-frame so a 1080p60 keyframe ships as a single batched write
        // rather than ~25 separate syscalls. Audio packets always have marker=1 (one AU
        // per packet), so audio still flushes immediately.
        is StreamTransport.Tcp -> sendInterleaved(t.rtpChannel, rtp, forceFlush = false)
        is StreamTransport.Udp -> sendUdp(t, rtp)
        null -> false
    }

    private fun sendInterleaved(channel: Int, payload: ByteArray, forceFlush: Boolean): Boolean {
        if (payload.size > 0xFFFF) return false
        synchronized(writeLock) {
            try {
                interleaveHeader[0] = '$'.code.toByte()
                interleaveHeader[1] = channel.toByte()
                interleaveHeader[2] = (payload.size ushr 8).toByte()
                interleaveHeader[3] = (payload.size and 0xFF).toByte()
                out.write(interleaveHeader)
                out.write(payload)
                // RTP marker bit lives in byte 1, high bit. Set on the last RTP packet of
                // a video access unit and on every audio packet.
                val markerSet = payload.size >= 2 && (payload[1].toInt() and 0x80 != 0)
                if (forceFlush || markerSet) out.flush()
                val n = (4 + payload.size).toLong()
                bytesSent.addAndGet(n)
                serverTxBytes.addAndGet(n)
                return true
            } catch (_: IOException) {
                return false
            }
        }
    }

    private fun sendUdp(t: StreamTransport.Udp, payload: ByteArray): Boolean {
        return try {
            t.rtpSocket.send(DatagramPacket(payload, payload.size, t.clientHost, t.clientRtpPort))
            bytesSent.addAndGet(payload.size.toLong())
            serverTxBytes.addAndGet(payload.size.toLong())
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun respond(req: Request, code: Int, status: String, extra: String = "") {
        val msg = "${req.version} $code $status\r\nCSeq: ${req.cseq()}\r\n${commonHeaders()}${echoHeaders(req)}$extra\r\n"
        writeLine(msg)
    }

    private fun respondWithBody(req: Request, code: Int, status: String, headers: String, body: ByteArray) {
        val head = "${req.version} $code $status\r\nCSeq: ${req.cseq()}\r\n${commonHeaders()}${echoHeaders(req)}$headers\r\n"
        synchronized(writeLock) {
            try {
                out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
            } catch (_: IOException) {}
        }
    }

    /** Sent when the request line carries a version we don't speak (anything but 1.0/2.0). */
    private fun respondVersionNotSupported(req: Request) {
        val msg = "RTSP/2.0 505 RTSP Version Not Supported\r\nCSeq: ${req.cseq()}\r\n${commonHeaders()}${echoHeaders(req)}\r\n"
        writeLine(msg)
    }

    /** `Server` + `Date` on every response (RFC 7826 §18.20/§18.46 SHOULD; harmless on 1.0). */
    private fun commonHeaders(): String {
        val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        fmt.timeZone = java.util.TimeZone.getTimeZone("GMT")
        return "Server: Lenscast\r\nDate: ${fmt.format(java.util.Date())}\r\n"
    }

    /**
     * Headers that must be reflected back from the request, computed per-response so they
     * stay correct for every status code. None of these fire for a stock 1.0 client (it
     * sends none of them), so the 1.0 wire output is unchanged:
     *  - `Timestamp` — copied verbatim so the client can measure round-trip time (§18.53).
     *  - `Pipelined-Requests` — correlates requests pipelined before a Session id existed (§18.33).
     *  - `Supported` — when the client probes, we answer with the feature tags we implement (§18.51).
     *  - `Connection: close` — acknowledge a client-requested non-persistent connection (§18.10).
     */
    private fun echoHeaders(req: Request): String {
        val sb = StringBuilder()
        req.headers["timestamp"]?.let { sb.append("Timestamp: ").append(it).append("\r\n") }
        req.headers["pipelined-requests"]?.let { sb.append("Pipelined-Requests: ").append(it).append("\r\n") }
        if (req.headers.containsKey("supported")) {
            sb.append("Supported: ").append(SUPPORTED_FEATURES.joinToString(", ")).append("\r\n")
        }
        if (req.headers["connection"]?.contains("close", ignoreCase = true) == true) {
            sb.append("Connection: close\r\n")
        }
        return sb.toString()
    }

    private fun writeLine(s: String) {
        synchronized(writeLock) {
            try { out.write(s.toByteArray(Charsets.US_ASCII)); out.flush() } catch (_: IOException) {}
        }
    }

    @Suppress("unused") fun bytesSentSoFar(): Long = bytesSent.get()

    companion object {
        // Advertised in OPTIONS `Public:` and echoed in `Allow:` on 455/501 — every method
        // we actually dispatch (RFC 7826 §18.6 requires Allow to list valid methods).
        private const val PUBLIC_METHODS =
            "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER, SET_PARAMETER"
        // Feature tags we implement (RFC 7826 §11.1, IANA RTSP feature-tag registry).
        // `play.basic` = basic non-seeking playback, which is exactly this live source.
        // We deliberately do NOT claim play.scale / play.speed / setup.* we can't honour,
        // so a Require for those correctly draws a 551.
        private val SUPPORTED_FEATURES = listOf("play.basic")
        /** Hard caps on the request parser so a malformed/hostile peer can't exhaust heap
         *  before the request is dispatched. RTSP request lines and headers are short. */
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_HEADERS = 100
    }
}
