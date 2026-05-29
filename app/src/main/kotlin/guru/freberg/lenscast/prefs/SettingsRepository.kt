package guru.freberg.lenscast.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lenscast_settings")

class SettingsRepository(private val context: Context) {

    val flow: Flow<Settings> = context.dataStore.data.map { readSettings(it) }

    /** Replace the whole settings blob in one transaction (used by import). */
    suspend fun replace(settings: Settings) {
        update { settings }
    }

    suspend fun update(transform: (Settings) -> Settings) {
        context.dataStore.edit { prefs ->
            val next = transform(readSettings(prefs))
            prefs[K_PROTOCOL] = next.protocol.ordinal
            prefs[K_RESOLUTION] = next.resolution.ordinal
            prefs[K_FPS] = next.fps.value
            prefs[K_QUALITY] = next.jpegQuality.coerceIn(10, 100)
            prefs[K_LENS] = next.lens.ordinal
            prefs[K_AUDIO] = next.audioEnabled
            prefs[K_KEEP_ON] = next.keepScreenOn
            prefs[K_MJPEG_PORT] = next.mjpegPort.coerceIn(1024, 65535)
            prefs[K_RTSP_PORT] = next.rtspPort.coerceIn(1024, 65535)
            prefs[K_MIRROR] = next.mirror
            prefs[K_CONT_AF] = next.continuousAf
            prefs[K_EV] = next.exposureEv
            prefs[K_WB] = next.whiteBalance.ordinal
            prefs[K_AB] = next.antiBanding.ordinal
            prefs[K_USERNAME] = next.streamUsername.ifBlank { "Lenscast" }
            prefs[K_PASSWORD] = next.streamPassword
            prefs[K_AUTO_START] = next.autoStart
            prefs[K_RTSP_BITRATE] = next.rtspBitrateKbps.coerceIn(0, 50_000)
            prefs[K_BLANK_PREVIEW] = next.blankPreview
            prefs[K_ROTATION_LOCK] = next.rotationLock.ordinal
            prefs[K_EFFECT] = next.effect.ordinal
            prefs[K_SCENE] = next.sceneMode.ordinal
            prefs[K_MANUAL_FOCUS] = next.manualFocus
            prefs[K_FOCUS_CD] = next.manualFocusCentidiopters
            prefs[K_BOOT] = next.startOnBoot
            prefs[K_MIC_SOURCE] = next.micSource.ordinal
            prefs[K_AUDIO_GAIN] = next.audioGainDb.coerceIn(-24, 24)
            prefs[K_NS] = next.noiseSuppress
            prefs[K_AEC] = next.echoCancel
            prefs[K_PRESETS] = encodePresets(next.presets)
            prefs[K_RECORD] = next.recordLocally
            prefs[K_WC_ENABLED] = next.webControlEnabled
            prefs[K_WC_PORT] = next.webControlPort.coerceIn(1024, 65535)
            prefs[K_WC_PERSIST] = next.persistentWebControl
            prefs[K_MANUAL_EXP] = next.manualExposure
            prefs[K_ISO] = next.iso
            prefs[K_SHUTTER] = next.shutterUs
            prefs[K_HTTPS] = next.httpsEnabled
            prefs[K_CALL_BEHAVIOR] = next.callBehavior.ordinal
            prefs[K_WATERMARK] = next.watermarkText.take(120)
            prefs[K_SFTP_ENABLED] = next.sftpEnabled
            prefs[K_SFTP_HOST] = next.sftpHost.trim().take(255)
            prefs[K_SFTP_PORT] = next.sftpPort.coerceIn(1, 65535)
            prefs[K_SFTP_USER] = next.sftpUser.trim().take(120)
            prefs[K_SFTP_PASSWORD] = next.sftpPassword
            prefs[K_SFTP_DIR] = next.sftpRemoteDir.trim().take(255)
            prefs[K_SFTP_FP] = next.sftpHostKeyFingerprint.trim().take(120)
            prefs[K_LANG] = next.languageTag.trim().take(16)
            prefs[K_MJPEG_SIDECAR] = next.mjpegSidecar
            prefs[K_SRT_MODE] = next.srtMode.ordinal
            prefs[K_SRT_HOST] = next.srtHost.trim().take(255)
            prefs[K_SRT_PORT] = next.srtPort.coerceIn(1, 65535)
            prefs[K_SRT_PASS] = next.srtPassphrase.take(79)
            prefs[K_SRT_LATENCY] = next.srtLatencyMs.coerceIn(20, 8000)
            prefs[K_SRT_STREAMID] = next.srtStreamId.trim().take(512)
            prefs[K_RIST_MODE] = next.ristMode.ordinal
            prefs[K_RIST_HOST] = next.ristHost.trim().take(255)
            prefs[K_RIST_PORT] = next.ristPort.coerceIn(1, 65534)
            prefs[K_RIST_PROFILE] = next.ristProfile.ordinal
            prefs[K_RIST_PASS] = next.ristEncryptionPassphrase.take(79)
            prefs[K_RIST_BUFFER] = next.ristBufferMs.coerceIn(20, 8000)
            prefs[K_RIST_AES_BITS] = if (next.ristAesKeyBits == 256) 256 else 128
        }
    }

