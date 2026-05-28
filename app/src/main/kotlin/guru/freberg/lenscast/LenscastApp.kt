package guru.freberg.lenscast

import android.app.Application
import android.app.LocaleManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import guru.freberg.lenscast.prefs.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.Locale

class LenscastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        guru.freberg.lenscast.system.CrashReporter.install(this)
        // Apply the saved UI language as early as possible. On Android 13+ (TIRAMISU) we
        // call the platform LocaleManager directly — AppCompatDelegate's recreate hooks
        // only fire from AppCompatActivity, and MainActivity is a plain ComponentActivity,
        // so the AppCompat path silently no-ops on a Compose-only app. The platform API
        // triggers Activity recreate at the framework level regardless of base class.
        // On <33 we fall back to AppCompatDelegate, which persists via the metadata
        // service and restores on next launch.
        val appScope = CoroutineScope(Dispatchers.Main)
        appScope.launch {
            SettingsRepository(this@LenscastApp).flow
                .map { it.languageTag.trim() }
                .distinctUntilChanged()
                .collect { tag -> applyLocale(tag) }
        }
    }

    private fun applyLocale(tag: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = getSystemService(LocaleManager::class.java)
            lm?.applicationLocales = if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                else LocaleList(Locale.forLanguageTag(tag))
        } else {
            AppCompatDelegate.setApplicationLocales(
                if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList()
                else LocaleListCompat.forLanguageTags(tag),
            )
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val streaming = NotificationChannel(
            CHANNEL_STREAMING,
            getString(R.string.notif_channel_streaming),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notif_channel_streaming_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        // Separate channel for the persistent web-control notification — keeps the
        // existing "Streaming" channel scoped to actual streaming events, so the user
        // can mute one without losing the other.
        val webControl = NotificationChannel(
            CHANNEL_WEB_CONTROL,
            getString(R.string.notif_channel_web_control),
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = getString(R.string.notif_channel_web_control_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(streaming)
        nm.createNotificationChannel(webControl)
    }

    companion object {
        const val CHANNEL_STREAMING = "lenscast.streaming"
        const val CHANNEL_WEB_CONTROL = "lenscast.webcontrol"
    }
}
