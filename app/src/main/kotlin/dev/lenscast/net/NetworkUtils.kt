package dev.lenscast.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket

object NetworkUtils {

    /**
     * Returns the device's IPv4 address on its active Wi-Fi/Ethernet network, or null if none.
     * Tries ConnectivityManager first (works on API 23+), falls back to enumerating interfaces
     * if the system doesn't expose link properties cleanly.
     */
    fun getLocalIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return enumerateFallback()
        val active = cm.activeNetwork ?: return enumerateFallback()
        val caps = cm.getNetworkCapabilities(active)
        val wifiOrEthernet = caps != null && (
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        )
        if (!wifiOrEthernet) return enumerateFallback()
        val link = cm.getLinkProperties(active) ?: return enumerateFallback()
        val v4: LinkAddress? = link.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
        return v4?.address?.hostAddress ?: enumerateFallback()
    }

    private fun enumerateFallback(): String? {
        return try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        } catch (_: Throwable) {
            null
        }
    }

    fun isPortFree(port: Int): Boolean = try {
        ServerSocket(port).use { true }
    } catch (_: Throwable) {
        false
    }
}
