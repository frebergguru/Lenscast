package dev.lenscast

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
        val channel = NotificationChannel(
            CHANNEL_STREAMING,
            getString(R.string.notif_channel_streaming),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_streaming_description)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_STREAMING = "lenscast.streaming"
    }
}
