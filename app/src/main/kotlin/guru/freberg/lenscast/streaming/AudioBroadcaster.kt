package guru.freberg.lenscast.streaming

import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fan-out for the MJPEG-path audio sidecar. Unlike [FrameBroadcaster] (which keeps only
 * the latest video frame because clients can tolerate dropped video), audio must be
 * delivered as a continuous stream — every AAC AU has to reach every connected client in
 * order, otherwise the receiver gets pops, dropouts and clock drift.
 *
 * Implementation: each HTTP client owns a [Channel] subscribed here. The encoder thread
 * calls [publish] which `trySend`s into every channel. If a client falls badly behind
 * (channel buffer full), we drop *that* client's oldest AU rather than the producer
 * blocking — a slow client gets brief audio glitches but the encoder, RTSP path, and
 * other clients are unaffected.
 */
class AudioBroadcaster {
    private val subscribers = CopyOnWriteArrayList<Channel<ByteArray>>()
    private val clients = AtomicInteger(0)

    /** PCM-16LE configuration carried alongside the byte stream so the WAV header matches. */
    @Volatile var sampleRate: Int = 44_100
    @Volatile var channels: Int = 1

    fun publish(au: ByteArray) {
        for (ch in subscribers) {
            // Lossy fan-out: trySend returns false if the channel buffer is full. We
            // prefer to drop one of this client's queued AUs over blocking the encoder
            // thread — the cost is a tiny audible glitch for the slow client.
            val ok = ch.trySend(au).isSuccess
            if (!ok) {
                // Drain one slot then retry once. This gives the slow client a fresh
                // sample at the cost of the oldest one — keeps end-to-end latency bounded
                // instead of letting a stuck channel stay full forever.
                ch.tryReceive()
                ch.trySend(au)
            }
        }
    }

    /**
     * Subscribe a new client. Buffer is sized to cover ~1.5 s of audio at 43 AU/s; that's
     * the cushion before lossy drop kicks in. Returns the channel; the caller must call
     * [unsubscribe] in its finally block.
     */
    fun subscribe(): Channel<ByteArray> {
        val ch = Channel<ByteArray>(capacity = 64)
        subscribers += ch
        return ch
    }

    fun unsubscribe(ch: Channel<ByteArray>) {
        subscribers -= ch
        ch.close()
    }

    fun clientCount(): Int = clients.get()
    fun onClientConnected() { clients.incrementAndGet() }
    fun onClientDisconnected() { clients.decrementAndGet() }
}
