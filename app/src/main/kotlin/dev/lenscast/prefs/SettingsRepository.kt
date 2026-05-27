package dev.lenscast.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
    }
}
