package dev.lenscast.streaming.rtsp

import java.nio.ByteBuffer

/**
 * RTCP Sender Report (RFC 3550 §6.4.1) — a tiny 28-byte packet:
 *
 * ```
 *  0                   1                   2                   3
 *  0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 * |V=2|P|  RC=0 |   PT=200      |          length=6 (in 32-bit words minus 1)
 * |                    SSRC of sender                             |
 * |              NTP timestamp, most significant word             |
 * |              NTP timestamp, least significant word            |
 * |                   RTP timestamp                               |
 * |               sender's packet count                           |
 * |               sender's octet count                            |
 * ```
 *
 * VLC and other strict players rely on the SR's NTP↔RTP timestamp pair to lock a
 * playback clock; without it they buffer indefinitely (or display nothing). FFmpeg
 * is lenient and renders without — but emitting SR makes any compliant client work.
 */
object Rtcp {
    const val SR_LENGTH = 28
    private const val NTP_EPOCH_OFFSET_SECONDS = 2_208_988_800L // seconds between 1900-01-01 and 1970-01-01

    fun buildSenderReport(
        ssrc: Int,
        ntpMillis: Long,
        rtpTimestamp: Int,
        packetCount: Long,
        octetCount: Long,
    ): ByteArray {
        val pkt = ByteArray(SR_LENGTH)
        val b = ByteBuffer.wrap(pkt)
        b.put(0x80.toByte())           // V=2, P=0, RC=0
        b.put(200.toByte())            // PT=200 (SR)
        b.putShort(6.toShort())        // length: total bytes/4 - 1 = 28/4 - 1 = 6
        b.putInt(ssrc)
        // NTP timestamp split into 32-bit seconds + 32-bit fraction.
        val ntpSecs = (ntpMillis / 1000L) + NTP_EPOCH_OFFSET_SECONDS
        val ntpFrac = ((ntpMillis % 1000L) shl 32) / 1000L
        b.putInt(ntpSecs.toInt())
        b.putInt(ntpFrac.toInt())
        b.putInt(rtpTimestamp)
        b.putInt(packetCount.toInt())
        b.putInt(octetCount.toInt())
        return pkt
    }
}
