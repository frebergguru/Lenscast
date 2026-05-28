package guru.freberg.lenscast.streaming.srt

/**
 * Minimal MPEG-TS muxer for H.264 video + AAC (ADTS) audio. Emits a continuous stream of
 * 188-byte transport-stream packets via the [sink] callback. Designed for live streaming:
 * no seek points beyond keyframes, PCR-only-on-video, PAT/PMT every 50 packets.
 *
 * **Scope** intentionally tiny — we don't handle:
 *  - Multiple programs (we always emit `program 1` on `PMT_PID=4096`).
 *  - SCTE-35 / closed captions / private data.
 *  - PAT/PMT version bumping (constant version=0).
 *  - PTS rollover (33-bit timestamp wraps at ~26 h — fine for a streaming session).
 *
 * Packet layout (per ISO 13818-1):
 * ```
 *  Byte 0     : sync (0x47)
 *  Bytes 1-2  : tei(1) | pusi(1) | priority(1) | PID(13)
 *  Byte 3     : tsc(2) | adapt(2) | cc(4)
 *  Bytes 4..  : (optional) adaptation field, then payload
 * ```
 */
class MpegTsMuxer(
    private val sink: (packet: ByteArray) -> Unit,
) {
    /**
     * Called by SrtManager when a new receiver connects. Re-arms the IDR gate (so the
     * next emitted PES is guaranteed to be a keyframe with SPS+PPS+IDR) and forces PSI
     * on the next access unit. Without this the receiver could join mid-stream and see
     * a P-frame as its first PES, with the previous IDR already drained off the queue.
     */
    fun resetForNewClient() {
        sentFirstVideoKeyframe = false
        packetCountSincePsi = 0
    }

    /** Send the encoder-emitted SPS bytes — required for the first PES on the video PID. */
    @Volatile var sps: ByteArray? = null
    /** Send the encoder-emitted PPS bytes — required for the first PES on the video PID. */
    @Volatile var pps: ByteArray? = null

    /** AAC AudioSpecificConfig (2 bytes for AAC-LC) — used to build ADTS headers. */
    @Volatile var asc: ByteArray? = null
    @Volatile var audioSampleRate: Int = 44_100
    @Volatile var audioChannels: Int = 1

    private val pcrPid: Int = VIDEO_PID
    private var videoCc: Int = 0
    private var audioCc: Int = 0
    private var patCc: Int = 0
    private var pmtCc: Int = 0
    private var packetCountSincePsi: Int = 0
    private var sentFirstVideoKeyframe: Boolean = false

    /** One H.264 access unit (1+ NAL units in Annex-B / length-prefix form). */
    fun writeVideoAu(nals: List<ByteArray>, ptsUs: Long, isKeyframe: Boolean) {
        if (!sentFirstVideoKeyframe) {
            // Wait for an IDR before letting receivers start — without it the decoder
            // will hold every packet trying to find a sync point.
            if (!isKeyframe) return
            sentFirstVideoKeyframe = true
        }
        maybeWritePsi()
        // Convert NALs to Annex-B (each preceded by 0x00 0x00 0x00 0x01). On keyframes,
        // prepend SPS+PPS so receivers that joined mid-stream can sync without waiting
        // for the next IDR. AUD NAL prepending was tried here but both ffplay and VLC
        // started rejecting the stream — likely because we emit one PES per NAL and the
        // demuxer counted each AUD as a new access unit, not as a delimiter inside the
        // current one. Modern Android H.264 encoders emit single-slice frames, so AUD
        // isn't required for correct demuxing.
        val annexB = mutableListOf<ByteArray>()
        if (isKeyframe) {
            sps?.let { annexB += it }
            pps?.let { annexB += it }
        }
        annexB += nals
        val payload = annexBPayload(annexB)
        // Convert PTS µs → 90 kHz.
        val pts90k = (ptsUs * 90L) / 1_000L
        val dts = pts90k  // No B-frames in our encoder, DTS == PTS.
        val pes = buildPesPacket(streamId = STREAM_ID_VIDEO, pts90k = pts90k, dts = dts, payload = payload)
        emitPes(VIDEO_PID, pes, withPcr = true, pcr27mHz = pts90k * 300L)
    }

    /** One AAC access unit (raw frame body — we add the ADTS header). */
    fun writeAudioAu(aac: ByteArray, ptsUs: Long) {
        if (!sentFirstVideoKeyframe) return // align with video so receivers don't desync.
        val cfg = asc ?: return
        maybeWritePsi()
        val adts = buildAdtsHeader(cfg, aac.size, audioSampleRate, audioChannels)
        val payload = adts + aac
        val pts90k = (ptsUs * 90L) / 1_000L
        val pes = buildPesPacket(streamId = STREAM_ID_AUDIO, pts90k = pts90k, dts = pts90k, payload = payload)
        emitPes(AUDIO_PID, pes, withPcr = false, pcr27mHz = 0L)
    }

    private fun maybeWritePsi() {
        // `<= 0`, not `== 0`: a single 4 K keyframe burns ~500 TS packets, taking the
        // counter well into negative territory. Comparing for equality meant we sent PSI
        // exactly once at start and never again, leaving any receiver that joined after
        // the initial PAT/PMT permanently without tables and triggering an endless
        // "Packet corrupt"/"non-existing PPS 0 referenced" stream on the receiver.
        if (packetCountSincePsi <= 0) {
            sink(buildPat())
            sink(buildPmt())
            packetCountSincePsi = PSI_INTERVAL_PACKETS
        }
    }

    private fun emitPes(pid: Int, pes: ByteArray, withPcr: Boolean, pcr27mHz: Long) {
        var offset = 0
        var first = true
        while (offset < pes.size) {
            val pkt = ByteArray(188)
            pkt[0] = 0x47
            // Bytes 1-2: TEI=0, PUSI=first, priority=0, PID=13 bits
            pkt[1] = (((if (first) 0x40 else 0) or ((pid ushr 8) and 0x1F))).toByte()
            pkt[2] = (pid and 0xFF).toByte()
            val cc = if (pid == VIDEO_PID) {
                videoCc = (videoCc + 1) and 0x0F; videoCc
            } else {
                audioCc = (audioCc + 1) and 0x0F; audioCc
            }
            // Payload area starts at 4 (no adaptation field) or after the AF if present.
            val remaining = pes.size - offset
            // Adaptation field needed if (a) we need PCR (only on the first packet of an
            // access unit on the PCR PID) or (b) we need to stuff a short last payload up
            // to the full 184-byte slot.
            val needPcr = withPcr && first && pid == pcrPid
            val payloadCapacityIfNoAf = 184
            val needStuffing = remaining < payloadCapacityIfNoAf
            val hasAf = needPcr || needStuffing
            // 4-bit "adaptation_field_control": 01 = payload only, 11 = AF + payload.
            pkt[3] = (((if (hasAf) 0x30 else 0x10) or (cc and 0x0F))).toByte()

            val payloadStart: Int
            if (hasAf) {
                // afLength byte at offset 4, then afLength bytes of AF data.
                val afData = ByteArray(if (needPcr) 7 else 0)
                if (needPcr) {
                    afData[0] = 0x10  // PCR flag
                    // PCR base = pcr27mHz / 300 (90 kHz), PCR extension = pcr27mHz % 300.
                    val pcrBase = pcr27mHz / 300L
                    val pcrExt = (pcr27mHz % 300L).toInt() and 0x1FF
                    afData[1] = ((pcrBase ushr 25) and 0xFF).toByte()
                    afData[2] = ((pcrBase ushr 17) and 0xFF).toByte()
                    afData[3] = ((pcrBase ushr 9) and 0xFF).toByte()
                    afData[4] = ((pcrBase ushr 1) and 0xFF).toByte()
                    afData[5] = ((((pcrBase and 0x1L) shl 7) or 0x7E or ((pcrExt ushr 8) and 0x1).toLong()) and 0xFF).toByte()
                    afData[6] = (pcrExt and 0xFF).toByte()
                }
                val afContentLen = afData.size
                val payloadCapacity = 184 - 1 - afContentLen // 1 byte for afLength itself
                val payloadBytes = minOf(remaining, payloadCapacity)
                val stuffingBytes = if (needStuffing) (payloadCapacity - payloadBytes) else 0
                val afLength = afContentLen + stuffingBytes
                pkt[4] = afLength.toByte()
                if (afContentLen > 0) {
                    // Flag byte at offset 5 is the same as afData[0]; copy the entire AF
                    // content starting at 5. The rest of the AF (stuffing) is zeroed.
                    System.arraycopy(afData, 0, pkt, 5, afContentLen)
                }
                if (stuffingBytes > 0) {
                    // Stuffing bytes after the AF content area = 0xFF per spec.
                    java.util.Arrays.fill(pkt, 5 + afContentLen, 5 + afContentLen + stuffingBytes, 0xFF.toByte())
                }
                payloadStart = 4 + 1 + afLength
            } else {
                payloadStart = 4
            }

            val capacity = 188 - payloadStart
            val toCopy = minOf(remaining, capacity)
            System.arraycopy(pes, offset, pkt, payloadStart, toCopy)
            offset += toCopy
            first = false
            sink(pkt)
            packetCountSincePsi--
        }
    }

    private fun annexBPayload(nals: List<ByteArray>): ByteArray {
        var total = 0
        for (n in nals) total += 4 + n.size
        // Prepend a leading AUD NAL would be cleaner; receivers handle either.
        val out = ByteArray(total)
        var p = 0
        for (n in nals) {
            out[p] = 0; out[p + 1] = 0; out[p + 2] = 0; out[p + 3] = 1
            System.arraycopy(n, 0, out, p + 4, n.size); p += 4 + n.size
        }
        return out
    }

    /**
     * PES wrap: 6-byte header + 3-byte extension flags + 5/10 bytes of PTS [+DTS] + payload.
     * For video we set PES packet length = 0 (unbounded), for audio we set the real length.
     */
    private fun buildPesPacket(streamId: Int, pts90k: Long, dts: Long, payload: ByteArray): ByteArray {
        val hasDts = dts != pts90k
        val ptsDtsLen = if (hasDts) 10 else 5
        val flags = if (hasDts) 0xC0 else 0x80
        val unbounded = streamId == STREAM_ID_VIDEO
        val pesPacketLength = if (unbounded) 0 else (3 + ptsDtsLen + payload.size)
        val header = ByteArray(9 + ptsDtsLen)
        header[0] = 0; header[1] = 0; header[2] = 1; header[3] = streamId.toByte()
        header[4] = ((pesPacketLength ushr 8) and 0xFF).toByte()
        header[5] = (pesPacketLength and 0xFF).toByte()
        header[6] = 0x80.toByte() // marker bits
        header[7] = flags.toByte()
        header[8] = ptsDtsLen.toByte()
        writePts(header, 9, prefixNibble = if (hasDts) 0x3 else 0x2, pts = pts90k)
        if (hasDts) writePts(header, 14, prefixNibble = 0x1, pts = dts)
        return header + payload
    }

    /** ISO 13818-1 §2.4.3.6 PTS / DTS encoding — 5 bytes carrying 33 bits + marker bits. */
    private fun writePts(buf: ByteArray, offset: Int, prefixNibble: Int, pts: Long) {
        buf[offset] = ((prefixNibble shl 4) or (((pts ushr 30) and 0x7L).toInt() shl 1) or 1).toByte()
        buf[offset + 1] = ((pts ushr 22) and 0xFFL).toByte()
        buf[offset + 2] = ((((pts ushr 15) and 0x7FL).toInt() shl 1) or 1).toByte()
        buf[offset + 3] = ((pts ushr 7) and 0xFFL).toByte()
        buf[offset + 4] = ((((pts and 0x7FL).toInt() shl 1)) or 1).toByte()
    }

    private fun buildAdtsHeader(asc: ByteArray, payloadSize: Int, sr: Int, ch: Int): ByteArray {
        // AAC profile: ASC bits 0-4 = audioObjectType. For AAC-LC = 2.
        val profile = ((asc[0].toInt() and 0xFF) ushr 3) and 0x1F
        val srIdx = sampleRateIdxFor(sr)
        val total = 7 + payloadSize
        val h = ByteArray(7)
        h[0] = 0xFF.toByte()
        h[1] = 0xF1.toByte() // MPEG-4, no CRC
        h[2] = ((((profile - 1) and 0x3) shl 6) or ((srIdx and 0xF) shl 2) or ((ch ushr 2) and 0x1)).toByte()
        h[3] = ((((ch and 0x3) shl 6) or ((total ushr 11) and 0x3)).toByte())
        h[4] = ((total ushr 3) and 0xFF).toByte()
        h[5] = ((((total and 0x7) shl 5) or 0x1F)).toByte()
        h[6] = 0xFC.toByte()
        return h
    }

    private fun sampleRateIdxFor(sr: Int): Int = when (sr) {
        96000 -> 0; 88200 -> 1; 64000 -> 2; 48000 -> 3; 44100 -> 4
        32000 -> 5; 24000 -> 6; 22050 -> 7; 16000 -> 8; 12000 -> 9
        11025 -> 10; 8000 -> 11; 7350 -> 12; else -> 4
    }

    /**
     * Program Association Table — one entry: program 1 → PMT on PID 4096. Sent in a single
     * 188-byte packet with a fixed CRC32.
     */
    private fun buildPat(): ByteArray {
        val pkt = ByteArray(188)
        pkt[0] = 0x47; pkt[1] = 0x40; pkt[2] = 0x00
        patCc = (patCc + 1) and 0x0F
        pkt[3] = (0x10 or patCc).toByte()
        pkt[4] = 0x00 // pointer field
        // section start at offset 5:
        val section = byteArrayOf(
            0x00,                                // table_id=0 (PAT)
            0xB0.toByte(), 0x0D,                 // section_syntax_indicator=1 + length=13
            0x00, 0x01,                          // transport_stream_id=1
            0xC1.toByte(),                       // version=0, current_next=1
            0x00, 0x00,                          // section_number, last_section_number
            0x00, 0x01,                          // program_number=1
            ((0xE0 or ((PMT_PID ushr 8) and 0x1F))).toByte(), (PMT_PID and 0xFF).toByte(),
        )
        val crc = crc32Mpeg(section)
        System.arraycopy(section, 0, pkt, 5, section.size)
        pkt[5 + section.size] = ((crc ushr 24) and 0xFF).toByte()
        pkt[6 + section.size] = ((crc ushr 16) and 0xFF).toByte()
        pkt[7 + section.size] = ((crc ushr 8) and 0xFF).toByte()
        pkt[8 + section.size] = (crc and 0xFF).toByte()
        java.util.Arrays.fill(pkt, 9 + section.size, 188, 0xFF.toByte())
        return pkt
    }

    /**
     * Program Map Table — program 1 with two streams: H.264 video on `VIDEO_PID` (type 0x1B)
     * and AAC audio on `AUDIO_PID` (type 0x0F).
     */
    private fun buildPmt(): ByteArray {
        val pkt = ByteArray(188)
        pkt[0] = 0x47
        pkt[1] = ((0x40 or ((PMT_PID ushr 8) and 0x1F))).toByte()
        pkt[2] = (PMT_PID and 0xFF).toByte()
        pmtCc = (pmtCc + 1) and 0x0F
        pkt[3] = (0x10 or pmtCc).toByte()
        pkt[4] = 0x00
        val streams = byteArrayOf(
            0x1B,                                            // stream_type = H.264
            ((0xE0 or ((VIDEO_PID ushr 8) and 0x1F))).toByte(), (VIDEO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,                             // ES_info_length=0
            0x0F,                                            // stream_type = AAC ADTS
            ((0xE0 or ((AUDIO_PID ushr 8) and 0x1F))).toByte(), (AUDIO_PID and 0xFF).toByte(),
            0xF0.toByte(), 0x00,
        )
        val sectionLength = 13 + streams.size
        val sectionBody = byteArrayOf(
            0x02,                                            // table_id
            ((0xB0 or ((sectionLength ushr 8) and 0xF))).toByte(),
            (sectionLength and 0xFF).toByte(),
            0x00, 0x01,                                      // program_number=1
            0xC1.toByte(),                                   // version=0
            0x00, 0x00,
            ((0xE0 or ((pcrPid ushr 8) and 0x1F))).toByte(), (pcrPid and 0xFF).toByte(),
            0xF0.toByte(), 0x00,                             // program_info_length=0
        ) + streams
        val crc = crc32Mpeg(sectionBody)
        System.arraycopy(sectionBody, 0, pkt, 5, sectionBody.size)
        pkt[5 + sectionBody.size] = ((crc ushr 24) and 0xFF).toByte()
        pkt[6 + sectionBody.size] = ((crc ushr 16) and 0xFF).toByte()
        pkt[7 + sectionBody.size] = ((crc ushr 8) and 0xFF).toByte()
        pkt[8 + sectionBody.size] = (crc and 0xFF).toByte()
        java.util.Arrays.fill(pkt, 9 + sectionBody.size, 188, 0xFF.toByte())
        return pkt
    }

    /**
     * MPEG-2 CRC-32 with polynomial 0x04C11DB7, init 0xFFFFFFFF, no reflection. Hand-rolled
     * because java.util.zip.CRC32 uses the IEEE 802.3 reflected polynomial which doesn't
     * match the values the spec expects.
     */
    private fun crc32Mpeg(data: ByteArray): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (b in data) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            for (i in 0 until 8) {
                crc = if (crc and 0x80000000.toInt() != 0) (crc shl 1) xor 0x04C11DB7 else (crc shl 1)
            }
        }
        return crc
    }

    companion object {
        private const val VIDEO_PID = 256
        private const val AUDIO_PID = 257
        private const val PMT_PID = 4096
        private const val STREAM_ID_VIDEO = 0xE0
        private const val STREAM_ID_AUDIO = 0xC0
        private const val PSI_INTERVAL_PACKETS = 50 // Send PAT+PMT roughly every 50 TS packets.

        /**
         * Access Unit Delimiter NAL: nal_unit_type=9, primary_pic_type=0xF0 (all types).
         * Two bytes — 0x09 (forbidden=0, nri=0, type=9) + 0xF0. Telling the decoder
         * "this is a new access unit" without forcing it to sniff slice_header bits.
         */
        private val AUD_NAL = byteArrayOf(0x09, 0xF0.toByte())
    }
}
