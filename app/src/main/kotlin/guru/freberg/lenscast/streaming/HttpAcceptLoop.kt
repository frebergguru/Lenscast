package guru.freberg.lenscast.streaming

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLContext

/**
 * Shared accept loop for the hand-rolled HTTP servers ([MjpegServer], [WebControlServer]).
 * Both bound an (optionally TLS) [ServerSocket] with identical socket tuning and spawned a
 * coroutine per accepted client — the same boilerplate that had drifted apart only in log
 * wording. Binds on [port], hands the socket back via [onBound] so the caller's `stop()`
 * can close it, then loops until [isRunning] flips false or the socket closes.
 */
internal fun CoroutineScope.launchHttpAcceptLoop(
    port: Int,
    sslContext: SSLContext?,
    logTag: String,
    serviceName: String,
    isRunning: () -> Boolean,
    onBound: (ServerSocket) -> Unit,
    handle: suspend (Socket) -> Unit,
): Job = launch {
    try {
        val s = if (sslContext != null) {
            sslContext.serverSocketFactory.createServerSocket().also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(port))
            }
        } else {
            ServerSocket().also {
                it.reuseAddress = true
                it.bind(InetSocketAddress(port))
            }
        }
        onBound(s)
        val scheme = if (sslContext != null) "https" else "http"
        Log.i(logTag, "$serviceName $scheme listening on 0.0.0.0:$port")
        while (isRunning() && isActive) {
            val client = try { s.accept() } catch (e: IOException) { break }
            // TLS sockets need TCP_NODELAY set before the handshake.
            client.tcpNoDelay = true
            client.soTimeout = 5_000
            launch { handle(client) }
        }
    } catch (t: Throwable) {
        Log.e(logTag, "$serviceName accept loop crashed", t)
    }
}
