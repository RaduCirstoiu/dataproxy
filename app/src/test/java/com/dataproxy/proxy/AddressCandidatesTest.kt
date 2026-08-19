package com.dataproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class AddressCandidatesTest {

    @Test
    fun `keeps every distinct DNS fallback in resolver order`() {
        val ipv6 = InetAddress.getByName("2001:db8::1")
        val ipv4 = InetAddress.getByName("192.0.2.1")

        val result = selectConnectCandidates(
            addresses = listOf(ipv6, ipv4, ipv6),
            limit = 5,
        )

        assertEquals(listOf(ipv6, ipv4), result)
    }

    @Test
    fun `limits pathological DNS responses`() {
        val addresses = (1..8).map { InetAddress.getByName("192.0.2.$it") }

        val result = selectConnectCandidates(addresses, limit = 5)

        assertEquals(addresses.take(5), result)
    }
}
