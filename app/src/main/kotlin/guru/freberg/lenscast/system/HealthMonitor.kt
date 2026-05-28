package guru.freberg.lenscast.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Lightweight battery + thermal observers, exposed as Flows. Surfaced in the UI as a
 * pre-emptive warning banner before the OS starts throttling the camera or audio —
 * which it does aggressively above THERMAL_STATUS_SEVERE, and silently kills the
 * foreground service if the battery saver kicks in at <15 %.
 */
object HealthMonitor {

    enum class Severity { OK, WARN, CRITICAL }
    data class State(val severity: Severity, val message: String?)

    /**
     * Combined state — picks the most severe of (battery state, thermal state) and
     * builds a user-facing message. State.OK with message=null means "no banner".
     */
    fun observe(context: Context): Flow<State> =
        battery(context).combine(thermal(context)) { b, t ->
            val items = listOfNotNull(b, t).sortedByDescending { it.severity.ordinal }
            val worst = items.firstOrNull() ?: State(Severity.OK, null)
            worst
        }

    /**
     * Synchronous one-shot of the current battery + thermal state. Used by the web control
     * `/status` endpoint, which builds JSON in a single pass — keeping a Flow collector
     * alive purely to read latest-value would be wasteful at /status's poll rate.
     */
    fun snapshotNow(context: Context): State {
        val battery = snapshot(context)
        val thermal = thermalSnapshot(context)
        return listOf(battery, thermal).maxByOrNull { it.severity.ordinal } ?: State(Severity.OK, null)
    }

    private fun thermalSnapshot(context: Context): State {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return State(Severity.OK, null)
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return State(Severity.OK, null)
        return thermalState(pm.currentThermalStatus)
    }

    /** Battery flow — sticky-broadcast based, so the initial value is immediate. */
    private fun battery(context: Context): Flow<State> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                trySend(snapshot(context))
            }
        }
        trySend(snapshot(context))
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        awaitClose {
            try { context.unregisterReceiver(receiver) } catch (_: Throwable) {}
        }
    }.map { it }

    private fun snapshot(context: Context): State {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return State(Severity.OK, null)
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0 || scale <= 0) return State(Severity.OK, null)
        val pct = (level * 100) / scale
        // Charging masks any battery worry — the OS won't enter battery-saver mode while
        // plugged in, so the camera can keep running indefinitely.
        if (plugged) return State(Severity.OK, null)
        return when {
            pct <= 10 -> State(Severity.CRITICAL, "Battery $pct% — Android may stop the camera within minutes")
            pct <= 20 -> State(Severity.WARN, "Battery $pct% — plug in to keep streaming")
            else      -> State(Severity.OK, null)
        }
    }

    /**
     * Thermal flow — `PowerManager.addThermalStatusListener` on API 29+, no-op on older
     * (the global thermal API isn't available; OEM throttling still happens but we can't
     * see it coming).
     */
    private fun thermal(context: Context): Flow<State> = callbackFlow {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            trySend(State(Severity.OK, null))
            awaitClose { }
            return@callbackFlow
        }
        val listener = PowerManager.OnThermalStatusChangedListener { status ->
            trySend(thermalState(status))
        }
        trySend(thermalState(pm.currentThermalStatus))
        try { pm.addThermalStatusListener(listener) } catch (_: Throwable) {}
        awaitClose {
            try { pm.removeThermalStatusListener(listener) } catch (_: Throwable) {}
        }
    }.map { it }

    private fun thermalState(status: Int): State = when (status) {
        PowerManager.THERMAL_STATUS_MODERATE ->
            State(Severity.WARN, "Phone is warm — frame rate may drop soon")
        PowerManager.THERMAL_STATUS_SEVERE ->
            State(Severity.CRITICAL, "Phone is hot — Android is throttling the camera")
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN ->
            State(Severity.CRITICAL, "Phone is dangerously hot — streaming may stop")
        else -> State(Severity.OK, null)
    }
}
