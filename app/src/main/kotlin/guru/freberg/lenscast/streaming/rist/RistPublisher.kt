package guru.freberg.lenscast.streaming.rist

import android.util.Log
import guru.freberg.lenscast.streaming.rtsp.Rtcp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RIST sender — pure Kotlin / `DatagramSocket`, no native librist dependency. Supports both
 * RIST profiles defined by VSF:
 *
 *  - **Simple Profile** (TR-06-1): plain RTP carrying MPEG-TS on an even **data port**, RTCP
 *    on the odd **control port** (data+1). Receiver requests gaps via an RFC 4585 Generic
 *    NACK; we resend the exact RTP packets. "Receiver compatibility everywhere."
 *  - **Main Profile** (TR-06-2): everything is GRE-encapsulated over a **single UDP port**
 *    (the data port), with a 32-bit GRE sequence and optional **PSK AES-CTR encryption**.
 *    Inside the GRE we use the "reduced overhead" header (virtual ports
 *    [VIRT_SRC_PORT]→[VIRT_DST_PORT]) followed by the same RTP/MP2T or RTCP payload. The
 *    receiver requests gaps via RIST range (PT 204) or bitmask (PT 205) NACKs; we resend.
 *
 * The GRE framing and crypto were implemented directly from the librist source
 * (`src/proto/gre.c`, `src/crypto/psk.c`) — see [RistCrypto] and [buildGre]/[parseInbound]
 * for the exact byte layout and the spec references.
 *
 * Two modes mirror [guru.freberg.lenscast.streaming.srt.SrtPublisher]:
 *  - [Mode.CALLER]:   the phone dials `host:port` and pushes out. Best through NAT, and the
 *    reliable choice for receivers (VLC, ffmpeg) whose RIST input binds/listens locally.
 *  - [Mode.LISTENER]: the phone binds the port and learns the peer from its first inbound
 *    packet, then pushes to it.
 */
