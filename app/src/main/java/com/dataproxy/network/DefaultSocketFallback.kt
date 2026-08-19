package com.dataproxy.network

/**
 * An unbound socket is safe only when cellular is the sole physical internet
 * network and the destination cannot be captured by a VPN route.
 */
internal fun canUseUnboundCellularFallback(
    cellularIsOnlyPhysicalInternetNetwork: Boolean,
    inspectedAllVpnRoutes: Boolean,
    destinationRoutedByVpn: Boolean,
): Boolean = cellularIsOnlyPhysicalInternetNetwork &&
    inspectedAllVpnRoutes &&
    !destinationRoutedByVpn
