package dev.lenscast.system

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import java.util.concurrent.Executor

/**
 * Watches the device's call state and reports active / idle transitions so the streaming
 * service can apply the user's chosen [dev.lenscast.prefs.CallBehavior] — mute the
 * outgoing audio, drop the incoming call, or do nothing.
 *
 * Uses the modern [TelephonyCallback] API on Android 12+ and falls back to the deprecated
 * [PhoneStateListener] on older releases. Both code paths require READ_PHONE_STATE; if
 * permission is missing the monitor silently no-ops (the SecurityException is logged and
 * eaten — the call-handling features just don't react).
 *
 * Single boolean output instead of the raw three-state {IDLE, RINGING, OFFHOOK}, because
 * everything we want to do (mute the encoder, drop the ringer) is the same regardless of
 * the specific non-idle state.
 */
class TelephonyMonitor(
    private val context: Context,
    private val executor: Executor,
    private val onActiveChanged: (active: Boolean) -> Unit,
) {
    private var callback31: TelephonyCallback? = null
    private var legacyListener: PhoneStateListener? = null
    @Volatile private var active = false

    @SuppressLint("MissingPermission") // Caller is expected to gate on READ_PHONE_STATE.
    fun start() {
        if (callback31 != null || legacyListener != null) return
        val mgr = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = ActiveCallback()
            try {
                mgr.registerTelephonyCallback(executor, cb)
                callback31 = cb
            } catch (t: Throwable) {
                Log.w(TAG, "registerTelephonyCallback failed: ${t.message}")
            }
        } else {
            val l = LegacyListener()
            try {
                @Suppress("DEPRECATION")
                mgr.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
                legacyListener = l
            } catch (t: Throwable) {
                Log.w(TAG, "PhoneStateListener.listen failed: ${t.message}")
            }
        }
    }

    fun stop() {
        val mgr = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            callback31?.let { try { mgr?.unregisterTelephonyCallback(it) } catch (_: Throwable) {} }
            callback31 = null
        } else {
            legacyListener?.let {
                @Suppress("DEPRECATION")
                try { mgr?.listen(it, PhoneStateListener.LISTEN_NONE) } catch (_: Throwable) {}
            }
            legacyListener = null
        }
        if (active) {
            active = false
            onActiveChanged(false)
        }
    }

    private fun handle(state: Int) {
        val isActive = state != TelephonyManager.CALL_STATE_IDLE
        if (isActive != active) {
            active = isActive
            onActiveChanged(isActive)
        }
    }

    @RequiresApiS
    private inner class ActiveCallback : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) = handle(state)
    }

    private inner class LegacyListener : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, phoneNumber: String?) = handle(state)
    }

    companion object { private const val TAG = "TelephonyMonitor" }
}

@Suppress("ObsoleteSdkInt")
@Retention(AnnotationRetention.BINARY)
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private annotation class RequiresApiS
