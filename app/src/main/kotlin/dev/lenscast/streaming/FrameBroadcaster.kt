package dev.lenscast.streaming

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-producer, lock-free "latest frame" slot.
 *
 * Producer writes the most recent JPEG byte array; consumers (HTTP client coroutines) read at
 * their own cadence. If a client is slow, it naturally drops frames — no per-client queue
 * pressure. The [frameSeq] counter lets consumers detect "nothing new since last read".
 */
class FrameBroadcaster {
    private val slot = AtomicReference<ByteArray?>(null)
    private val seq = AtomicLong(0)
    private val clients = AtomicInteger(0)
    private val framesProduced = AtomicLong(0)

    fun publish(jpeg: ByteArray) {
        slot.set(jpeg)
        seq.incrementAndGet()
        framesProduced.incrementAndGet()
    }

    fun latest(): Pair<ByteArray, Long>? {
        val s = seq.get()
        val b = slot.get() ?: return null
        return b to s
    }

    fun framesProduced(): Long = framesProduced.get()

    fun clientCount(): Int = clients.get()
    fun onClientConnected() { clients.incrementAndGet() }
    fun onClientDisconnected() { clients.decrementAndGet() }
}
