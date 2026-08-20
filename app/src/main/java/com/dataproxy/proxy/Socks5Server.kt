package com.dataproxy.proxy

import com.dataproxy.network.CellularNetworkProvider
import com.dataproxy.util.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.SocketException

/**
 * Single-instance SOCKS5 listener. One [start] / [stop] cycle.
 *
 * `bindAddress` may be `"0.0.0.0"` (all interfaces) or a specific local IP from
 * [com.dataproxy.network.NetworkInterfaceLister]. Every accepted client
 * spawns a [Socks5Connection] coroutine on the supervisor scope so a single
 * failure does not kill the accept loop.
 */
class Socks5Server(
    val bindAddress: String,
    val port: Int,
    private val cellular: CellularNetworkProvider,
    val registry: ConnectionRegistry,
    private val onFatal: (Throwable) -> Unit,
    private val authProvider: () -> AuthConfig = { AuthConfig.Disabled },
) {
    private val socketLock = Any()

    @Volatile
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile var running: Boolean = false
        private set

    fun start() {
        if (running) return
        registry.reset()

        val socket = try {
            openServerSocket()
        } catch (e: IOException) {
            AppLog.e(TAG, "bind failed on $bindAddress:$port", e)
            onFatal(e); return
        }

        synchronized(socketLock) {
            serverSocket = socket
            running = true
        }
        AppLog.i(TAG, "listening on ${socket.inetAddress.hostAddress}:${socket.localPort}")

        acceptJob = scope.launch {
            var activeSocket = socket
            try {
                while (running) {
                    val client = try {
                        activeSocket.accept()
                    } catch (e: SocketException) {
                        if (!running) break
                        activeSocket = recoverListener(activeSocket, e) ?: break
                        continue
                    } catch (e: IOException) {
                        if (!running) break
                        activeSocket = recoverListener(activeSocket, e) ?: break
                        continue
                    }
                    Socks5Connection(
                        clientSocket = client,
                        cellular = cellular,
                        registry = registry,
                        scope = scope,
                        authProvider = authProvider,
                    ).handle()
                }
            } finally {
                AppLog.i(TAG, "accept loop exited")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        val socket = synchronized(socketLock) {
            serverSocket.also { serverSocket = null }
        }
        runCatching { socket?.close() }
        acceptJob?.cancel()
        acceptJob = null
        scope.cancel()
        AppLog.i(TAG, "stopped")
    }

    private fun openServerSocket(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        val addr = InetAddress.getByName(bindAddress)
        bind(InetSocketAddress(addr, port), BACKLOG)
    }

    /**
     * Android may destroy sockets attached to a VPN network when that VPN is
     * recreated (commonly when an OEM wakes the phone). Keep the foreground
     * service useful by rebinding the listener once its local address returns.
     */
    private suspend fun recoverListener(
        failedSocket: ServerSocket,
        cause: IOException,
    ): ServerSocket? {
        synchronized(socketLock) {
            if (serverSocket === failedSocket) serverSocket = null
        }
        runCatching { failedSocket.close() }
        AppLog.w(
            TAG,
            "listener closed unexpectedly: ${cause.message}; attempting to rebind " +
                "$bindAddress:$port",
        )

        var lastFailure: IOException = cause
        repeat(REBIND_ATTEMPTS) { index ->
            if (!running) return null
            if (index > 0) delay(REBIND_DELAY_MS)

            val replacement = try {
                openServerSocket()
            } catch (error: IOException) {
                lastFailure = error
                val attempt = index + 1
                if (attempt == 1 || attempt % REBIND_LOG_EVERY == 0) {
                    AppLog.w(
                        TAG,
                        "listener rebind attempt $attempt/$REBIND_ATTEMPTS failed: " +
                            error.message,
                    )
                }
                null
            } ?: return@repeat

            val installed = synchronized(socketLock) {
                if (running) {
                    serverSocket = replacement
                    true
                } else {
                    false
                }
            }
            if (!installed) {
                runCatching { replacement.close() }
                return null
            }

            AppLog.i(
                TAG,
                "listener recovered on ${replacement.inetAddress.hostAddress}:" +
                    "${replacement.localPort} after ${index + 1} attempt(s)",
            )
            return replacement
        }

        if (running) onFatal(lastFailure)
        return null
    }

    companion object {
        private const val TAG = "Socks5Server"
        private const val BACKLOG = 64
        private const val REBIND_ATTEMPTS = 30
        private const val REBIND_DELAY_MS = 1_000L
        private const val REBIND_LOG_EVERY = 5
    }
}
