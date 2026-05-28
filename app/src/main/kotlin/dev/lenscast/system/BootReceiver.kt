package dev.lenscast.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dev.lenscast.prefs.SettingsRepository
import dev.lenscast.streaming.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Fires once on `BOOT_COMPLETED`. Reads [SettingsRepository.flow], and if
 * `settings.startOnBoot` is true, hands off to [StreamingService] via
 * `ACTION_START_TILE` — same code path the QS tile uses.
 *
 * Caveats noted in the Settings hint: device must allow the broadcast through battery
 * optimisation, and the user must have granted CAMERA already (the service can't request
 * runtime permissions from a non-UI context).
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) return
        val pending = goAsync()
        // Kotlin goAsync() requires us to call finish() eventually. Use a short-lived
        // GlobalScope-ish CoroutineScope on IO; we just need the suspending DataStore read.
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context).flow.first()
                if (settings.startOnBoot) {
                    val svcIntent = Intent(context, StreamingService::class.java)
                        .setAction(StreamingService.ACTION_START_TILE)
                    ContextCompat.startForegroundService(context, svcIntent)
                }
            } catch (_: Throwable) {
                // Boot is a hostile environment — battery saver, locked DataStore, no perms.
                // Silent failure is fine; the user will notice the stream isn't up.
            } finally {
                pending.finish()
            }
        }
    }
}
