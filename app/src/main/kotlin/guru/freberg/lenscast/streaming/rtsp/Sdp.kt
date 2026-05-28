package guru.freberg.lenscast.streaming.rtsp

import android.util.Base64

/**
 * Generates the SDP body that describes our RTSP stream(s) to the client (e.g., OBS).
 *
 * Layout:
 *
 *  ```
 *  v=0
 *  o=- <session> <session> IN IP4 0.0.0.0
 *  s=Lenscast
 *  c=IN IP4 0.0.0.0
 *  t=0 0
 *  a=control:*                                ← session-level aggregate control URL
 *  m=video 0 RTP/AVP 96
 *  a=rtpmap:96 H264/90000
 *  a=fmtp:96 packetization-mode=1;profile-level-id=<...>;sprop-parameter-sets=<sps>,<pps>
 *  a=control:streamid=0
 *  m=audio 0 RTP/AVP 97                       ← only when audio is enabled
 *  a=rtpmap:97 mpeg4-generic/<rate>/<chan>
 *  a=fmtp:97 streamtype=5;profile-level-id=1;mode=AAC-hbr;sizelength=13;indexlength=3;indexdeltalength=3;config=<asc-hex>
 *  a=control:streamid=1
 *  ```
 */
object Sdp {

    fun build(
        sessionId: Long,
        video: VideoTrack,
        audio: AudioTrack?,
    ): String {
        val sb = StringBuilder()
        sb.append("v=0\r\n")
        sb.append("o=- ").append(sessionId).append(' ').append(sessionId).append(" IN IP4 0.0.0.0\r\n")
        sb.append("s=Lenscast\r\n")
        sb.append("c=IN IP4 0.0.0.0\r\n")
        sb.append("t=0 0\r\n")
        sb.append("a=control:*\r\n")

        // Video media description
        sb.append("m=video 0 RTP/AVP ").append(video.payloadType).append("\r\n")
        sb.append("a=rtpmap:").append(video.payloadType).append(" H264/90000\r\n")
        val profileLevelIdHex = if (video.sps.size >= 4) "%02x%02x%02x".format(
            video.sps[1].toInt() and 0xFF, video.sps[2].toInt() and 0xFF, video.sps[3].toInt() and 0xFF,
        ) else "42001f"
        val spsB64 = Base64.encodeToString(video.sps, Base64.NO_WRAP)
        val ppsB64 = Base64.encodeToString(video.pps, Base64.NO_WRAP)
        sb.append("a=fmtp:").append(video.payloadType)
            .append(" packetization-mode=1;profile-level-id=").append(profileLevelIdHex)
            .append(";sprop-parameter-sets=").append(spsB64).append(',').append(ppsB64).append("\r\n")
        // Use trackID=N — broadest client compatibility (Apple convention, VLC + GStreamer + FFmpeg all parse it correctly).
        sb.append("a=control:trackID=0\r\n")

        // Audio media description (optional)
        if (audio != null) {
            sb.append("m=audio 0 RTP/AVP ").append(audio.payloadType).append("\r\n")
            sb.append("a=rtpmap:").append(audio.payloadType)
                .append(" mpeg4-generic/").append(audio.sampleRate).append('/').append(audio.channels).append("\r\n")
            val ascHex = audio.asc.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
            sb.append("a=fmtp:").append(audio.payloadType)
                .append(" streamtype=5;profile-level-id=1;mode=AAC-hbr;")
                .append("sizelength=13;indexlength=3;indexdeltalength=3;config=").append(ascHex).append("\r\n")
            sb.append("a=control:trackID=1\r\n")
        }
        return sb.toString()
    }

    data class VideoTrack(val payloadType: Int, val sps: ByteArray, val pps: ByteArray)
    data class AudioTrack(val payloadType: Int, val sampleRate: Int, val channels: Int, val asc: ByteArray)
}
