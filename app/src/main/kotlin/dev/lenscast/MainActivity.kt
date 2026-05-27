package dev.lenscast

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import dev.lenscast.streaming.StreamingService
import dev.lenscast.ui.MainScreen
import dev.lenscast.ui.theme.LenscastTheme

class MainActivity : ComponentActivity() {

    private val serviceState = mutableStateOf<StreamingService?>(null)
    private var bound: Boolean = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, ibinder: IBinder) {
            serviceState.value = (ibinder as StreamingService.LocalBinder).service
            bound = true
        }
        override fun onServiceDisconnected(name: ComponentName) {
            serviceState.value = null
            bound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Bind to the streaming service — created lazily by AUTO_CREATE. Foreground promotion
        // happens when the user taps Start so we don't need camera permission upfront.
        val intent = Intent(this, StreamingService::class.java)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        setContent {
            val svc by serviceState
            LenscastTheme {
                MainScreen(
                    service = svc,
                    startForeground = { ContextCompat.startForegroundService(this, Intent(this, StreamingService::class.java)) },
                )
            }
        }
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(connection) } catch (_: Throwable) {}
            bound = false
        }
        super.onDestroy()
    }
}