    private fun readSettings(p: Preferences): Settings = Settings(
        protocol = Protocol.entries.getOrNull(p[K_PROTOCOL] ?: 0) ?: Protocol.MJPEG,
        resolution = Resolution.fromOrdinal(p[K_RESOLUTION] ?: Resolution.P720.ordinal),
        fps = Fps.fromValue(p[K_FPS] ?: Fps.FPS30.value),
        jpegQuality = (p[K_QUALITY] ?: 80).coerceIn(10, 100),
        lens = Lens.entries.getOrNull(p[K_LENS] ?: 0) ?: Lens.BACK,
        audioEnabled = p[K_AUDIO] ?: false,
        keepScreenOn = p[K_KEEP_ON] ?: true,
        mjpegPort = (p[K_MJPEG_PORT] ?: 4747).coerceIn(1024, 65535),
        rtspPort = (p[K_RTSP_PORT] ?: 5540).coerceIn(1024, 65535),
        mirror = p[K_MIRROR] ?: false,
        continuousAf = p[K_CONT_AF] ?: true,
        exposureEv = p[K_EV] ?: 0,
        whiteBalance = WhiteBalance.entries.getOrNull(p[K_WB] ?: 0) ?: WhiteBalance.AUTO,
        antiBanding = AntiBanding.entries.getOrNull(p[K_AB] ?: 0) ?: AntiBanding.AUTO,
        streamUsername = (p[K_USERNAME] ?: "Lenscast").ifBlank { "Lenscast" },
        streamPassword = p[K_PASSWORD] ?: "",
        autoStart = p[K_AUTO_START] ?: false,
        rtspBitrateKbps = (p[K_RTSP_BITRATE] ?: 0).coerceIn(0, 50_000),
        blankPreview = p[K_BLANK_PREVIEW] ?: false,
        rotationLock = RotationLock.entries.getOrNull(p[K_ROTATION_LOCK] ?: 0) ?: RotationLock.AUTO,
        effect = CameraEffect.entries.getOrNull(p[K_EFFECT] ?: 0) ?: CameraEffect.NONE,
        sceneMode = SceneMode.entries.getOrNull(p[K_SCENE] ?: 0) ?: SceneMode.DISABLED,
        manualFocus = p[K_MANUAL_FOCUS] ?: false,
        manualFocusCentidiopters = p[K_FOCUS_CD] ?: 0,
        startOnBoot = p[K_BOOT] ?: false,
        micSource = MicSource.entries.getOrNull(p[K_MIC_SOURCE] ?: MicSource.VOICE_RECOGNITION.ordinal)
            ?: MicSource.VOICE_RECOGNITION,
        // Absent-key default must mirror Settings.audioGainDb (+12 dB) — this is the value a
        // fresh install / cleared DataStore actually gets, the data-class default never applies here.
        audioGainDb = (p[K_AUDIO_GAIN] ?: 12).coerceIn(-24, 24),
        noiseSuppress = p[K_NS] ?: false,
        echoCancel = p[K_AEC] ?: false,
        presets = decodePresets(p[K_PRESETS] ?: ""),
        recordLocally = p[K_RECORD] ?: false,
        webControlEnabled = p[K_WC_ENABLED] ?: true,
        webControlPort = (p[K_WC_PORT] ?: 8080).coerceIn(1024, 65535),
        persistentWebControl = p[K_WC_PERSIST] ?: false,
        manualExposure = p[K_MANUAL_EXP] ?: false,
        iso = p[K_ISO] ?: 100,
        shutterUs = p[K_SHUTTER] ?: 16_666L,
        httpsEnabled = p[K_HTTPS] ?: false,
        callBehavior = CallBehavior.entries.getOrNull(p[K_CALL_BEHAVIOR] ?: 0) ?: CallBehavior.IGNORE,
        watermarkText = (p[K_WATERMARK] ?: "").take(120),
        sftpEnabled = p[K_SFTP_ENABLED] ?: false,
        sftpHost = (p[K_SFTP_HOST] ?: "").trim(),
        sftpPort = (p[K_SFTP_PORT] ?: 22).coerceIn(1, 65535),
        sftpUser = (p[K_SFTP_USER] ?: "").trim(),
        sftpPassword = p[K_SFTP_PASSWORD] ?: "",
        sftpRemoteDir = (p[K_SFTP_DIR] ?: "").trim(),
        sftpHostKeyFingerprint = (p[K_SFTP_FP] ?: "").trim(),
        languageTag = (p[K_LANG] ?: "").trim(),
        mjpegSidecar = p[K_MJPEG_SIDECAR] ?: false,
        srtMode = SrtMode.entries.getOrNull(p[K_SRT_MODE] ?: SrtMode.LISTENER.ordinal) ?: SrtMode.LISTENER,
        srtHost = (p[K_SRT_HOST] ?: "").trim(),
        srtPort = (p[K_SRT_PORT] ?: 9710).coerceIn(1, 65535),
        srtPassphrase = p[K_SRT_PASS] ?: "",
        srtLatencyMs = (p[K_SRT_LATENCY] ?: 200).coerceIn(20, 8000),
        srtStreamId = (p[K_SRT_STREAMID] ?: "").trim(),
        ristMode = RistMode.entries.getOrNull(p[K_RIST_MODE] ?: RistMode.LISTENER.ordinal) ?: RistMode.LISTENER,
        ristHost = (p[K_RIST_HOST] ?: "").trim(),
        ristPort = (p[K_RIST_PORT] ?: 5004).coerceIn(1, 65534),
        ristProfile = RistProfile.entries.getOrNull(p[K_RIST_PROFILE] ?: 0) ?: RistProfile.SIMPLE,
        ristEncryptionPassphrase = p[K_RIST_PASS] ?: "",
        ristBufferMs = (p[K_RIST_BUFFER] ?: 200).coerceIn(20, 8000),
        ristAesKeyBits = if ((p[K_RIST_AES_BITS] ?: 128) == 256) 256 else 128,
    )

