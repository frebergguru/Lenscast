package dev.lenscast.streaming

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-producer, lock-free "latest frame" slot.
 *
 * Producer writes the most recent JPEG byte array; consumers (HTTP client coroutines) read at
 * their own cadence. If a client is slow, it naturally drops frames — no per-client queue
 * pressure. The seq counter lets consumers detect "nothing new since last read".
 *
 * Bytes + seq live in a single [AtomicReference] so readers always see a coherent
 * (bytes, seq) pair — never the new bytes with the old seq or vice versa.
 */
class FrameBroadcaster {
    private data class Frame(val bytes: ByteArray, val seq: Long)

    private val slot = AtomicReference<Frame?>(null)
    private val framesProduced = AtomicLong(0)
    private val clients = AtomicInteger(0)

    fun publish(jpeg: ByteArray) {
        val n = framesProduced.incrementAndGet()
        slot.set(Frame(jpeg, n))
    }

    fun latest(): Pair<ByteArray, Long>? {
        val f = slot.get() ?: return null
        return f.bytes to f.seq
    }

    fun framesProduced(): Long = framesProduced.get()

    fun clientCount(): Int = clients.get()
    fun onClientConnected() { clients.incrementAndGet() }
    fun onClientDisconnected() { clients.decrementAndGet() }
}
