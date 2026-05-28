package guru.freberg.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class H264RtpPacketizerTest {

    private fun stream() = RtpStream(payloadType = 96, ssrc = 0x12345678, clockHz = 90_000)

    @Test fun `small NAL goes out as a single packet`() {
        val nal = ByteArray(64) { 0x42 }
        // First byte is the NAL header. Give it a realistic value.
        nal[0] = 0x65 // IDR (forbidden=0, nri=3, type=5)
        val pkts = H264RtpPacketizer.packetize(stream(), nal, tsRtp = 0x12345, markerOnLast = true)
        assertEquals(1, pkts.size)
        val pkt = pkts[0]
        assertEquals(RtpStream.HEADER_SIZE + nal.size, pkt.size)
        // Marker bit + PT
        assertEquals((0x80 or 96).toByte(), pkt[1])
        // NAL header is byte 12 (right after RTP header), preserved as-is.
        assertEquals(0x65.toByte(), pkt[RtpStream.HEADER_SIZE])
    }

    @Test fun `large NAL fragments into FU-A with correct S and E bits`() {
        val size = RtpStream.MTU * 3 // forces fragmentation
        val nal = ByteArray(size) { 0x00 }
        nal[0] = 0x65 // IDR
        val pkts = H264RtpPacketizer.packetize(stream(), nal, tsRtp = 0x1000, markerOnLast = true)
        assertTrue("expected multiple fragments", pkts.size >= 3)

        // First fragment: FU header's S bit (0x80) set; E bit (0x40) clear.
        val firstFu = pkts.first()[RtpStream.HEADER_SIZE + 1]
        assertTrue("S bit must be set on first fragment", (firstFu.toInt() and 0x80) != 0)
        assertTrue("E bit must be clear on first fragment", (firstFu.toInt() and 0x40) == 0)

        // Last fragment: E bit set; S bit clear.
        val lastFu = pkts.last()[RtpStream.HEADER_SIZE + 1]
        assertTrue("E bit must be set on last fragment", (lastFu.toInt() and 0x40) != 0)
        assertTrue("S bit must be clear on last fragment", (lastFu.toInt() and 0x80) == 0)

        // Middle fragments: neither S nor E.
        for (i in 1 until pkts.size - 1) {
            val mid = pkts[i][RtpStream.HEADER_SIZE + 1]
            assertTrue("middle frag must have S=0", (mid.toInt() and 0x80) == 0)
            assertTrue("middle frag must have E=0", (mid.toInt() and 0x40) == 0)
        }
    }

    @Test fun `marker bit only set on last RTP packet of an access unit`() {
        val nal = ByteArray(RtpStream.MTU * 2) { 0x00 }
        nal[0] = 0x65
        val pkts = H264RtpPacketizer.packetize(stream(), nal, tsRtp = 0, markerOnLast = true)
        for (i in 0 until pkts.size - 1) {
            assertEquals("non-last must have marker=0",
                (96).toByte(), pkts[i][1])
        }
        assertEquals("last must have marker=1",
            (0x80 or 96).toByte(), pkts.last()[1])
    }

    @Test fun `FU indicator preserves F and NRI from the original NAL header`() {
        val nal = ByteArray(RtpStream.MTU * 2) { 0x00 }
        // NRI = 11 (binary) → byte = 0x60 | type. Type = 5 (IDR).
        nal[0] = 0x65
        val pkts = H264RtpPacketizer.packetize(stream(), nal, tsRtp = 0, markerOnLast = false)
        // FU indicator's F+NRI come from the original NAL's first byte upper 3 bits;
        // its type field is 28 (FU-A).
        val fuInd = pkts.first()[RtpStream.HEADER_SIZE].toInt() and 0xFF
        assertEquals("F+NRI must be preserved (0x60)", 0x60, fuInd and 0xE0)
        assertEquals("FU-A type must be 28", 28, fuInd and 0x1F)
    }

    @Test fun `RTP sequence numbers are monotonic across fragments`() {
        val nal = ByteArray(RtpStream.MTU * 3) { 0x00 }
        nal[0] = 0x65
        val pkts = H264RtpPacketizer.packetize(stream(), nal, tsRtp = 0, markerOnLast = false)
        val seqs = pkts.map { ((it[2].toInt() and 0xFF) shl 8) or (it[3].toInt() and 0xFF) }
        for (i in 1 until seqs.size) {
            assertEquals("seq must advance by 1", (seqs[i - 1] + 1) and 0xFFFF, seqs[i])
        }
    }
}
