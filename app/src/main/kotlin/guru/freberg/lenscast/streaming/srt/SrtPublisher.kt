package guru.freberg.lenscast.streaming.srt

import android.util.Log
import io.github.thibaultbee.srtdroid.core.Srt
import io.github.thibaultbee.srtdroid.core.enums.SockOpt
import io.github.thibaultbee.srtdroid.core.enums.Transtype
import io.github.thibaultbee.srtdroid.core.models.SrtSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
    @Volatile private var socket: SrtSocket? = null
    @Volatile private var clientSocket: SrtSocket? = null
    @Volatile private var bytesSent: Long = 0L

    fun bytesSent(): Long = bytesSent

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Srt.startUp()
        onState(State.CONNECTING)
        connectJob = scope.launch {
            try {
                when (config.mode) {
                    Mode.CALLER -> startCaller()
                    Mode.LISTENER -> startListener()
                }
            } catch (t: Throwable) {
                Log.w(TAG, "SRT connect failed", t)
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
        onState(State.CONNECTED)
        startPump(s)
    }

    private fun startListener() {
        val s = SrtSocket()
        configureCommon(s)
        socket = s
        s.bind(InetSocketAddress("0.0.0.0", config.port))
        s.listen(1)
        Log.i(TAG, "SRT listening on 0.0.0.0:${config.port}")
        while (running.get()) {
            val accepted = try { s.accept() } catch (_: Throwable) { break }
            val client = accepted.first
            Log.i(TAG, "SRT accepted client from ${client.peerName}")
            clientSocket = client
            onState(State.CONNECTED)
            // Single-client listener: drain the queue into this client until it disconnects,
            // then loop back to accept another. This matches the typical OBS workflow where
            // only one receiver pulls at a time.
            pumpUntilClosed(client)
            try { client.close() } catch (_: Throwable) {}
            clientSocket = null
            onState(State.CONNECTING)
        }
    }

    private fun configureCommon(s: SrtSocket) {
        // TRANSTYPE = LIVE — strict latency + bandwidth pacing, drops late packets at
        // the receiver. Required when we set LATENCY.
        s.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
        s.setSockFlag(SockOpt.LATENCY, config.latencyMs)
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
        try {
            while (running.get() && s.isValid) {
                val pkt = try { queue.poll(50, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { return }
                    ?: continue
                val n = s.send(pkt)
                if (n > 0) bytesSent += n
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
        // Drop oldest under back-pressure so the queue can't grow unbounded if the receiver
        // stalls. `offer` returns false when the bounded queue is full.
        if (!queue.offer(tsPacket)) {
            queue.poll()
            queue.offer(tsPacket)
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        stopInternal()
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
        // Roughly 1 MB of buffered TS at 188 bytes per packet.
        private const val MAX_QUEUE = 5000
    }
}
