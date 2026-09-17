package com.yugahashimoto.andcode.runtime.local

import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class Ipv4FirstDnsTest {
    @Test
    fun `puts IPv4 addresses before IPv6 addresses`() {
        val ipv6 = InetAddress.getByName("::1")
        val ipv4 = InetAddress.getByName("127.0.0.1")

        assertEquals(listOf(ipv4, ipv6), ipv4First(listOf(ipv6, ipv4)))
    }

    @Test
    fun `keeps IPv6 addresses when no IPv4 address is available`() {
        val ipv6 = InetAddress.getByName("::1")

        assertEquals(listOf(ipv6), ipv4First(listOf(ipv6)))
    }
}
