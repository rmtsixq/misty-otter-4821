package com.sixq.gozjest.core

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** Small helpers for finding the other phone on a hotspot / local Wi-Fi link. */
object NetUtils {

    /** Every non-loopback IPv4 address of this device, e.g. `192.168.43.1`. */
    fun localIpv4Addresses(): List<String> = buildList {
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp }.getOrDefault(false) }
                .forEach { iface ->
                    iface.inetAddresses.asSequence()
                        .filterIsInstance<Inet4Address>()
                        .filterNot { it.isLoopbackAddress }
                        .forEach { add(it.hostAddress ?: return@forEach) }
                }
        }
    }

    /** Subnet broadcast addresses plus the global one, for discovery probes. */
    fun broadcastAddresses(): List<InetAddress> {
        val result = linkedSetOf<InetAddress>()
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .forEach { iface ->
                    iface.interfaceAddresses.forEach { ifaceAddr ->
                        ifaceAddr.broadcast?.let { result.add(it) }
                    }
                }
        }
        runCatching { result.add(InetAddress.getByName("255.255.255.255")) }
        return result.toList()
    }

    /**
     * The default-route gateway of the active network. When this device is
     * joined to the other phone's hotspot, that gateway *is* the other phone.
     */
    fun defaultGateway(context: Context): String? = runCatching {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return null
        val linkProperties = cm.getLinkProperties(network) ?: return null
        linkProperties.routes
            .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
    }.getOrNull()

    /**
     * Guesses the hotspot host from our own address: a client that got
     * `192.168.43.57` is almost always talking to `192.168.43.1`.
     */
    fun guessedHotspotHosts(): List<String> = localIpv4Addresses()
        .mapNotNull { address ->
            val parts = address.split('.')
            if (parts.size != 4) null else "${parts[0]}.${parts[1]}.${parts[2]}.1"
        }
        .distinct()
        .filterNot { it in localIpv4Addresses() }
}
