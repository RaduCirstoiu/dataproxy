package com.dataproxy.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSocketFallbackTest {
    @Test
    fun `allows fallback when cellular is sole physical route and vpn misses destination`() {
        assertTrue(
            canUseUnboundCellularFallback(
                cellularIsOnlyPhysicalInternetNetwork = true,
                inspectedAllVpnRoutes = true,
                destinationRoutedByVpn = false,
            )
        )
    }

    @Test
    fun `refuses fallback when wifi or another physical network exists`() {
        assertFalse(
            canUseUnboundCellularFallback(
                cellularIsOnlyPhysicalInternetNetwork = false,
                inspectedAllVpnRoutes = true,
                destinationRoutedByVpn = false,
            )
        )
    }

    @Test
    fun `refuses fallback when vpn routes destination such as an exit node`() {
        assertFalse(
            canUseUnboundCellularFallback(
                cellularIsOnlyPhysicalInternetNetwork = true,
                inspectedAllVpnRoutes = true,
                destinationRoutedByVpn = true,
            )
        )
    }

    @Test
    fun `refuses fallback when vpn routes cannot be inspected`() {
        assertFalse(
            canUseUnboundCellularFallback(
                cellularIsOnlyPhysicalInternetNetwork = true,
                inspectedAllVpnRoutes = false,
                destinationRoutedByVpn = false,
            )
        )
    }
}
