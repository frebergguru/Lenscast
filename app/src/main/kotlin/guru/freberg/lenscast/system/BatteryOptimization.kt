package guru.freberg.lenscast.system

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimization (Doze / OEM app-standby) exemption helper.
 *
 * A foreground service with a wake + Wi-Fi lock keeps the *radio and CPU* awake, but on
 * many devices the OEM battery manager will still throttle or freeze the app's process and
 * defer its network once the screen has been off a while — unless the app is set to
 * "Unrestricted" (added to the system battery-optimization allow-list). For a phone-as-camera
 * app that must keep streaming and answering the panel/API with the screen locked, that
 * exemption is the difference between "stays up" and "freezes". DroidCam asks for the same
 * thing. We can only detect the state and deep-link the user to the right screen — granting
 * it is the user's decision.
 */
object BatteryOptimization {

    fun isExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open the exemption flow. Prefers the direct per-app request dialog (needs the
     * REQUEST_IGNORE_BATTERY_OPTIMIZATIONS permission); falls back to the battery-optimization
     * list screen, then to the app's own details page, so the user can always reach the toggle.
     */
    fun request(context: Context) {
        @Suppress("BatteryLife") // legitimate: a screen-off streaming server must not be dozed
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(direct)
            return
        } catch (_: ActivityNotFoundException) {
            // fall through
        }
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(list)
            return
        } catch (_: ActivityNotFoundException) {
            // fall through
        }
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
