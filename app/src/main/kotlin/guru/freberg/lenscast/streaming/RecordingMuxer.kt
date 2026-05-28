package guru.freberg.lenscast.streaming

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.nio.ByteBuffer

/**
 * Side-channel MediaMuxer that records the RTSP path's H.264 + AAC streams to MP4 in
 * `Movies/Lenscast/`. Hooked into [RtspManager] alongside the RTP packetizers — the
 * encoders produce the same Annex-B / raw-AAC samples already; this class just packages
 * them.
 *
 * Lifecycle:
 *  - construct with the expected track shape (audio optional).
 *  - feed video NALs (length-prefixed Annex-B with start codes) and audio AUs (raw AAC).
 *  - on the first frame of each track, the codec-specific data (csd-0) is captured and
 *    the track added; muxer starts once every expected track is known.
 *  - samples before muxer.start() are dropped — keyframe alignment fixes the first
 *    second of recorded video, no visible artefact.
 *  - [stop] finalises the MP4 and clears MediaStore's IS_PENDING.
 */
class RecordingMuxer private constructor(
    private val context: Context,
    private val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    private val expectAudio: Boolean,
) {
    private var muxer: MediaMuxer? = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var videoTrack = -1
    private var audioTrack = -1
    @Volatile private var started = false
    /** Pts of the first written video sample; used to rebase audio onto the same timeline. */
    private var videoStartPtsUs = -1L
    private var audioStartPtsUs = -1L

    fun addVideoTrack(width: Int, height: Int, sps: ByteArray, pps: ByteArray): Boolean {
        if (videoTrack >= 0) return true
        val m = muxer ?: return false
        val format = MediaFormat.createVideoFormat("video/avc", width, height)
        // csd-0 for AVC inside MediaMuxer is SPS followed by PPS, each prefixed with the
        // 4-byte start code. We have SPS/PPS as raw NALs (no start codes); reconstruct here.
        val csd = ByteBuffer.allocate(8 + sps.size + pps.size)
        csd.put(START_CODE).put(sps).put(START_CODE).put(pps).flip()
        format.setByteBuffer("csd-0", csd)
        return try {
            videoTrack = m.addTrack(format)
            maybeStart()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "addVideoTrack failed: ${t.message}")
            false
        }
    }

    fun addAudioTrack(sampleRate: Int, channels: Int, asc: ByteArray): Boolean {
        if (audioTrack >= 0) return true
        val m = muxer ?: return false
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channels)
        format.setByteBuffer("csd-0", ByteBuffer.wrap(asc))
        return try {
            audioTrack = m.addTrack(format)
            maybeStart()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "addAudioTrack failed: ${t.message}")
            false
        }
    }

    private fun maybeStart() {
        if (started) return
        val haveVideo = videoTrack >= 0
        val haveAudio = audioTrack >= 0 || !expectAudio
        if (haveVideo && haveAudio) {
            try {
                muxer?.start()
                started = true
                Log.i(TAG, "MP4 recording started, uri=$uri")
            } catch (t: Throwable) {
                Log.w(TAG, "muxer.start() failed: ${t.message}")
            }
        }
    }

    /**
     * Write an H.264 VCL NAL to the muxer. The encoder's callback delivers raw NAL bytes
     * (no start code) — we prepend the 4-byte start code so MediaMuxer can demarcate it.
     */
    fun writeVideo(nalNoStartCode: ByteArray, ptsUs: Long, isKey: Boolean) {
        if (!started || videoTrack < 0) return
        val m = muxer ?: return
        if (videoStartPtsUs < 0) videoStartPtsUs = ptsUs
        val pts = ptsUs - videoStartPtsUs
        val buf = ByteBuffer.allocate(4 + nalNoStartCode.size)
        buf.put(START_CODE).put(nalNoStartCode).flip()
        val info = MediaCodec.BufferInfo().apply {
            set(0, buf.remaining(), pts, if (isKey) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        }
        try {
            m.writeSampleData(videoTrack, buf, info)
        } catch (t: Throwable) {
            Log.w(TAG, "writeVideo failed: ${t.message}")
        }
    }

    fun writeAudio(au: ByteArray, ptsUs: Long) {
        if (!started || audioTrack < 0) return
        val m = muxer ?: return
        if (audioStartPtsUs < 0) audioStartPtsUs = ptsUs
        val pts = ptsUs - audioStartPtsUs
        val buf = ByteBuffer.wrap(au)
        val info = MediaCodec.BufferInfo().apply { set(0, au.size, pts, 0) }
        try {
            m.writeSampleData(audioTrack, buf, info)
        } catch (t: Throwable) {
            Log.w(TAG, "writeAudio failed: ${t.message}")
        }
    }

    fun stop(): Uri? {
        val m = muxer ?: return null
        muxer = null
        try { if (started) m.stop() } catch (t: Throwable) { Log.w(TAG, "muxer.stop: ${t.message}") }
        try { m.release() } catch (_: Throwable) {}
        try { pfd.close() } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                resolver.update(uri, values, null, null)
            } catch (_: Throwable) {}
        }
        return uri
    }

    companion object {
        private const val TAG = "RecordingMuxer"
        private val START_CODE = byteArrayOf(0, 0, 0, 1)

        /**
         * Create the MediaStore entry, open a writable file descriptor, and wrap it in
         * a new [RecordingMuxer]. Returns null if MediaStore refused the insert (no
         * permission, no Movies dir, etc.) — the caller should log and carry on
         * streaming without recording.
         */
        fun create(context: Context, expectAudio: Boolean): RecordingMuxer? {
            val name = "Lenscast_${System.currentTimeMillis()}.mp4"
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Lenscast")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver: ContentResolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return null
            val pfd = try {
                resolver.openFileDescriptor(uri, "w")
            } catch (t: Throwable) {
                Log.w(TAG, "openFileDescriptor failed: ${t.message}")
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                return null
            } ?: run {
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                return null
            }
            return try {
                RecordingMuxer(context, uri, pfd, expectAudio)
            } catch (t: Throwable) {
                Log.w(TAG, "MediaMuxer construction failed: ${t.message}")
                try { pfd.close() } catch (_: Throwable) {}
                try { resolver.delete(uri, null, null) } catch (_: Throwable) {}
                null
            }
        }
    }
}
