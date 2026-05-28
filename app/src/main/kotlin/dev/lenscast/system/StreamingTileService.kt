package dev.lenscast.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import dev.lenscast.R
import dev.lenscast.streaming.StreamingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Quick Settings tile that toggles streaming on / off using the last-saved settings. While
 * the QS panel is open we bind to the [StreamingService] and observe its status flow so the
 * tile's active/idle visual is live. Outside that window the tile shows its last known
 * state — the system caches it.
 */
class StreamingTileService : TileService() {

    private var bound = false
    private var service: StreamingService? = null
    private var scope: CoroutineScope? = null
    private var observeJob: Job? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = (binder as StreamingService.LocalBinder).service
            bound = true
            observeJob?.cancel()
            observeJob = scope?.launch {
                service?.status?.collectLatest { refreshTile(it.state) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            bound = false
        }
    }

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val intent = Intent(this, StreamingService::class.java)
        try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (_: Throwable) { /* tile stays in its cached state */ }
    }

    override fun onStopListening() {
        observeJob?.cancel()
        observeJob = null
        if (bound) {
            try { unbindService(connection) } catch (_: Throwable) {}
            bound = false
        }
        service = null
        scope?.cancel(); scope = null
        super.onStopListening()
    }

    override fun onClick() {
        val live = service?.status?.value?.state == StreamingService.State.STREAMING ||
            service?.status?.value?.state == StreamingService.State.STARTING
        if (live) {
            startService(Intent(this, StreamingService::class.java).setAction(StreamingService.ACTION_STOP))
        } else {
            val intent = Intent(this, StreamingService::class.java)
                .setAction(StreamingService.ACTION_START_TILE)
            ContextCompat.startForegroundService(this, intent)
        }
    }

    private fun refreshTile(state: StreamingService.State) {
        val tile = qsTile ?: return
        val active = state == StreamingService.State.STREAMING || state == StreamingService.State.STARTING
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.tile_label)
        tile.subtitle = if (active) {
            getString(R.string.tile_subtitle_live)
        } else {
            getString(R.string.tile_subtitle_idle)
        }
        tile.updateTile()
    }
}
