package guru.freberg.lenscast

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import guru.freberg.lenscast.streaming.StreamingService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import guru.freberg.lenscast.ui.MainScreen
import guru.freberg.lenscast.ui.theme.LenscastTheme

class MainActivity : ComponentActivity() {

    private val serviceState = mutableStateOf<StreamingService?>(null)
    private val inPipState = mutableStateOf(false)
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

        // If the user has opted into a persistent web panel, fire startForegroundService
        // with ACTION_PERSIST_WEB so the service stays alive past Activity unbind. The
        // service itself reconciles the actual foreground state via its Settings flow
        // observer; this is just to start it from cold.
        lifecycleScope.launch {
            val s = guru.freberg.lenscast.prefs.SettingsRepository(this@MainActivity).flow.first()
            if (s.persistentWebControl && s.webControlEnabled) {
                val persistIntent = Intent(this@MainActivity, StreamingService::class.java)
                    .setAction(StreamingService.ACTION_PERSIST_WEB)
                ContextCompat.startForegroundService(this@MainActivity, persistIntent)
            }
        }

        setContent {
            val svc by serviceState
            val inPip by inPipState
            LenscastTheme {
                MainScreen(
                    service = svc,
                    inPictureInPicture = inPip,
                    startForeground = { ContextCompat.startForegroundService(this, Intent(this, StreamingService::class.java)) },
                )
            }
        }
    }

    /**
     * Auto-enter PiP when the user presses Home while streaming — that's the moment they're
     * most likely to want to keep an eye on framing in a corner while doing something else.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val svc = serviceState.value ?: return
        val live = svc.status.value.state == StreamingService.State.STREAMING
        if (!live) return
        val res = svc.status.value.settings.resolution
        try {
            val params = PictureInPictureParams.Builder()
                .setAspectRatio(Rational(res.width, res.height))
                .build()
            enterPictureInPictureMode(params)
        } catch (_: Throwable) { /* device doesn't support PiP; no-op */ }
    }

    override fun onPictureInPictureModeChanged(isInPip: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPip, newConfig)
        inPipState.value = isInPip
    }

    override fun onDestroy() {
        if (bound) {
            try { unbindService(connection) } catch (_: Throwable) {}
            bound = false
        }
        super.onDestroy()
    }
}
