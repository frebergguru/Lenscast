package guru.freberg.lenscast.streaming.rtsp

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds RTP packets. Header layout per RFC 3550:
 *
 *  ```
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  |V=2|P|X|  CC   |M|     PT      |       sequence number         |
 *  |                           timestamp                           |
 *  |           synchronization source (SSRC) identifier            |
 *  ```
 *
 * Both video (PT 96, 90 kHz clock) and audio (PT 97, sample-rate clock) use this layout.
 * Sequence numbers are 16-bit, wrap at 0xFFFF. SSRC is a per-stream random 32-bit value.
 */
class RtpStream(
    val payloadType: Int,
    val ssrc: Int,
    val clockHz: Int,
) {
    private val seq = AtomicInteger((Math.random() * 0xFFFF).toInt() and 0xFFFF)
    @Volatile private var lastTs: Int = 0

    fun timestampFromUs(ptsUs: Long): Int = ((ptsUs * clockHz.toLong()) / 1_000_000L).toInt()
    fun nextSeq(): Int = seq.incrementAndGet() and 0xFFFF
    /** Snapshot of the next sequence number (without incrementing) — for RTP-Info reporting. */
    fun peekSeq(): Int = (seq.get() + 1) and 0xFFFF
    /** Last RTP timestamp we wrote (initial rtptime estimate for RTP-Info). */
    fun lastTimestamp(): Int = lastTs

    fun writeHeader(buf: ByteBuffer, marker: Boolean, ts: Int) {
        buf.put(0x80.toByte())                                   // V=2, P=0, X=0, CC=0
        buf.put(((if (marker) 0x80 else 0) or (payloadType and 0x7F)).toByte())
        val s = nextSeq()
        buf.put((s ushr 8).toByte()); buf.put(s.toByte())
        buf.putInt(ts)
        buf.putInt(ssrc)
        lastTs = ts
    }

    companion object {
        const val HEADER_SIZE = 12
        /**
         * Conservative MTU for RTP payload: 1500-byte Ethernet MTU minus IP (20) + UDP (8)
         * + RTP header (12) = 1460. We pick 1400 to leave headroom for VPNs and TCP-interleaved
         * 4-byte framing.
         */
        const val MTU = 1400
    }
}

/**
 * RFC 6184 H.264 packetization. We support the two modes that cover ~all real-world need:
 *
 *  - **Single NAL unit packet** when `nal.size <= MTU - 12`: the NAL is dropped straight
 *    into the RTP payload; its first byte (the NAL header) is preserved as-is.
 *  - **FU-A fragmentation** otherwise: the NAL is split across multiple RTP packets each
 *    prefixed with an FU indicator + FU header. Start/end bits mark the first/last
 *    fragment. The marker bit on the last RTP packet of the access unit signals frame end.
 *
 * STAP-A (multiple NALs in one packet) and MTAP are skipped — they'd be a minor bandwidth
 * win and add complexity around timestamp identity.
 */
object H264RtpPacketizer {

    /**
     * Packetize one H.264 NAL into one or more RTP packets.
     *
     * @param nal raw NAL bytes (no Annex-B start code).
     * @param tsRtp 32-bit RTP timestamp.
     * @param markerOnLast set the M bit on the last produced packet (true for end-of-frame NAL).
     * @return list of byte arrays, each an RTP packet ready to send.
     */
    fun packetize(stream: RtpStream, nal: ByteArray, tsRtp: Int, markerOnLast: Boolean): List<ByteArray> {
        if (nal.isEmpty()) return emptyList()
        val maxPayload = RtpStream.MTU - RtpStream.HEADER_SIZE

        if (nal.size <= maxPayload) {
            // Single NAL unit packet.
            val pkt = ByteArray(RtpStream.HEADER_SIZE + nal.size)
            val buf = ByteBuffer.wrap(pkt)
            stream.writeHeader(buf, marker = markerOnLast, ts = tsRtp)
            buf.put(nal)
            return listOf(pkt)
        }

        // FU-A. The first byte of the original NAL splits into:
        //   forbidden_zero_bit (1) | nri (2) | type (5)
        // FU indicator preserves F + NRI, sets type=28.
        // FU header: S/E/R(0)/type-of-original
        val nalHeader = nal[0].toInt() and 0xFF
        val f = nalHeader and 0x80
        val nri = nalHeader and 0x60
        val origType = nalHeader and 0x1F
        val fuIndicator = (f or nri or 28).toByte()

        // Iterate the original NAL directly starting at offset 1 (the byte after the
        // header that we've already encoded into fuIndicator). Saves the copyOfRange.
        val fragMax = maxPayload - 2 // FU indicator + FU header
        val out = mutableListOf<ByteArray>()
        var srcOffset = 1
        while (srcOffset < nal.size) {
            val remaining = nal.size - srcOffset
            val take = minOf(fragMax, remaining)
            val isFirst = (srcOffset == 1)
            val isLast = (srcOffset + take >= nal.size)
            val fuHeader = ((if (isFirst) 0x80 else 0) or (if (isLast) 0x40 else 0) or origType).toByte()
            val pkt = ByteArray(RtpStream.HEADER_SIZE + 2 + take)
            val buf = ByteBuffer.wrap(pkt)
            stream.writeHeader(buf, marker = isLast && markerOnLast, ts = tsRtp)
            buf.put(fuIndicator); buf.put(fuHeader)
            buf.put(nal, srcOffset, take)
            out += pkt
            srcOffset += take
        }
        return out
    }
}

/**
 * RFC 3640 AAC-hbr (high bit-rate) packetization in single-frame-per-packet mode.
 *
 * Each RTP packet payload is:
 *   - 2-byte AU-headers-length field (in bits) = 16 for one AU with 13-bit size + 3-bit index
 *   - 2-byte AU-header for the single AAC access unit: size << 3
 *   - the AU bytes themselves
 *
 * AAC frames are typically 100-700 bytes — comfortably below MTU — so we never need
 * fragmentation here. If an AU did exceed MTU we'd skip it (encoder bitrate gone wild).
 */
object AacRtpPacketizer {

    fun packetize(stream: RtpStream, au: ByteArray, tsRtp: Int): List<ByteArray> {
        if (au.isEmpty()) return emptyList()
        val payloadSize = 2 + 2 + au.size
        if (payloadSize + RtpStream.HEADER_SIZE > RtpStream.MTU) return emptyList()
        val pkt = ByteArray(RtpStream.HEADER_SIZE + payloadSize)
        val buf = ByteBuffer.wrap(pkt)
        stream.writeHeader(buf, marker = true, ts = tsRtp)
        // AU-headers-length: total header bits = 16 (one AU header of 13+3 bits).
        buf.put(0x00); buf.put(0x10)
        // AU-header: size (13 bits) << 3 | index (3 bits = 0)
        val auSize = au.size and 0x1FFF
        buf.put((auSize ushr 5).toByte())
        buf.put(((auSize and 0x1F) shl 3).toByte())
        buf.put(au)
        return listOf(pkt)
    }
}
