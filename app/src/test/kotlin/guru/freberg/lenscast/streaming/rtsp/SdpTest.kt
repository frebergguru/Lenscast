package guru.freberg.lenscast.streaming.rtsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SdpTest {

    private val sps = byteArrayOf(0x67, 0x42.toByte(), 0x00, 0x1f, 0x96.toByte(), 0x52, 0x80.toByte(), 0x33, 0xee.toByte())
    private val pps = byteArrayOf(0x68.toByte(), 0xee.toByte(), 0x3c.toByte(), 0x80.toByte())

    @Test fun `video-only SDP has the required RFC sections in order`() {
        val sdp = Sdp.build(
            sessionId = 1234567890L,
            video = Sdp.VideoTrack(96, sps, pps),
            audio = null,
        )
        val lines = sdp.split("\r\n")
        assertEquals("v=0", lines[0])
        assertTrue(lines[1].startsWith("o=- 1234567890 1234567890 IN IP4"))
        assertEquals("s=Lenscast", lines[2])
        assertEquals("c=IN IP4 0.0.0.0", lines[3])
        assertEquals("t=0 0", lines[4])
        assertTrue("a=control:* must precede media sections", lines[5] == "a=control:*")
        assertTrue("m=video first", lines[6].startsWith("m=video 0 RTP/AVP 96"))
    }

    @Test fun `profile-level-id is derived from SPS bytes 1-3`() {
        val sdp = Sdp.build(1L, Sdp.VideoTrack(96, sps, pps), null)
        // sps[1..3] = 0x42 0x00 0x1f → "42001f"
        assertTrue("expected profile-level-id=42001f", sdp.contains("profile-level-id=42001f"))
    }

    @Test fun `sprop-parameter-sets carries base64 SPS,PPS without padding mangling`() {
        val sdp = Sdp.build(1L, Sdp.VideoTrack(96, sps, pps), null)
        val expectedSps = java.util.Base64.getEncoder().encodeToString(sps)
        val expectedPps = java.util.Base64.getEncoder().encodeToString(pps)
        assertTrue(sdp.contains("sprop-parameter-sets=$expectedSps,$expectedPps"))
    }

    @Test fun `audio track appended after video when present`() {
        val asc = byteArrayOf(0x12, 0x10)
        val sdp = Sdp.build(
            sessionId = 1L,
            video = Sdp.VideoTrack(96, sps, pps),
            audio = Sdp.AudioTrack(97, sampleRate = 44_100, channels = 1, asc = asc),
        )
        val mVideo = sdp.indexOf("m=video")
        val mAudio = sdp.indexOf("m=audio")
        assertTrue("m=video missing", mVideo >= 0)
        assertTrue("m=audio missing", mAudio >= 0)
        assertTrue("audio must come after video", mAudio > mVideo)
        assertTrue(sdp.contains("a=rtpmap:97 mpeg4-generic/44100/1"))
        assertTrue(sdp.contains("config=1210"))
        assertTrue(sdp.contains("a=control:trackID=1"))
    }

    @Test fun `audio is omitted entirely when null`() {
        val sdp = Sdp.build(1L, Sdp.VideoTrack(96, sps, pps), null)
        assertFalse("audio section leaked into video-only SDP", sdp.contains("m=audio"))
        assertFalse(sdp.contains("a=rtpmap:97"))
    }
}
