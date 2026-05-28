package dev.lenscast.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lenscast_settings")

class SettingsRepository(private val context: Context) {

    val flow: Flow<Settings> = context.dataStore.data.map { readSettings(it) }

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
            prefs[K_PASSWORD] = next.streamPassword
            prefs[K_AUTO_START] = next.autoStart
            prefs[K_RTSP_BITRATE] = next.rtspBitrateKbps.coerceIn(0, 50_000)
            prefs[K_BLANK_PREVIEW] = next.blankPreview
            prefs[K_ROTATION_LOCK] = next.rotationLock.ordinal
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
        streamPassword = p[K_PASSWORD] ?: "",
        autoStart = p[K_AUTO_START] ?: false,
        rtspBitrateKbps = (p[K_RTSP_BITRATE] ?: 0).coerceIn(0, 50_000),
        blankPreview = p[K_BLANK_PREVIEW] ?: false,
        rotationLock = RotationLock.entries.getOrNull(p[K_ROTATION_LOCK] ?: 0) ?: RotationLock.AUTO,
    )

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
        val K_PASSWORD = stringPreferencesKey("password")
        val K_AUTO_START = booleanPreferencesKey("auto_start")
        val K_RTSP_BITRATE = intPreferencesKey("rtsp_bitrate")
        val K_BLANK_PREVIEW = booleanPreferencesKey("blank_preview")
        val K_ROTATION_LOCK = intPreferencesKey("rotation_lock")
    }
}