class RistPublisher(
    private val config: Config,
    /** Surfaces transport-layer lifecycle events to the manager. */
    private val onState: (State) -> Unit = {},
) {

    enum class Mode { CALLER, LISTENER }
    enum class Profile { SIMPLE, MAIN }
    enum class State { IDLE, CONNECTING, CONNECTED, FAILED, CLOSED }

    data class Config(
        val mode: Mode,
        /** Remote host for [Mode.CALLER]; ignored for [Mode.LISTENER]. */
        val host: String,
        /**
         * Data port. Simple Profile sends RTP here and RTCP on [port] + 1 (the classic RTP
         * pair librist expects); Main Profile GRE-muxes everything onto this single port.
         */
        val port: Int,
        /** Retransmit-buffer / receiver-side jitter window in milliseconds. */
        val bufferMs: Int,
        val profile: Profile = Profile.SIMPLE,
        /** Main Profile PSK passphrase. Empty = GRE without encryption. Ignored under Simple. */
        val passphrase: String = "",
        /** AES key length in bits for Main Profile encryption (128 or 256). */
        val keyBits: Int = 128,
    )

    private val mainProfile = config.profile == Profile.MAIN
    // Main-Profile PSK cipher; null when unencrypted or Simple Profile.
    private val crypto: RistCrypto? =
        if (mainProfile && config.passphrase.isNotEmpty()) RistCrypto(config.passphrase, config.keyBits) else null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var pumpJob: Job? = null
    private var rtcpJob: Job? = null
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE + 1)
    private val running = AtomicBoolean(false)
    @Volatile private var currentState: State = State.IDLE

    // Simple Profile uses the classic RTP/RTCP port pair: media on [dataSocket] (the data
    // port) and RTCP on [rtcpSocket] (data+1) — librist's receiver authenticates the flow and
    // starts its output thread off the RTCP peer on the odd port, so the SR+SDES MUST go there
    // (it correlates the data and RTCP peers by source IP only, not port). Main Profile carries
    // everything GRE-muxed on [dataSocket] and leaves [rtcpSocket] null.
    @Volatile private var dataSocket: DatagramSocket? = null
    @Volatile private var rtcpSocket: DatagramSocket? = null
    @Volatile private var peerDataAddr: InetSocketAddress? = null
    @Volatile private var peerRtcpAddr: InetSocketAddress? = null

    @Volatile private var bytesSent: Long = 0L
    @Volatile private var packetCount: Long = 0L
    @Volatile private var octetCount: Long = 0L
    @Volatile private var retransmits: Long = 0L
    // 16-bit RTP sequence space (carried in the RTP header; NACKs reference this).
    private var sequence: Int = 0
    // 32-bit GRE sequence (Main Profile only): monotonic across every GRE packet, and the
    // high 4 bytes of the AES-CTR IV. Wraps naturally.
    private var greSeq: Int = 0
    // Stream clock origin for the 90 kHz RTP timestamp.
    private var clockStartNs: Long = 0L
    // 'LS' << 16 | port, with the low bit forced to 0: TR-06-1 §5.3.3 reserves the SSRC LSB as
    // the original(0)/retransmission(1) flag, so an odd port must not make originals look resent.
    private val ssrc: Int = (0x4C53_0000 or (config.port and 0xFFFF)) and 0x1.inv()

    // Retransmit ring keyed by RTP sequence. slot = seq & MASK; [retransSeq] confirms the slot
    // still holds the requested sequence (later packets overwrite older ones once it wraps).
    private val retransBuf = arrayOfNulls<ByteArray>(RETRANS_RING)
    private val retransSeq = IntArray(RETRANS_RING) { -1 }

    fun state(): State = currentState
    fun bytesSent(): Long = bytesSent
    fun retransmitCount(): Long = retransmits

    fun start() {
        if (!running.compareAndSet(false, true)) return
        currentState = State.CONNECTING
        onState(State.CONNECTING)
        connectJob = scope.launch {
            try {
                openSockets()
                rtcpJob = scope.launch { feedbackLoop() }
                when (config.mode) {
                    Mode.CALLER -> startCaller()
                    Mode.LISTENER -> startListener()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "RIST start failed", t)
                currentState = State.FAILED
                onState(State.FAILED)
                stopInternal()
            }
        }
    }

    private fun openSockets() {
        when (config.mode) {
            Mode.CALLER -> {
                dataSocket = DatagramSocket().apply { broadcast = true }
                val addr = InetAddress.getByName(config.host)
                peerDataAddr = InetSocketAddress(addr, config.port)
                if (!mainProfile) {
                    rtcpSocket = DatagramSocket()
                    peerRtcpAddr = InetSocketAddress(addr, config.port + 1)
                }
            }
            Mode.LISTENER -> {
                dataSocket = DatagramSocket(config.port)
                if (!mainProfile) rtcpSocket = DatagramSocket(config.port + 1)
            }
        }
        // The feedback loop reads this socket with a short timeout so it returns to check
        // `running` and re-runs the RTCP cadence; close() also unblocks a parked receive().
        feedbackSocket()?.soTimeout = RTCP_POLL_MS
    }

    /** Socket the receiver's feedback (NACK/RR) arrives on: the single socket in Main, the odd RTCP port in Simple. */
    private fun feedbackSocket(): DatagramSocket? = if (mainProfile) dataSocket else rtcpSocket

    private fun startCaller() {
        clockStartNs = System.nanoTime()
        currentState = State.CONNECTED
        queue.clear()
        onState(State.CONNECTED)
        Log.i(TAG, "RIST ${profileTag()} caller → ${config.host}:${config.port}")
        // Get an SR+SDES in front of the receiver before the data flood so its handshake
        // (which times out in ~270 ms) completes on the first try rather than racing.
        repeat(HANDSHAKE_BURST) { sendSenderReport() }
        startPump()
    }

    private fun startListener() {
        Log.i(TAG, "RIST ${profileTag()} listening on 0.0.0.0:${config.port}")
        startPump()
    }

    private fun profileTag() = if (mainProfile) (if (crypto != null) "main+aes${config.keyBits}" else "main") else "simple"

    private fun markPeerConnected(from: InetSocketAddress) {
        if (peerDataAddr != null) return
        if (mainProfile) {
            peerDataAddr = from
        } else {
            // Learned on the RTCP socket; assume the receiver's data port is the canonical one.
            peerDataAddr = InetSocketAddress(from.address, config.port)
            peerRtcpAddr = InetSocketAddress(from.address, config.port + 1)
        }
        clockStartNs = System.nanoTime()
        currentState = State.CONNECTED
        queue.clear()
        Log.i(TAG, "RIST listener learned peer ${from.address.hostAddress}")
        onState(State.CONNECTED)
        repeat(HANDSHAKE_BURST) { sendSenderReport() }
    }

    private fun startPump() {
        pumpJob = scope.launch { pumpLoop() }
    }

    /** Coalesce up to [TS_PER_RTP] TS packets into one RTP/MP2T datagram, flushing a partial
     *  bundle on a short poll-timeout so the receiver never waits on a half-full RTP packet. */
    private fun pumpLoop() {
        val ts = ByteArray(TS_PER_RTP * 188)
        var tsCount = 0
        try {
            while (running.get()) {
                val pkt = try {
                    queue.poll(FLUSH_POLL_MS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                if (pkt != null) {
                    System.arraycopy(pkt, 0, ts, tsCount * 188, 188)
                    tsCount++
                    if (tsCount == TS_PER_RTP) {
                        emitRtp(ts, tsCount * 188)
                        tsCount = 0
                    }
                } else if (tsCount > 0) {
                    emitRtp(ts, tsCount * 188)
                    tsCount = 0
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "RIST pump ended: ${t.message}")
        }
    }

    private fun emitRtp(tsPayload: ByteArray, payloadLen: Int) {
        val seq = sequence and 0xFFFF
        sequence = (sequence + 1) and 0xFFFF
        val rtp = buildRtpPacket(seq, tsPayload, payloadLen)
        val slot = seq and RETRANS_MASK
        retransBuf[slot] = rtp
        retransSeq[slot] = seq
        sendRtp(rtp, payloadLen)
    }

    /** Ship one RTP packet — raw on the data socket (Simple) or GRE-wrapped (Main). */
    private fun sendRtp(rtp: ByteArray, payloadLen: Int) {
        val sock = dataSocket ?: return
        val peer = peerDataAddr ?: return
        val wire = if (mainProfile) buildGre(rtp, isRtcp = false) else rtp
        try {
            sock.send(DatagramPacket(wire, wire.size, peer))
            bytesSent += wire.size
            octetCount += payloadLen
            packetCount++
        } catch (t: Throwable) {
            Log.w(TAG, "RIST data send failed: ${t.message}")
        }
    }

    private fun buildRtpPacket(seq: Int, tsPayload: ByteArray, payloadLen: Int): ByteArray {
        val rtp = ByteArray(RTP_HEADER + payloadLen)
        rtp[0] = 0x80.toByte()                 // V=2, P=0, X=0, CC=0
        rtp[1] = MP2T_PAYLOAD_TYPE.toByte()    // M=0, PT=33
        rtp[2] = ((seq ushr 8) and 0xFF).toByte()
        rtp[3] = (seq and 0xFF).toByte()
        val rtpTs = currentRtpTimestamp()
        rtp[4] = ((rtpTs ushr 24) and 0xFF).toByte()
        rtp[5] = ((rtpTs ushr 16) and 0xFF).toByte()
        rtp[6] = ((rtpTs ushr 8) and 0xFF).toByte()
        rtp[7] = (rtpTs and 0xFF).toByte()
        rtp[8] = ((ssrc ushr 24) and 0xFF).toByte()
        rtp[9] = ((ssrc ushr 16) and 0xFF).toByte()
        rtp[10] = ((ssrc ushr 8) and 0xFF).toByte()
        rtp[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(tsPayload, 0, rtp, RTP_HEADER, payloadLen)
        return rtp
    }

    private fun currentRtpTimestamp(): Int {
        val deltaNs = System.nanoTime() - clockStartNs
        return ((deltaNs / 1000L) * 90L / 1000L).toInt() // ns → µs → 90 kHz ticks
    }

    // ─── Main Profile GRE encapsulation ───────────────────────────────────────────────────
    //
    // Wire layout for a (v2) data packet, from librist src/proto/gre.c:
    //   flags1 | flags2 | prot_type(=0xCCE0 VSF) |        ← 4-byte GRE base, cleartext
    //   [nonce(4)]  (present iff K bit set / encrypted)   ← cleartext
    //   greSeq(4, big-endian)                             ← cleartext (S bit set)
    //   ── encrypted region (AES-CTR, IV = BE32(greSeq)‖0) when a passphrase is set ──
    //   VSF proto {type=0, subtype=0} (4)
    //   reduced  {src_port, dst_port} (4)
    //   <payload: RTP header + TS, or an RTCP packet>
    //
    // flags1: bit4 (0x10)=sequence present, bit5 (0x20)=key/nonce present.
    // flags2: (version<<3)=0x10 for v2; bit6 (0x40) set additionally for AES-256.
    private fun buildGre(payload: ByteArray, isRtcp: Boolean): ByteArray {
        val seq = greSeq
        greSeq++
        // Cleartext region: VSF proto (4 zero bytes for RIST/reduced) + reduced header + payload.
        val clear = ByteArray(8 + payload.size)
        // VSF proto type=0x0000, subtype=0x0000 → clear[0..3] already zero.
        putBe16(clear, 4, VIRT_SRC_PORT)
        putBe16(clear, 6, VIRT_DST_PORT)
        System.arraycopy(payload, 0, clear, 8, payload.size)

        val c = crypto
        return if (c != null) {
            val nonce = c.nonce
            val enc = c.encrypt(seq, clear)
            val out = ByteArray(12 + enc.size)
            out[0] = 0x30.toByte()                                    // K + S bits
            out[1] = (0x10 or (if (config.keyBits == 256) 0x40 else 0)).toByte()
            putBe16(out, 2, GRE_PROTO_VSF)
            System.arraycopy(nonce, 0, out, 4, 4)
            putBe32(out, 8, seq)
            System.arraycopy(enc, 0, out, 12, enc.size)
            out
        } else {
            val out = ByteArray(8 + clear.size)
            out[0] = 0x10.toByte()                                    // S bit only
            out[1] = 0x10.toByte()                                    // version 2
            putBe16(out, 2, GRE_PROTO_VSF)
            putBe32(out, 4, seq)
            System.arraycopy(clear, 0, out, 8, clear.size)
            out
        }
    }

    /**
     * Reads the feedback socket: learns the peer (LISTENER), services retransmit requests,
     * and emits a periodic Sender Report.
     */
    private fun feedbackLoop() {
        val buf = ByteArray(2048)
        var lastSrNs = 0L
        while (running.get()) {
            val sock = feedbackSocket() ?: break
            val dp = DatagramPacket(buf, buf.size)
            try {
                sock.receive(dp)
                val from = dp.socketAddress as? InetSocketAddress
                if (from != null && peerDataAddr == null) markPeerConnected(from)
                if (mainProfile) parseInbound(buf, dp.length) else handleRtcp(buf, 0, dp.length)
            } catch (_: SocketTimeoutException) {
                // fall through to the SR cadence check
            } catch (t: Throwable) {
                if (running.get()) Log.w(TAG, "RIST feedback recv: ${t.message}")
            }
            val now = System.nanoTime()
            if (currentState == State.CONNECTED && now - lastSrNs >= SR_INTERVAL_NS) {
                sendSenderReport()
                lastSrNs = now
            }
        }
    }

    /** Strip the GRE encapsulation off an inbound Main-Profile packet, decrypt, and dispatch. */
    private fun parseInbound(pkt: ByteArray, len: Int) {
        if (len < 4) return
        val flags1 = pkt[0].toInt() and 0xFF
        val protType = be16(pkt, 2)
        var off = 4
        val hasKey = (flags1 and 0x20) != 0
        val hasSeq = (flags1 and 0x10) != 0
        var nonce: ByteArray? = null
        if (hasKey) {
            if (off + 4 > len) return
            nonce = pkt.copyOfRange(off, off + 4); off += 4
        }
        var seq = 0
        if (hasSeq) {
            if (off + 4 > len) return
            seq = be32(pkt, off); off += 4
        }
        if (off > len) return
        val inner: ByteArray = if (hasKey && crypto != null && nonce != null) {
            crypto.decrypt(nonce, seq, pkt, off, len - off)
        } else {
            pkt.copyOfRange(off, len)
        }
        var io = 0
        if (protType == GRE_PROTO_VSF) io += 4          // skip VSF proto sub-header (v2)
        else if (protType == GRE_PROTO_KEEPALIVE) return // ignore keepalives
        if (io + 4 > inner.size) return
        io += 4                                          // skip reduced overhead header (src/dst port)
        if (io < inner.size) handleRtcp(inner, io, inner.size - io)
    }

    // ─── NACK / RTCP feedback handling (shared by both profiles) ───────────────────────────
    //
    // Walks a (possibly compound) RTCP buffer and services retransmit requests. The two NACK
    // shapes are matched on their FULL first byte, NOT just the payload type — because RIST's
    // echo request/response packets reuse PT 204 *and* the "RIST" APP name and differ from the
    // range NACK only in that first byte (range NACK = 0x80, echo req/resp = 0x82/0x83). Keying
    // off PT alone made us decode echo NTP timestamps as huge bogus seq ranges → resend storm.
    //   - PT 205, first byte 0x81: RIST bitmask NACK   — FCI records {base seq, BLP bitmask}.
    //   - PT 204, first byte 0x80, name "RIST": range NACK — FCI records {first seq, extra}.
    // Both reference the 16-bit RTP sequence, which is how our retransmit ring is keyed.
    private fun handleRtcp(pkt: ByteArray, start: Int, length: Int) {
        var off = start
        val end = start + length
        while (off + 4 <= end) {
            val first = pkt[off].toInt() and 0xFF
            val pt = pkt[off + 1].toInt() and 0xFF
            val words = be16(pkt, off + 2)
            val sub = (words + 1) * 4
            if (sub <= 0 || off + sub > end) break
            when {
                pt == RTCP_RTPFB_BITMASK && first == NACK_BITMASK_FLAGS ->
                    parseBitmaskNack(pkt, off, sub)
                pt == RTCP_RANGE_NACK && first == NACK_RANGE_FLAGS && isRistApp(pkt, off, end) ->
                    parseRangeNack(pkt, off, sub)
            }
            off += sub
        }
    }

    /** True if the 4-byte APP name at the RIST range-NACK offset (header + ssrc) is "RIST". */
    private fun isRistApp(pkt: ByteArray, off: Int, end: Int): Boolean =
        off + 12 <= end &&
            pkt[off + 8] == 'R'.code.toByte() && pkt[off + 9] == 'I'.code.toByte() &&
            pkt[off + 10] == 'S'.code.toByte() && pkt[off + 11] == 'T'.code.toByte()

    /** FCI starts after the 12-byte feedback header (header + 2 SSRCs). Records: {PID, BLP}. */
    private fun parseBitmaskNack(pkt: ByteArray, off: Int, sub: Int) {
        var fci = off + 12
        while (fci + 4 <= off + sub) {
            val pid = be16(pkt, fci)
            val blp = be16(pkt, fci + 2)
            resend(pid)
            for (bit in 0 until 16) if ((blp ushr bit) and 1 == 1) resend((pid + bit + 1) and 0xFFFF)
            fci += 4
        }
    }

    /** RIST range NACK: records {first missing seq, count of additional consecutive packets}. */
    private fun parseRangeNack(pkt: ByteArray, off: Int, sub: Int) {
        var fci = off + 12
        while (fci + 4 <= off + sub) {
            val startSeq = be16(pkt, fci)
            // Clamp the run length: a legitimate NACK never spans more than the buffer, and the
            // cap keeps any malformed record from turning into a multi-thousand-packet resend.
            val extra = minOf(be16(pkt, fci + 2), MAX_NACK_RUN)
            var i = 0
            while (i <= extra) {
                resend((startSeq + i) and 0xFFFF)
                i++
            }
            fci += 4
        }
    }

    private fun resend(seq: Int) {
        val slot = seq and RETRANS_MASK
        if (retransSeq[slot] != seq) return // evicted by ring wrap — too old to recover
        val rtp = retransBuf[slot] ?: return
        val sock = dataSocket ?: return
        val peer = peerDataAddr ?: return
        // TR-06-1 §5.3.3: a retransmitted packet is a copy of the original with the same
        // sequence number and timestamp but the SSRC's least-significant bit set to 1. Mark a
        // copy so the cached original (LSB=0) stays intact for any later resend.
        val copy = rtp.copyOf()
        copy[11] = (copy[11].toInt() or 0x01).toByte()
        val wire = if (mainProfile) buildGre(copy, isRtcp = false) else copy
        try {
            sock.send(DatagramPacket(wire, wire.size, peer))
            bytesSent += wire.size
            retransmits++
        } catch (t: Throwable) {
            Log.w(TAG, "RIST retransmit failed: ${t.message}")
        }
    }

    /**
     * Periodic RTCP. We send a **compound SR + SDES(CNAME)** — librist (and therefore OBS,
     * VLC and ffmpeg RIST inputs) refuses to accept data until it has seen an RTCP packet
     * carrying an SDES, so a bare Sender Report leaves the receiver stuck "waiting for an
     * RTCP packet with SDES on it". Sent on the same socket/peer as the media.
     */
    private fun sendSenderReport() {
        // Simple Profile RTCP goes to the odd RTCP port; Main Profile rides the single
        // GRE-muxed data socket.
        val sock = if (mainProfile) dataSocket else rtcpSocket
        val peer = if (mainProfile) peerDataAddr else peerRtcpAddr
        if (sock == null || peer == null) return
        val sr = Rtcp.buildSenderReport(
            ssrc = ssrc,
            ntpMillis = System.currentTimeMillis(),
            rtpTimestamp = currentRtpTimestamp(),
            packetCount = packetCount,
            octetCount = octetCount,
        )
        val compound = sr + buildSdes(ssrc, CNAME)
        val wire = if (mainProfile) buildGre(compound, isRtcp = true) else compound
        try {
            sock.send(DatagramPacket(wire, wire.size, peer))
        } catch (_: Throwable) { /* non-fatal */ }
    }

    /**
     * RTCP SDES packet (RFC 3550 §6.5) with a single CNAME item — the minimum librist needs
     * to complete its receive-side handshake. One chunk: SSRC + {type=1 CNAME, len, text} +
     * a null terminator, zero-padded to a 32-bit boundary.
     */
    private fun buildSdes(ssrc: Int, cname: String): ByteArray {
        val name = cname.toByteArray(Charsets.US_ASCII)
        // chunk = SSRC(4) + [type(1)+len(1)+text] + null(1), padded to a multiple of 4.
        val contentLen = 4 + (2 + name.size) + 1
        val padded = (contentLen + 3) and 3.inv()
        val pkt = ByteArray(4 + padded)
        pkt[0] = 0x81.toByte()                 // V=2, P=0, SC=1
        pkt[1] = 202.toByte()                  // PT = SDES
        putBe16(pkt, 2, padded / 4)            // length in 32-bit words minus 1 (= payload words)
        putBe32(pkt, 4, ssrc)
        pkt[8] = 1                             // item type: CNAME
        pkt[9] = name.size.toByte()
        System.arraycopy(name, 0, pkt, 10, name.size)
        // remaining bytes already zero → CNAME-list terminator + padding
        return pkt
    }

    /**
     * Enqueue one MPEG-TS packet (must be exactly 188 bytes). Dropped before a peer is
     * connected so the bounded queue doesn't fill with frames referencing an unseen IDR.
     */
    fun send(tsPacket: ByteArray) {
        if (!running.get()) return
        if (currentState != State.CONNECTED) return
        if (queue.offer(tsPacket)) return
        try {
            if (!queue.offer(tsPacket, 500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "RIST queue full for 500ms — link slower than encode rate, dropping packet")
            }
        } catch (_: InterruptedException) { /* closing */ }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopInternal()
        currentState = State.CLOSED
        onState(State.CLOSED)
    }

    private fun stopInternal() {
        try { dataSocket?.close() } catch (_: Throwable) {}
        try { rtcpSocket?.close() } catch (_: Throwable) {}
        dataSocket = null
        rtcpSocket = null
        connectJob?.cancel(); connectJob = null
        pumpJob?.cancel(); pumpJob = null
        rtcpJob?.cancel(); rtcpJob = null
        queue.clear()
        scope.cancel()
    }

    private fun putBe16(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 8) and 0xFF).toByte(); b[o + 1] = (v and 0xFF).toByte()
    }

    private fun putBe32(b: ByteArray, o: Int, v: Int) {
        b[o] = ((v ushr 24) and 0xFF).toByte(); b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte(); b[o + 3] = (v and 0xFF).toByte()
    }

    private fun be16(b: ByteArray, o: Int): Int = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun be32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    companion object {
        private const val TAG = "RistPublisher"
        private const val RTP_HEADER = 12
        private const val MP2T_PAYLOAD_TYPE = 33
        private const val TS_PER_RTP = 7                 // 7×188 = 1316, the canonical MP2T-over-RTP size
        // RTCP feedback packet types + their first-byte flags (librist rtp.h).
        private const val RTCP_RTPFB_BITMASK = 205       // PTYPE_NACK_BITMASK
        private const val RTCP_RANGE_NACK = 204          // PTYPE_NACK_CUSTOM (also RIST echo APP)
        private const val NACK_RANGE_FLAGS = 0x80        // RTCP_NACK_RANGE_FLAGS (echo req/resp = 0x82/0x83)
        private const val NACK_BITMASK_FLAGS = 0x81      // RTCP_NACK_BITMASK_FLAGS
        private const val MAX_NACK_RUN = 1024            // cap a single range-NACK record's run length
        // GRE protocol (ethertype) values, librist src/proto/gre.h.
        private const val GRE_PROTO_VSF = 0xCCE0
        private const val GRE_PROTO_KEEPALIVE = 0x88B5
        // RIST default virtual ports (librist peer.h): src 1971, dst 1968.
        private const val VIRT_SRC_PORT = 1971
        private const val VIRT_DST_PORT = 1968
        private const val MAX_QUEUE = 20_000
        private const val RETRANS_RING = 8192
        private const val RETRANS_MASK = RETRANS_RING - 1
        private const val FLUSH_POLL_MS = 20L
        private const val RTCP_POLL_MS = 100
        // Send SR+SDES every ~100 ms — comfortably inside librist's ~270 ms listening-peer
        // handshake window, so the SDES it waits for always lands in time.
        private const val SR_INTERVAL_NS = 100_000_000L
        // How many SR+SDES to fire back-to-back the instant a peer connects, to win the
        // handshake on the first try instead of racing the periodic cadence.
        private const val HANDSHAKE_BURST = 3
        private const val CNAME = "lenscast"
    }
}
