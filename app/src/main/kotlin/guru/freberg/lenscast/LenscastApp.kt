package guru.freberg.lenscast

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class LenscastApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
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
