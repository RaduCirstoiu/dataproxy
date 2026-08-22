package com.dataproxy.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.system.ErrnoException
import android.system.OsConstants
import com.dataproxy.util.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException

/**
 * Maintains a live handle on the device's cellular network so that outbound sockets
 * can be pinned to mobile data, irrespective of which network is the system default.
 *
 * The proxy listens on the VPN/WiFi/LAN side; outbound sockets are explicitly
 * bound to cellular. A narrowly guarded unbound fallback supports Android VPNs
 * that reject explicit network selection even when cellular is the only
 * possible physical route.
 */
class CellularNetworkProvider(context: Context) {

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var cellular: Network? = null

    @Volatile
    private var explicitSocketBindingBlocked = false

    @Volatile
    private var fallbackLogged = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            // We do NOT call cm.bindProcessToNetwork(network) here.
            // That tags every socket the process creates — including the
            // SOCKS5 listener — with the cellular netId. The kernel then
            // routes the listener's SYN-ACK replies via the cellular
            // route table, so external clients on Wi-Fi never finish the
            // TCP handshake (SYN_RECV → retransmits → time out).
            //
            // All outbound traffic is pinned to cellular explicitly via
            // Network.bindSocket on each created socket
            // (see createOutboundSocket / createBoundDatagramSocket), and
            // every DNS lookup goes through Network.getAllByName on this
            // network handle. No code path in this app uses JVM-default
            // DNS, so dropping the process binding doesn't open a leak.
            if (cellular != network) {
                explicitSocketBindingBlocked = false
                fallbackLogged = false
            }
            cellular = network
            _state.value = State.Available(network)
            AppLog.i(TAG, "cellular available: $network")
        }

        override fun onLost(network: Network) {
            if (cellular == network) {
                cellular = null
                explicitSocketBindingBlocked = false
                fallbackLogged = false
                _state.value = State.Lost
                AppLog.w(TAG, "cellular lost: $network")
            }
        }

        override fun onUnavailable() {
            cellular = null
            explicitSocketBindingBlocked = false
            fallbackLogged = false
            _state.value = State.Unavailable
            AppLog.w(TAG, "cellular unavailable")
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            cellular = network
        }
    }

    private var registered = false

    @Synchronized
    fun start() {
        if (registered) return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.requestNetwork(request, callback)
        registered = true
        _state.value = State.Requesting
        AppLog.i(TAG, "requested cellular network")
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        cellular = null
        explicitSocketBindingBlocked = false
        fallbackLogged = false
        _state.value = State.Idle
        AppLog.i(TAG, "released cellular network")
    }

    /** Block-bind a socket to the cellular network. Throws if cellular is not up. */
    fun bindSocket(socket: Socket) {
        val net = cellular
            ?: throw IllegalStateException("Cellular network not available")
        net.bindSocket(socket)
    }

    /**
     * Suspend until the registered callback has stored a cellular network, or
     * return null on [timeoutMs].
     *
     * Do not issue a second request here. A temporary callback could return a
     * network before [callback] populated [cellular], allowing the service to
     * report Running while every outbound bind still failed as unavailable.
     */
    suspend fun awaitAvailable(timeoutMs: Long = 10_000L): Network? = withTimeoutOrNull(timeoutMs) {
        cellular?.let { return@withTimeoutOrNull it }
        when (val result = _state.first {
            it is State.Available || it is State.Unavailable
        }) {
            is State.Available -> result.network
            else -> null
        }
    }

    /** Resolve every address using cellular DNS (not WiFi DNS). */
    fun resolveHost(host: String): List<InetAddress> {
        val net = cellular ?: return emptyList()
        return runCatching { net.getAllByName(host).toList() }.getOrDefault(emptyList())
    }

    /**
     * Create a TCP socket that is guaranteed to use cellular for [destination].
     *
     * Tailscale's Android VPN does not permit apps to explicitly bind around
     * it, which surfaces as EPERM even if its split routes do not include the
     * destination. In that one case we may use an ordinary socket, but only if
     * every physical internet network is cellular-only and no VPN route matches
     * the destination. Any uncertainty fails closed.
     */
    fun createOutboundSocket(destination: InetAddress): Socket {
        if (explicitSocketBindingBlocked) {
            return createSafeUnboundSocket(destination, null)
        }

        val socket = Socket()
        return try {
            bindSocket(socket)
            socket
        } catch (error: Exception) {
            runCatching { socket.close() }
            if (!error.isOperationNotPermitted()) throw error
            explicitSocketBindingBlocked = true
            createSafeUnboundSocket(destination, error)
        }
    }

    /** Block-bind a DatagramSocket to the cellular network for UDP egress. */
    fun bindDatagram(socket: DatagramSocket) {
        val net = cellular
            ?: throw IllegalStateException("Cellular network not available")
        net.bindSocket(socket)
    }

    /** Create a new UDP socket already bound to the cellular network. */
    fun createBoundDatagramSocket(): DatagramSocket {
        val socket = DatagramSocket()
        bindDatagram(socket)
        return socket
    }

    private fun createSafeUnboundSocket(
        destination: InetAddress,
        bindFailure: Exception?,
    ): Socket {
        val currentCellular = cellular
            ?: throw IllegalStateException("Cellular network not available", bindFailure)

        val assessment = runCatching {
            val internetPhysicalNetworks = cm.allNetworks.mapNotNull { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                ) {
                    return@mapNotNull null
                }
                caps
            }
            val allPhysicalNetworksAreCellularOnly = allNetworksAreCellularOnly(
                transportTypesByNetwork = internetPhysicalNetworks.map { caps ->
                    KNOWN_TRANSPORT_TYPES.filter(caps::hasTransport).toIntArray()
                },
                cellularTransport = NetworkCapabilities.TRANSPORT_CELLULAR,
            )

            var inspectedAllVpnRoutes = true
            var destinationRoutedByVpn = false
            cm.allNetworks.forEach { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@forEach
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@forEach
                val linkProperties = cm.getLinkProperties(network)
                if (linkProperties == null) {
                    inspectedAllVpnRoutes = false
                } else if (linkProperties.routes.any { it.matches(destination) }) {
                    destinationRoutedByVpn = true
                }
            }

            FallbackAssessment(
                allowed = canUseUnboundCellularFallback(
                    allPhysicalInternetNetworksAreCellularOnly =
                        allPhysicalNetworksAreCellularOnly,
                    inspectedAllVpnRoutes = inspectedAllVpnRoutes,
                    destinationRoutedByVpn = destinationRoutedByVpn,
                ),
                reason = when {
                    internetPhysicalNetworks.isEmpty() ->
                        "no physical internet network was visible"
                    !allPhysicalNetworksAreCellularOnly ->
                        "a non-cellular or mixed physical internet transport is active"
                    !inspectedAllVpnRoutes -> "VPN routes could not be inspected"
                    destinationRoutedByVpn ->
                        "the destination is covered by a VPN route or exit node"
                    else -> "safe"
                },
            )
        }.getOrElse { error ->
            FallbackAssessment(false, "network route inspection failed: ${error.message}")
        }

        if (!assessment.allowed) {
            throw SocketException(
                "VPN blocked cellular binding; unbound fallback refused because ${assessment.reason}"
            ).apply { bindFailure?.let(::initCause) }
        }

        if (!fallbackLogged) {
            fallbackLogged = true
            AppLog.w(
                TAG,
                "VPN blocked explicit cellular binding; using the default socket safely " +
                    "because all physical routes are cellular-only and the destination is " +
                    "outside VPN routes",
            )
        }
        return Socket()
    }

    private fun Throwable.isOperationNotPermitted(): Boolean {
        var current: Throwable? = this
        while (current != null) {
            if (current is ErrnoException && current.errno == OsConstants.EPERM) return true
            current = current.cause
        }
        return false
    }

    private data class FallbackAssessment(
        val allowed: Boolean,
        val reason: String,
    )

    sealed interface State {
        data object Idle : State
        data object Requesting : State
        data class Available(val network: Network) : State
        data object Lost : State
        data object Unavailable : State
    }

    companion object {
        private const val TAG = "CellularNetwork"

        // NetworkCapabilities does not expose its transport set as an array.
        // Enumerate every transport Android 16 defines so mixed or unknown-for-
        // this-release physical routes fail the cellular-only check.
        private val KNOWN_TRANSPORT_TYPES = intArrayOf(
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_VPN,
            NetworkCapabilities.TRANSPORT_WIFI_AWARE,
            NetworkCapabilities.TRANSPORT_LOWPAN,
            NetworkCapabilities.TRANSPORT_USB,
            NetworkCapabilities.TRANSPORT_THREAD,
            NetworkCapabilities.TRANSPORT_SATELLITE,
        )
    }
}
