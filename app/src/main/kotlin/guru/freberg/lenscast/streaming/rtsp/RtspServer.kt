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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Minimal single-client RTSP/1.0 server. The streaming path is **TCP-interleaved** —
 * RTP packets share the RTSP control TCP socket, framed by a 4-byte `$<channel><len16>`
 * preamble per RFC 2326 §10.12. Two-port UDP isn't supported (yet); it'd be a fairly
 * mechanical addition but TCP works through NAT and is what OBS uses by default.
 *
 * Handles only one client at a time; a new connect closes the previous session.
 *
 * Wiring: the [RtspManager] supplies an [OutgoingPacketSink] hook that the producer
 * (encoder packetizers) calls; the server multiplexes those bytes onto the active client's
 * TCP socket. Channels are 0/1 for video RTP/RTCP and 2/3 for audio RTP/RTCP.
 */
class RtspServer(
    private val port: Int,
    private val streamProvider: StreamProvider,
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
        /** Called when the client tears down or disconnects. */
        fun onClientTeardown()
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
    private var currentClient: Socket? = null
    @Volatile private var running = false
    @Volatile var playingClients: Int = 0
        private set

    fun start() {
        if (running) return
        running = true
        acceptJob = scope.launch {
            try {
                val s = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                server = s
                Log.i(TAG, "RTSP listening on 0.0.0.0:$port")
                while (running && isActive) {
                    val client = try { s.accept() } catch (_: IOException) { break }
                    client.tcpNoDelay = true
                    client.soTimeout = 0
                    // Single client at a time — boot the old one.
                    try { currentClient?.close() } catch (_: Throwable) {}
                    currentClient = client
                    launch { handle(client) }
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Accept loop crashed", t)
            }
        }
    }

    fun stop() {
        running = false
        try { currentClient?.close() } catch (_: Throwable) {}
        currentClient = null
        try { server?.close() } catch (_: Throwable) {}
        server = null
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
    }

    private suspend fun handle(socket: Socket) {
        Log.i(TAG, "Client connected from ${socket.remoteSocketAddress}")
        val session = RtspSession(socket, streamProvider)
        try {
            session.serve()
        } catch (_: SocketException) {
            // Normal disconnect.
        } catch (t: Throwable) {
            Log.w(TAG, "Session error: ${t.message}")
        } finally {
            try { socket.close() } catch (_: Throwable) {}
            session.onClosed()
            if (currentClient === socket) currentClient = null
            Log.i(TAG, "Client ${socket.remoteSocketAddress} disconnected")
        }
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

    private val sessionId: String = (Random.nextLong(1_000_000_000L, 9_999_999_999L)).toString()
    private val sessionNumeric: Long = sessionId.toLong()
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
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
        while (!socket.isClosed) {
            val request = readRequest(reader) ?: break
            handle(request)
        }
    }

    fun onClosed() {
        sink = null
        rtcpThread?.interrupt()
        rtcpThread = null
        (videoTransport as? StreamTransport.Udp)?.let { it.rtpSocket.close(); it.rtcpSocket.close() }
        (audioTransport as? StreamTransport.Udp)?.let { it.rtpSocket.close(); it.rtcpSocket.close() }
        videoTransport = null
        audioTransport = null
        if (state == State.PLAYING) {
            state = State.TEARDOWN
            provider.onClientTeardown()
        }
    }

    private data class Request(val method: String, val uri: String, val headers: Map<String, String>) {
        fun cseq(): String = headers["cseq"] ?: "0"
    }

    private fun readRequest(reader: BufferedReader): Request? {
        var startLine: String? = null
        while (true) {
            val l = reader.readLine() ?: return null
            if (l.isNotBlank()) { startLine = l; break }
        }
        val parts = startLine!!.split(' ')
        if (parts.size < 3) return null
        val method = parts[0]
        val uri = parts[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val l = reader.readLine() ?: break
            if (l.isEmpty()) break
            val idx = l.indexOf(':')
            if (idx <= 0) continue
            headers[l.substring(0, idx).trim().lowercase()] = l.substring(idx + 1).trim()
        }
        return Request(method, uri, headers)
    }

    private fun handle(req: Request) {
        android.util.Log.i("RtspSession", "${req.method} ${req.uri} transport=${req.headers["transport"]}")
        when (req.method.uppercase()) {
            "OPTIONS" -> respond(req, 200, "OK", extra = "Public: OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN, GET_PARAMETER\r\n")
            "DESCRIBE" -> handleDescribe(req)
            "SETUP" -> handleSetup(req)
            "PLAY" -> handlePlay(req)
            "PAUSE" -> {
                respond(req, 200, "OK", extra = "Session: $sessionId\r\n")
            }
            "TEARDOWN" -> {
                respond(req, 200, "OK", extra = "Session: $sessionId\r\n")
                state = State.TEARDOWN
                sink = null
                provider.onClientTeardown()
                try { socket.close() } catch (_: Throwable) {}
            }
            "GET_PARAMETER", "SET_PARAMETER" -> {
                respond(req, 200, "OK", extra = "Session: $sessionId\r\n")
            }
            else -> respond(req, 501, "Not Implemented")
        }
    }

    private fun handleDescribe(req: Request) {
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
        respond(
            req, 200, "OK",
            extra = "Transport: $responseTransport\r\nSession: $sessionId;timeout=60\r\n",
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
            respond(req, 455, "Method Not Valid in This State"); return
        }
        state = State.PLAYING

        // Build RTP-Info — VLC won't render frames without it because it can't synchronize
        // RTP timestamps with the wall-clock start of playback.
        val base = req.uri.trimEnd('/')
        val rtpInfoParts = mutableListOf<String>()
        if (videoTransport != null) {
            val vi = provider.videoRtpInfo()
            rtpInfoParts += "url=$base/trackID=0;seq=${vi.seq};rtptime=${vi.rtpTime}"
        }
        if (audioTransport != null) {
            val ai = provider.audioRtpInfo()
            rtpInfoParts += "url=$base/trackID=1;seq=${ai.seq};rtptime=${ai.rtpTime}"
        }
        val rtpInfoHeader = if (rtpInfoParts.isNotEmpty()) "RTP-Info: ${rtpInfoParts.joinToString(",")}\r\n" else ""

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
        respond(req, 200, "OK", extra = "Session: $sessionId\r\nRange: npt=0.000-\r\n$rtpInfoHeader")
        provider.onClientPlay(sink!!)
        startRtcp()
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
                bytesSent.addAndGet((4 + payload.size).toLong())
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
            true
        } catch (_: IOException) {
            false
        }
    }

    private fun respond(req: Request, code: Int, status: String, extra: String = "") {
        val msg = "RTSP/1.0 $code $status\r\nCSeq: ${req.cseq()}\r\n$extra\r\n"
        writeLine(msg)
    }

    private fun respondWithBody(req: Request, code: Int, status: String, headers: String, body: ByteArray) {
        val head = "RTSP/1.0 $code $status\r\nCSeq: ${req.cseq()}\r\n$headers\r\n"
        synchronized(writeLock) {
            try {
                out.write(head.toByteArray(Charsets.US_ASCII)); out.write(body); out.flush()
            } catch (_: IOException) {}
        }
    }

    private fun writeLine(s: String) {
        synchronized(writeLock) {
            try { out.write(s.toByteArray(Charsets.US_ASCII)); out.flush() } catch (_: IOException) {}
        }
    }

    @Suppress("unused") fun bytesSentSoFar(): Long = bytesSent.get()
}
