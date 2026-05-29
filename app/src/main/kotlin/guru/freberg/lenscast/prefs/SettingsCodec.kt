package guru.freberg.lenscast.prefs

import org.json.JSONArray
import org.json.JSONObject

/**
 * JSON serialiser for the full [Settings] data class. Used for export/import (Settings
 * sheet button on the phone, `/export` and `/import` endpoints on the web control
 * panel). Hand-rolled rather than reflective because the codebase already avoids the
 * kotlinx-serialization dependency and `org.json` is built into Android.
 *
 * Schema is versioned via the `version` field so we can grow the format without breaking
 * older exports. Reads tolerate missing fields (uses the [Settings] defaults).
 */
object SettingsCodec {
    private const val VERSION = 1

    fun toJson(s: Settings): String {
        val o = JSONObject().apply {
            put("version", VERSION)
            put("protocol", s.protocol.name)
            put("resolution", s.resolution.name)
            put("fps", s.fps.value)
            put("jpegQuality", s.jpegQuality)
            put("lens", s.lens.name)
            put("audioEnabled", s.audioEnabled)
            put("keepScreenOn", s.keepScreenOn)
            put("mjpegPort", s.mjpegPort)
            put("rtspPort", s.rtspPort)
            put("mirror", s.mirror)
            put("continuousAf", s.continuousAf)
            put("exposureEv", s.exposureEv)
            put("whiteBalance", s.whiteBalance.name)
            put("antiBanding", s.antiBanding.name)
            put("streamUsername", s.streamUsername)
            put("streamPassword", s.streamPassword)
            put("autoStart", s.autoStart)
            put("rtspBitrateKbps", s.rtspBitrateKbps)
            put("blankPreview", s.blankPreview)
            put("rotationLock", s.rotationLock.name)
            put("effect", s.effect.name)
            put("sceneMode", s.sceneMode.name)
            put("manualFocus", s.manualFocus)
            put("manualFocusCentidiopters", s.manualFocusCentidiopters)
            put("startOnBoot", s.startOnBoot)
            put("micSource", s.micSource.name)
            put("audioGainDb", s.audioGainDb)
            put("noiseSuppress", s.noiseSuppress)
            put("echoCancel", s.echoCancel)
            put("presets", JSONArray().apply {
                s.presets.forEach { p ->
                    put(JSONObject().apply {
                        put("name", p.name)
                        put("protocol", p.protocol.name)
                        put("resolution", p.resolution.name)
                        put("fps", p.fps.value)
                        put("lens", p.lens.name)
                    })
                }
            })
            put("recordLocally", s.recordLocally)
            put("webControlEnabled", s.webControlEnabled)
            put("webControlPort", s.webControlPort)
            put("manualExposure", s.manualExposure)
            put("iso", s.iso)
            put("shutterUs", s.shutterUs)
            put("httpsEnabled", s.httpsEnabled)
            put("callBehavior", s.callBehavior.name)
            put("persistentWebControl", s.persistentWebControl)
        }
        return o.toString(2)
    }

    /**
     * Parse a previously exported JSON blob into a [Settings] object. Returns null on
     * malformed JSON. Individual fields fall back to defaults — that's deliberate, so
     * older exports still work after [Settings] grows new fields.
     */
    fun fromJson(json: String): Settings? {
        val o = try { JSONObject(json) } catch (_: Throwable) { return null }
        val d = Settings() // defaults — used as fallback for any missing field
        return Settings(
            protocol = enumByName(o, "protocol", d.protocol),
            resolution = enumByName(o, "resolution", d.resolution),
            fps = Fps.fromValue(o.optInt("fps", d.fps.value)),
            jpegQuality = o.optInt("jpegQuality", d.jpegQuality).coerceIn(10, 100),
            lens = enumByName(o, "lens", d.lens),
            audioEnabled = o.optBoolean("audioEnabled", d.audioEnabled),
            keepScreenOn = o.optBoolean("keepScreenOn", d.keepScreenOn),
            mjpegPort = o.optInt("mjpegPort", d.mjpegPort).coerceIn(1024, 65535),
            rtspPort = o.optInt("rtspPort", d.rtspPort).coerceIn(1024, 65535),
            mirror = o.optBoolean("mirror", d.mirror),
            continuousAf = o.optBoolean("continuousAf", d.continuousAf),
            exposureEv = o.optInt("exposureEv", d.exposureEv),
            whiteBalance = enumByName(o, "whiteBalance", d.whiteBalance),
            antiBanding = enumByName(o, "antiBanding", d.antiBanding),
            streamUsername = o.optString("streamUsername", d.streamUsername).ifBlank { "Lenscast" },
            streamPassword = o.optString("streamPassword", d.streamPassword),
            autoStart = o.optBoolean("autoStart", d.autoStart),
            rtspBitrateKbps = o.optInt("rtspBitrateKbps", d.rtspBitrateKbps).coerceIn(0, 50_000),
            blankPreview = o.optBoolean("blankPreview", d.blankPreview),
            rotationLock = enumByName(o, "rotationLock", d.rotationLock),
            effect = enumByName(o, "effect", d.effect),
            sceneMode = enumByName(o, "sceneMode", d.sceneMode),
            manualFocus = o.optBoolean("manualFocus", d.manualFocus),
            manualFocusCentidiopters = o.optInt("manualFocusCentidiopters", d.manualFocusCentidiopters),
            startOnBoot = o.optBoolean("startOnBoot", d.startOnBoot),
            micSource = enumByName(o, "micSource", d.micSource),
            audioGainDb = o.optInt("audioGainDb", d.audioGainDb).coerceIn(-24, 24),
            noiseSuppress = o.optBoolean("noiseSuppress", d.noiseSuppress),
            echoCancel = o.optBoolean("echoCancel", d.echoCancel),
            presets = parsePresets(o.optJSONArray("presets")),
            recordLocally = o.optBoolean("recordLocally", d.recordLocally),
            webControlEnabled = o.optBoolean("webControlEnabled", d.webControlEnabled),
            webControlPort = o.optInt("webControlPort", d.webControlPort).coerceIn(1024, 65535),
            manualExposure = o.optBoolean("manualExposure", d.manualExposure),
            iso = o.optInt("iso", d.iso),
            shutterUs = o.optLong("shutterUs", d.shutterUs),
            httpsEnabled = o.optBoolean("httpsEnabled", d.httpsEnabled),
            callBehavior = enumByName(o, "callBehavior", d.callBehavior),
            persistentWebControl = o.optBoolean("persistentWebControl", d.persistentWebControl),
        )
    }

    private inline fun <reified T : Enum<T>> enumByName(o: JSONObject, key: String, fallback: T): T =
        enumValueOrNull<T>(o.optString(key, "")) ?: fallback

    private fun parsePresets(arr: JSONArray?): List<Preset> {
        if (arr == null) return emptyList()
        val out = mutableListOf<Preset>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            val name = p.optString("name", "").takeIf { it.isNotBlank() } ?: continue
            val protocol = enumValueOrNull<Protocol>(p.optString("protocol")) ?: continue
            val resolution = enumValueOrNull<Resolution>(p.optString("resolution")) ?: continue
            val fps = Fps.fromValue(p.optInt("fps", Fps.FPS30.value))
            val lens = enumValueOrNull<Lens>(p.optString("lens")) ?: Lens.BACK
            out += Preset(name, protocol, resolution, fps, lens)
        }
        return out
    }
}
