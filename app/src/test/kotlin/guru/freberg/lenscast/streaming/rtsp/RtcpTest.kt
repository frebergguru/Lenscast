package guru.freberg.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer

class RtcpTest {

    @Test fun `SR header has V=2, PT=200, length=6`() {
        val pkt = Rtcp.buildSenderReport(
            ssrc = 0x11223344,
            ntpMillis = 1_700_000_000_000L,
            rtpTimestamp = 0x1000,
            packetCount = 42,
            octetCount = 12345,
        )
        assertEquals("packet size", 28, pkt.size)
        // First byte: V=2 (top 2 bits = 10), P=0, RC=0  → 0x80.
        assertEquals(0x80.toByte(), pkt[0])
        // Second byte: PT=200.
        assertEquals(200.toByte(), pkt[1])
        // Bytes 2-3: length=6 in big-endian.
        val len = ((pkt[2].toInt() and 0xFF) shl 8) or (pkt[3].toInt() and 0xFF)
        assertEquals(6, len)
    }

    @Test fun `SSRC is encoded at offset 4 in big-endian`() {
        val pkt = Rtcp.buildSenderReport(0x12345678, 0L, 0, 0, 0)
        val b = ByteBuffer.wrap(pkt)
        b.position(4)
        assertEquals(0x12345678, b.int)
    }

    @Test fun `NTP seconds and fraction are correct for a known timestamp`() {
        // 2_208_988_800 sec offset between 1900-01-01 and 1970-01-01.
        // At 1970-01-01T00:00:00Z, NTP seconds = 2_208_988_800.
        val pkt = Rtcp.buildSenderReport(0, 0L, 0, 0, 0)
        val b = ByteBuffer.wrap(pkt)
        b.position(8)
        assertEquals(2_208_988_800L.toInt(), b.int)
        assertEquals(0, b.int) // fraction = 0
    }

    @Test fun `packet and octet counts land at the right offsets`() {
        val pkt = Rtcp.buildSenderReport(0, 0L, 0, 100, 200)
        val b = ByteBuffer.wrap(pkt)
        b.position(20)
        assertEquals(100, b.int)
        assertEquals(200, b.int)
    }

    @Test fun `parses one report block out of a textbook RR`() {
        // RR with RC=1, PT=201, length = (8 + 24)/4 - 1 = 7
        val pkt = ByteArray(32)
        val b = ByteBuffer.wrap(pkt)
        b.put(0x81.toByte())            // V=2, P=0, RC=1
        b.put(201.toByte())             // PT=201 (RR)
        b.putShort(7.toShort())         // length
        b.putInt(0xCAFEBABE.toInt())    // reporter SSRC
        // Report block:
        b.putInt(0x11223344)            // source SSRC (i.e. our sender)
        b.put(64.toByte())              // fractionLost = 64/256 = 25 %
        b.put(0); b.put(0); b.put(123.toByte()) // cumulativeLost = 123
        b.putInt(0)                     // extended seq
        b.putInt(7777)                  // jitter
        b.putInt(0x0A0B0C0D)            // LSR
        b.putInt(0x00010000)           // DLSR = 1.0 s in 1/65536 units

        val reports = Rtcp.parseReceiverReports(pkt)
        assertEquals(1, reports.size)
        val r = reports[0]
        assertEquals(0xCAFEBABE.toInt(), r.reporterSsrc)
        assertEquals(0x11223344, r.sourceSsrc)
        assertEquals(64, r.fractionLost)
        assertEquals(0.25, r.fractionLostFraction, 0.001)
        assertEquals(123, r.cumulativeLost)
        assertEquals(7777, r.jitter)
        assertEquals(0x0A0B0C0D, r.lsr)
        assertEquals(0x00010000, r.dlsr)
    }

    @Test fun `roundTripMs subtracts LSR and DLSR`() {
        // Receiver echoes the LSR from an SR we sent at `sentMs`, having held the RR for ~250 ms
        // (DLSR), and we receive it ~600 ms after that SR — RTT should be ~350 ms.
        val sentMs = 1_700_000_000_000L
        val lsr = Rtcp.ntpMiddle32(sentMs).toInt()
        val dlsr = (0.25 * 65536).toInt()
        val report = Rtcp.ReceiverReport(0, 0, 0, 0, 0, lsr = lsr, dlsr = dlsr)
        val rtt = Rtcp.roundTripMs(report, sentMs + 600)
        assertEquals(350.0, rtt.toDouble(), 5.0)
    }

    @Test fun `roundTripMs returns -1 when the receiver has no SR yet`() {
        assertEquals(-1, Rtcp.roundTripMs(Rtcp.ReceiverReport(0, 0, 0, 0, 0, lsr = 0, dlsr = 0), 1_700_000_000_000L))
    }

    @Test fun `rejects unknown packet type without throwing`() {
        val pkt = ByteArray(8)
        pkt[0] = 0x80.toByte()
        pkt[1] = 250.toByte()           // not RR/SR
        pkt[3] = 1                      // length = 1
        assertEquals(0, Rtcp.parseReceiverReports(pkt).size)
    }

    @Test fun `rejects truncated RR without throwing`() {
        // RR header claims length=7 but the buffer is only 16 bytes long.
        val pkt = ByteArray(16)
        pkt[0] = 0x81.toByte()
        pkt[1] = 201.toByte()
        pkt[3] = 7
        assertEquals(0, Rtcp.parseReceiverReports(pkt).size)
    }

    @Test fun `cumulative-loss is sign-extended for negative values`() {
        val pkt = ByteArray(32)
        val b = ByteBuffer.wrap(pkt)
        b.put(0x81.toByte()); b.put(201.toByte()); b.putShort(7.toShort())
        b.putInt(0)
        b.putInt(0x11223344)
        b.put(0)
        // -1 as 24-bit two's complement = 0xFFFFFF
        b.put(0xFF.toByte()); b.put(0xFF.toByte()); b.put(0xFF.toByte())
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0)
        val reports = Rtcp.parseReceiverReports(pkt)
        assertEquals(1, reports.size)
        assertEquals(-1, reports[0].cumulativeLost)
    }
}
