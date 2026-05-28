package guru.freberg.lenscast.streaming.rtsp

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

    /**
     * One report block from an RTCP Receiver Report (RFC 3550 §6.4.2). The fraction-lost
     * field is 8 bits where 256 = 100 % loss (i.e. `fractionLost / 256.0` is the percentage).
     */
    data class ReceiverReport(
        /** SSRC of the receiver who sent this RR. */
        val reporterSsrc: Int,
        /** SSRC the receiver is reporting on (i.e. *our* sender SSRC). */
        val sourceSsrc: Int,
        /** 0..255 — fraction of packets lost since the last RR. */
        val fractionLost: Int,
        /** 24-bit cumulative number of packets lost (sign-extended). */
        val cumulativeLost: Int,
        /** Interarrival jitter in RTP timestamp units. */
        val jitter: Int,
    ) {
        /** Fraction lost as a 0.0..1.0 number for control-loop math. */
        val fractionLostFraction: Double get() = fractionLost / 256.0
    }

    /**
     * Parse one or more RTCP report blocks out of a compound RTCP packet (RR or SR with RC>0).
     * Returns an empty list when the packet isn't an RR/SR or when the bytes are malformed.
     * Bytes after the parsed packet (a compound RTCP typically has multiple sub-packets
     * concatenated) are ignored — call again with the offset advanced.
     */
    fun parseReceiverReports(pkt: ByteArray, offset: Int = 0, len: Int = pkt.size - offset): List<ReceiverReport> {
        if (len < 8) return emptyList()
        val firstByte = pkt[offset].toInt() and 0xFF
        val version = (firstByte ushr 6) and 0x3
        val rc = firstByte and 0x1F
        if (version != 2 || rc == 0) return emptyList()
        val pt = pkt[offset + 1].toInt() and 0xFF
        val length = ((pkt[offset + 2].toInt() and 0xFF) shl 8) or (pkt[offset + 3].toInt() and 0xFF)
        val totalBytes = (length + 1) * 4
        if (totalBytes > len) return emptyList()
        // RR (PT=201) header is 8 bytes (header + reporter SSRC), then RC × 24-byte blocks.
        // SR (PT=200) header is 28 bytes (header + sender info), then RC × 24-byte blocks.
        val (reporterSsrc, blocksOffset) = when (pt) {
            201 -> readInt(pkt, offset + 4) to (offset + 8)
            200 -> readInt(pkt, offset + 4) to (offset + 28)
            else -> return emptyList()
        }
        val reports = ArrayList<ReceiverReport>(rc)
        for (i in 0 until rc) {
            val o = blocksOffset + i * 24
            if (o + 24 > offset + totalBytes) break
            val sourceSsrc = readInt(pkt, o)
            val fractionLost = pkt[o + 4].toInt() and 0xFF
            val cumulativeLost = signExtend24(
                ((pkt[o + 5].toInt() and 0xFF) shl 16) or
                ((pkt[o + 6].toInt() and 0xFF) shl 8) or
                (pkt[o + 7].toInt() and 0xFF)
            )
            // Bytes 8..11 = extended highest seq received; we don't surface it.
            val jitter = readInt(pkt, o + 12)
            reports += ReceiverReport(reporterSsrc, sourceSsrc, fractionLost, cumulativeLost, jitter)
        }
        return reports
    }

    private fun readInt(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or
        ((b[o + 1].toInt() and 0xFF) shl 16) or
        ((b[o + 2].toInt() and 0xFF) shl 8) or
        (b[o + 3].toInt() and 0xFF)

    private fun signExtend24(v: Int): Int = if ((v and 0x800000) != 0) v or 0xFF000000.toInt() else v

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
