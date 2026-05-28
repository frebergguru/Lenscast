package dev.lenscast.net

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/**
 * mDNS / DNS-SD advertiser for the streaming server. Once started, the device shows up on
 * the LAN as `Lenscast` of type `_http._tcp.` (MJPEG) or `_rtsp._tcp.` (RTSP) — so OBS,
 * VLC, and Bonjour-aware tooling can discover the URL without the user typing an IP.
 *
 * Both NSD registrations live on the same [NsdManager]; calling [start] more than once
 * without [stop] in between is a no-op (the same instance can't multiplex).
 */
class NsdAdvertiser(private val context: Context) {

    private var manager: NsdManager? = null
    private var listener: NsdManager.RegistrationListener? = null

    /**
     * Register a service of the given type. [type] follows the standard mDNS form
     * (e.g. `_http._tcp.`, `_rtsp._tcp.`). Name collisions are auto-resolved by NSD.
     */
    fun start(type: String, port: Int, serviceName: String = "Lenscast") {
        if (listener != null) return
        val mgr = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = type
            this.port = port
        }
        val lis = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(svc: NsdServiceInfo) {
                Log.i(TAG, "Advertised ${svc.serviceName} (${svc.serviceType}) on :${svc.port}")
            }
            override fun onRegistrationFailed(svc: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD register failed (code=$code) for ${svc.serviceType}")
            }
            override fun onServiceUnregistered(svc: NsdServiceInfo) {
                Log.i(TAG, "NSD unregistered ${svc.serviceType}")
            }
            override fun onUnregistrationFailed(svc: NsdServiceInfo, code: Int) {
                Log.w(TAG, "NSD unregister failed (code=$code) for ${svc.serviceType}")
            }
        }
        try {
            mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, lis)
            manager = mgr
            listener = lis
        } catch (t: Throwable) {
            Log.w(TAG, "NSD registerService threw: ${t.message}")
        }
    }

    fun stop() {
        val mgr = manager ?: return
        val lis = listener ?: return
        try { mgr.unregisterService(lis) } catch (_: Throwable) {}
        manager = null
        listener = null
    }

    companion object {
        private const val TAG = "NsdAdvertiser"
        const val TYPE_MJPEG = "_http._tcp."
        const val TYPE_RTSP = "_rtsp._tcp."
    }
}
