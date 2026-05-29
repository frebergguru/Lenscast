package guru.freberg.lenscast.streaming.srt

import android.util.Log
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.EpollOpt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.Epoll
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the SRT socket(s). Two modes:
 *  - [Mode.CALLER]:   `connect(host, port)` and push TS packets out.
 *  - [Mode.LISTENER]: `bind(port).listen()`, accept one client at a time, push to it.
 *
 * Caller is the easiest to NAT-traverse (the phone dials out). Listener requires the
 * receiver to reach the phone — usable on the same LAN or with port forwarding.
 *
 * `srtdroid` initialises libsrt globally on first construction of [Srt]; we ref-count
 * via [Srt.startUp] / [Srt.cleanUp].
 */
class SrtPublisher(
    private val config: Config,
    /** Surfaces transport-layer errors and lifecycle events to the manager. */
    private val onState: (State) -> Unit = {},
) {

    enum class Mode { CALLER, LISTENER }
    enum class State { IDLE, CONNECTING, CONNECTED, FAILED, CLOSED }

    data class Config(
        val mode: Mode,
        /** Remote host for [Mode.CALLER]; bind address (typically "0.0.0.0") for [Mode.LISTENER]. */
        val host: String,
        val port: Int,
        /** Empty = no encryption. */
        val passphrase: String,
        /** SRT ARQ latency window in milliseconds. */
        val latencyMs: Int,
        /** Optional SRT streamid (CALLER mode). */
        val streamId: String,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var connectJob: Job? = null
    private var pumpJob: Job? = null
    private val queue = LinkedBlockingQueue<ByteArray>(MAX_QUEUE + 1)
    private val running = AtomicBoolean(false)
    @Volatile private var currentState: State = State.IDLE
    @Volatile private var socket: SrtSocket? = null
    @Volatile private var clientSocket: SrtSocket? = null
    @Volatile private var acceptEpoll: Epoll? = null
    @Volatile private var bytesSent: Long = 0L
    @Volatile private var droppedPackets: Long = 0L

    fun state(): State = currentState

    fun bytesSent(): Long = bytesSent

    /** MPEG-TS packets dropped because the link couldn't keep up with the encode rate. */
    fun droppedPackets(): Long = droppedPackets

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Srt.startUp()
        currentState = State.CONNECTING
        onState(State.CONNECTING)
        connectJob = scope.launch {
            try {
                when (config.mode) {
                    Mode.CALLER -> startCaller()
                    Mode.LISTENER -> startListener()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "SRT connect failed", t)
                currentState = State.FAILED
                onState(State.FAILED)
                stopInternal()
            }
        }
    }

    private fun startCaller() {
        val s = SrtSocket()
        configureCommon(s)
        socket = s
        Log.i(TAG, "SRT caller dialing ${config.host}:${config.port}")
        s.connect(InetSocketAddress(config.host, config.port))
        currentState = State.CONNECTED
        // Drop everything the muxer queued while we were waiting for a client.
        // Those packets reference an IDR the new client will never see (queue
        // wrap-around silently evicts the oldest = the keyframe). Starting from
        // an empty queue lets the muxer's next IDR-aligned write be the first
        // thing the client sees.
        queue.clear()
        onState(State.CONNECTED)
        startPump(s)
    }

    private fun startListener() {
        val s = SrtSocket()
        configureCommon(s)
        socket = s
        s.bind(InetSocketAddress("0.0.0.0", config.port))
        s.listen(1)
        // Non-blocking accept. With the default (blocking) accept, the call parks in
        // native libsrt; a concurrent socket.close() from stop()/restart then frees the
        // socket's condvar under the blocked call and aborts the whole process (SIGABRT
        // in CUDTUnited::accept — confirmed in a crash trace). Instead we poll accept-
        // readiness via epoll with a short timeout so the loop keeps returning to check
        // `running`, and [stop] joins this loop *before* closing the socket.
        s.setSockFlag(SockOpt.RCVSYN, false)
        val epoll = Epoll().also { acceptEpoll = it }
        epoll.addUSock(s, listOf(EpollOpt.IN, EpollOpt.ERR))
        Log.i(TAG, "SRT listening on 0.0.0.0:${config.port}")
        try {
            while (running.get()) {
                val ready = try {
                    epoll.uWait(ACCEPT_POLL_MS.toLong())
                } catch (_: Throwable) {
                    emptyList()
                }
                if (!running.get()) break
                // Only the listener socket is registered, so any IN event = a pending
                // connection ready to accept.
                val acceptable = ready.any { EpollOpt.IN in it.events }
                if (!acceptable) continue
                val client = try { s.accept().first } catch (_: Throwable) { continue }
                Log.i(TAG, "SRT accepted client from ${client.peerName}")
                clientSocket = client
                currentState = State.CONNECTED
                // Drop everything the muxer queued while we were waiting for a client.
                // Those packets reference an IDR the new client will never see (queue
                // wrap-around silently evicts the oldest = the keyframe). Starting from
                // an empty queue lets the muxer's next IDR-aligned write be the first
                // thing the client sees.
                queue.clear()
                onState(State.CONNECTED)
                // Single-client listener: drain the queue into this client until it
                // disconnects, then loop back to accept another. This matches the typical
                // OBS workflow where only one receiver pulls at a time.
                pumpUntilClosed(client)
                try { client.close() } catch (_: Throwable) {}
                clientSocket = null
                if (running.get()) {
                    currentState = State.CONNECTING
                    onState(State.CONNECTING)
                }
            }
        } finally {
            try { epoll.release() } catch (_: Throwable) {}
            acceptEpoll = null
        }
    }

    private fun configureCommon(s: SrtSocket) {
        // TRANSTYPE = LIVE — strict latency + bandwidth pacing, drops late packets at
        // the receiver. Required when we set LATENCY.
        s.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
        s.setSockFlag(SockOpt.LATENCY, config.latencyMs)
        // Bump the SRT send buffer well above the default 8 MB so a 4K keyframe
        // (~100 KB → 530 TS packets in one burst from the encoder) never overflows.
        // Without this, large keyframes occasionally truncate when the buffer's full
        // and the receiver sees a corrupt frame's worth of macroblock concealment.
        s.setSockFlag(SockOpt.SNDBUF, 32 * 1024 * 1024)
        s.setSockFlag(SockOpt.RCVBUF, 32 * 1024 * 1024)
        // OHEADBW: percent of the input bandwidth SRT is allowed to use for retransmits.
        // Default is 25 %; raising it to 50 % gives SRT more headroom to recover lost
        // packets on jittery WiFi, at the cost of a bit more bandwidth.
        s.setSockFlag(SockOpt.OHEADBW, 50)
        if (config.passphrase.isNotEmpty()) {
            // 16/24/32-byte AES key length. We use 16 (AES-128) which is libsrt's default.
            s.setSockFlag(SockOpt.PASSPHRASE, config.passphrase)
            s.setSockFlag(SockOpt.PBKEYLEN, 16)
        }
        if (config.streamId.isNotEmpty()) {
            s.setSockFlag(SockOpt.STREAMID, config.streamId)
        }
    }

    private fun startPump(s: SrtSocket) {
        pumpJob = scope.launch { pumpUntilClosed(s) }
    }

    private fun pumpUntilClosed(s: SrtSocket) {
        // SRT LIVE mode is built around 1316-byte (7×188) TS chunks — the default
        // SRT_PAYLOAD_SIZE. Sending one 188-byte packet per `send()` call works but
        // wastes ~70 % of the SRT data-packet overhead, which is exactly what causes
        // the TSBPD `RCV-DROPPED` flood when the receiver's latency window is small.
        // We coalesce up to 7 TS packets into a single send and flush whenever we'd
        // otherwise wait more than 5 ms — that's tight enough to keep glass-to-glass
        // latency low and still amortise the SRT framing cost.
        val bundle = ByteArray(TS_BUNDLE_BYTES)
        var bundleOffset = 0
        var bytesSentLog = 0L
        try {
            while (running.get() && s.isValid) {
                // Poll just long enough to ride out a Wi-Fi inter-packet gap. The flush-
                // partial-bundle path below kicks in on timeout, but we want it to fire
                // rarely — partial UDP sends are smaller and seem to get deprioritised on
                // some access points (the audio-vs-video asymmetric loss pattern wireshark
                // showed). 20 ms is a good balance: at the slowest audio rate (86 pps) we
                // still collect ~2 audio packets per window, so they ride out in a bundle
                // with whatever video happens to be nearby.
                val pkt = try { queue.poll(20, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return }
                if (pkt != null) {
                    if (bundleOffset + 188 > bundle.size) {
                        // Defensive: should never happen with the size check below, but if
                        // it ever did, ship what we have rather than corrupt memory.
                        val n = s.send(bundle.copyOf(bundleOffset))
                        if (n > 0) bytesSent += n
                        bundleOffset = 0
                    }
                    System.arraycopy(pkt, 0, bundle, bundleOffset, 188)
                    bundleOffset += 188
                    if (bundleOffset >= TS_BUNDLE_BYTES) {
                        // copyOf() defensively — srtdroid's send() *should* be synchronous
                        // (libsrt's srt_send copies into the SRT send buffer and returns)
                        // but if it ever queues asynchronously, reusing `bundle` while it's
                        // in-flight would corrupt the bytes on the wire. ~10 KB/s of
                        // allocation at typical SRT rates is negligible vs. the risk.
                        val n = s.send(bundle.copyOf())
                        if (n > 0) bytesSent += n
                        bytesSentLog += n
                        bundleOffset = 0
                    }
                } else if (bundleOffset > 0) {
                    // No new packet in 5 ms — flush whatever's buffered so the receiver
                    // doesn't sit waiting for the bundle to fill.
                    val n = s.send(bundle.copyOf(bundleOffset))
                    if (n > 0) bytesSent += n
                    bytesSentLog += n
                    bundleOffset = 0
                }
                if (bytesSentLog > 0 && bytesSent % (1024 * 1024) < 4096) {
                    // Once every ~1 MB, log progress. Helps confirm the pump is alive.
                    Log.d(TAG, "SRT pump: ${bytesSent / 1024} KB shipped")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "SRT pump ended: ${t.message}")
        }
    }

    /**
     * Enqueue one MPEG-TS packet (must be exactly 188 bytes). Lock-free; drops the oldest
     * packet when the queue exceeds [MAX_QUEUE] to keep latency bounded.
     */
    fun send(tsPacket: ByteArray) {
        if (!running.get()) return
        // Don't accumulate before a receiver is connected. Queueing pre-connect would
        // fill the bounded queue with frames the receiver will never see — wrap-around
        // eviction drops the OLDEST first, which is the keyframe. The receiver then
        // gets only P-frames at start and floods the log with "non-existing PPS".
        if (currentState != State.CONNECTED) return
        if (queue.offer(tsPacket)) return
        // Queue is full. Block briefly (back-pressure up to muxer → encoder → camera,
        // so MediaCodec drops a frame at the source rather than dropping a single TS
        // packet mid-PES which would corrupt an in-flight access unit on the wire).
        // 500 ms is long enough to ride out a Wi-Fi micro-stall, short enough that an
        // actually-broken link doesn't hang the encoder thread.
        try {
            if (!queue.offer(tsPacket, 500, TimeUnit.MILLISECONDS)) {
                droppedPackets++
                Log.w(TAG, "SRT queue full for 500ms — link slower than encode rate, dropping packet")
            }
        } catch (_: InterruptedException) { /* closing */ }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // CRITICAL ordering: wait for the accept + pump loops to observe running=false
        // and return *before* closing any socket. libsrt aborts the process if a socket
        // is closed while another thread is parked inside it (accept/recv). The accept
        // loop wakes every ACCEPT_POLL_MS; the pump every ~20 ms. Bounded join so a wedged
        // native call can't hang the caller (this runs on the restart path).
        try {
            runBlocking {
                withTimeoutOrNull(ACCEPT_POLL_MS + 700L) {
                    pumpJob?.join()
                    connectJob?.join()
                }
            }
        } catch (_: Throwable) {}
        stopInternal()
        currentState = State.CLOSED
        onState(State.CLOSED)
    }

    private fun stopInternal() {
        try { clientSocket?.close() } catch (_: Throwable) {}
        try { socket?.close() } catch (_: Throwable) {}
        clientSocket = null
        socket = null
        connectJob?.cancel(); connectJob = null
        pumpJob?.cancel(); pumpJob = null
        queue.clear()
        try { Srt.cleanUp() } catch (_: Throwable) {}
        scope.cancel()
    }

    companion object {
        private const val TAG = "SrtPublisher"
        // ~4 MB of buffered TS at 188 bytes per packet — enough to absorb the
        // ~530-packet burst of a 4K keyframe without forcing the muxer into back-pressure.
        private const val MAX_QUEUE = 20_000
        // 7 × 188 = 1316. The default SRT_PAYLOAD_SIZE for LIVE mode.
        private const val TS_BUNDLE_BYTES = 7 * 188
        // Accept-readiness poll interval. Short enough that stop() returns promptly (it
        // joins the accept loop, which can only exit between polls); long enough to not
        // busy-spin the listener thread while idle.
        private const val ACCEPT_POLL_MS = 100
    }
}
