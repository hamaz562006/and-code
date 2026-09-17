package com.yugahashimoto.andcode.runtime.local

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/** Prefer IPv4 on Android networks that advertise unreachable IPv6 routes. */
class Ipv4FirstDns : Dns {
    override fun lookup(hostname: String): List<InetAddress> = ipv4First(Dns.SYSTEM.lookup(hostname))
}

internal fun ipv4First(addresses: List<InetAddress>): List<InetAddress> = addresses.sortedBy { if (it is Inet4Address) 0 else 1 }