    /**
     * Newline-separated, pipe-delimited: `name|protocolOrdinal|resOrdinal|fpsValue|lensOrdinal`.
     * Names with `|` or newline are sanitised at save time so the format stays unambiguous.
     */
    private fun encodePresets(presets: List<Preset>): String = presets.joinToString("\n") { p ->
        val safeName = p.name.replace('|', '/').replace('\n', ' ').take(40)
        "$safeName|${p.protocol.ordinal}|${p.resolution.ordinal}|${p.fps.value}|${p.lens.ordinal}"
    }

    private fun decodePresets(raw: String): List<Preset> {
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val parts = line.split('|')
            if (parts.size != 5) return@mapNotNull null
            try {
                Preset(
                    name = parts[0],
                    protocol = Protocol.entries.getOrNull(parts[1].toInt()) ?: return@mapNotNull null,
                    resolution = Resolution.fromOrdinal(parts[2].toInt()),
                    fps = Fps.fromValue(parts[3].toInt()),
                    lens = Lens.entries.getOrNull(parts[4].toInt()) ?: return@mapNotNull null,
                )
            } catch (_: Throwable) { null }
        }
    }

    private companion object {
        val K_PROTOCOL = intPreferencesKey("protocol")
        val K_RESOLUTION = intPreferencesKey("resolution")
        val K_FPS = intPreferencesKey("fps")
        val K_QUALITY = intPreferencesKey("quality")
        val K_LENS = intPreferencesKey("lens")
        val K_AUDIO = booleanPreferencesKey("audio")
        val K_KEEP_ON = booleanPreferencesKey("keep_on")
        val K_MJPEG_PORT = intPreferencesKey("mjpeg_port")
        val K_RTSP_PORT = intPreferencesKey("rtsp_port")
        val K_MIRROR = booleanPreferencesKey("mirror")
        val K_CONT_AF = booleanPreferencesKey("cont_af")
        val K_EV = intPreferencesKey("ev")
        val K_WB = intPreferencesKey("wb")
        val K_AB = intPreferencesKey("ab")
        val K_USERNAME = stringPreferencesKey("username")
        val K_PASSWORD = stringPreferencesKey("password")
        val K_AUTO_START = booleanPreferencesKey("auto_start")
        val K_RTSP_BITRATE = intPreferencesKey("rtsp_bitrate")
        val K_BLANK_PREVIEW = booleanPreferencesKey("blank_preview")
        val K_ROTATION_LOCK = intPreferencesKey("rotation_lock")
        val K_EFFECT = intPreferencesKey("effect")
        val K_SCENE = intPreferencesKey("scene")
        val K_MANUAL_FOCUS = booleanPreferencesKey("manual_focus")
        val K_FOCUS_CD = intPreferencesKey("focus_cd")
        val K_BOOT = booleanPreferencesKey("start_on_boot")
        val K_MIC_SOURCE = intPreferencesKey("mic_source")
        val K_AUDIO_GAIN = intPreferencesKey("audio_gain")
        val K_NS = booleanPreferencesKey("ns")
        val K_AEC = booleanPreferencesKey("aec")
        val K_PRESETS = stringPreferencesKey("presets")
        val K_RECORD = booleanPreferencesKey("record_locally")
        val K_WC_ENABLED = booleanPreferencesKey("web_control_enabled")
        val K_WC_PORT = intPreferencesKey("web_control_port")
        val K_WC_PERSIST = booleanPreferencesKey("web_control_persist")
        val K_MANUAL_EXP = booleanPreferencesKey("manual_exposure")
        val K_ISO = intPreferencesKey("iso")
        val K_SHUTTER = longPreferencesKey("shutter_us")
        val K_HTTPS = booleanPreferencesKey("https_enabled")
        val K_CALL_BEHAVIOR = intPreferencesKey("call_behavior")
        val K_WATERMARK = stringPreferencesKey("watermark_text")
        val K_SFTP_ENABLED = booleanPreferencesKey("sftp_enabled")
        val K_SFTP_HOST = stringPreferencesKey("sftp_host")
        val K_SFTP_PORT = intPreferencesKey("sftp_port")
        val K_SFTP_USER = stringPreferencesKey("sftp_user")
        val K_SFTP_PASSWORD = stringPreferencesKey("sftp_password")
        val K_SFTP_DIR = stringPreferencesKey("sftp_dir")
        val K_SFTP_FP = stringPreferencesKey("sftp_host_fingerprint")
        val K_LANG = stringPreferencesKey("language_tag")
        val K_MJPEG_SIDECAR = booleanPreferencesKey("mjpeg_sidecar")
        val K_SRT_MODE = intPreferencesKey("srt_mode")
        val K_SRT_HOST = stringPreferencesKey("srt_host")
        val K_SRT_PORT = intPreferencesKey("srt_port")
        val K_SRT_PASS = stringPreferencesKey("srt_passphrase")
        val K_SRT_LATENCY = intPreferencesKey("srt_latency_ms")
        val K_SRT_STREAMID = stringPreferencesKey("srt_streamid")
        val K_RIST_MODE = intPreferencesKey("rist_mode")
        val K_RIST_HOST = stringPreferencesKey("rist_host")
        val K_RIST_PORT = intPreferencesKey("rist_port")
        val K_RIST_PROFILE = intPreferencesKey("rist_profile")
        val K_RIST_PASS = stringPreferencesKey("rist_passphrase")
        val K_RIST_BUFFER = intPreferencesKey("rist_buffer_ms")
        val K_RIST_AES_BITS = intPreferencesKey("rist_aes_bits")
    }
}
