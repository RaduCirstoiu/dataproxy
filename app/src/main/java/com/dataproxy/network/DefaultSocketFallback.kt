package com.dataproxy.network

/**
 * An unbound socket is safe only when every physical internet network is
 * cellular-only and the destination cannot be captured by a VPN route.
 *
 * Android/OEMs may expose more than one Network object for the same cellular
 * transport, so requiring exactly one object is unnecessarily strict.
 */
internal fun canUseUnboundCellularFallback(
    allPhysicalInternetNetworksAreCellularOnly: Boolean,
    inspectedAllVpnRoutes: Boolean,
    destinationRoutedByVpn: Boolean,
): Boolean = allPhysicalInternetNetworksAreCellularOnly &&
    inspectedAllVpnRoutes &&
    !destinationRoutedByVpn

/**
 * True only when at least one physical internet network is visible and every
 * one of those networks reports cellular as its sole transport.
 */
internal fun allNetworksAreCellularOnly(
    transportTypesByNetwork: List<IntArray>,
    cellularTransport: Int,
): Boolean = transportTypesByNetwork.isNotEmpty() &&
    transportTypesByNetwork.all { transports ->
        transports.isNotEmpty() && transports.all { it == cellularTransport }
    }
