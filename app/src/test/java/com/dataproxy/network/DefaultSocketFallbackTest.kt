package com.dataproxy.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultSocketFallbackTest {
    @Test
    fun `allows fallback when all physical routes are cellular and vpn misses destination`() {
        assertTrue(
            canUseUnboundCellularFallback(
                allPhysicalInternetNetworksAreCellularOnly = true,
                inspectedAllVpnRoutes = true,
                destinationRoutedByVpn = false,
            )
        )
    }

    @Test
    fun `refuses fallback when a non-cellular physical network exists`() {
        assertFalse(
            canUseUnboundCellularFallback(
                allPhysicalInternetNetworksAreCellularOnly = false,
                inspectedAllVpnRoutes = true,
                destinationRoutedByVpn = false,
            )
        )
    }

    @Test
    fun `refuses fallback when vpn routes destination such as an exit node`() {
        assertFalse(
            canUseUnboundCellularFallback(
                allPhysicalInternetNetworksAreCellularOnly = true,
                inspectedAllVpnRoutes = true,
                destinationRoutedByVpn = true,
            )
        )
    }

    @Test
    fun `refuses fallback when vpn routes cannot be inspected`() {
        assertFalse(
            canUseUnboundCellularFallback(
                allPhysicalInternetNetworksAreCellularOnly = true,
                inspectedAllVpnRoutes = false,
                destinationRoutedByVpn = false,
            )
        )
    }

    @Test
    fun `accepts multiple cellular-only network objects`() {
        assertTrue(
            allNetworksAreCellularOnly(
                transportTypesByNetwork = listOf(intArrayOf(0), intArrayOf(0)),
                cellularTransport = 0,
            )
        )
    }

    @Test
    fun `rejects wifi mixed or unknown physical transports`() {
        assertFalse(
            allNetworksAreCellularOnly(
                transportTypesByNetwork = listOf(intArrayOf(0), intArrayOf(1)),
                cellularTransport = 0,
            )
        )
        assertFalse(
            allNetworksAreCellularOnly(
                transportTypesByNetwork = listOf(intArrayOf(0, 1)),
                cellularTransport = 0,
            )
        )
        assertFalse(
            allNetworksAreCellularOnly(
                transportTypesByNetwork = emptyList(),
                cellularTransport = 0,
            )
        )
    }
}
