package com.kcg.dr.utils

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

enum class NetworkType {
    HOTSPOT,
    WIFI,
    CELLULAR,
    LOOPBACK,
    UNKNOWN
}

fun NetworkInterface.getNetworkType(): NetworkType {
    val name = this.name.lowercase()
    return when {
        isLoopback -> NetworkType.LOOPBACK
        name.startsWith("ap") || name.startsWith("softap") || name.contains("wlan1") -> NetworkType.HOTSPOT
        name.startsWith("wlan") -> NetworkType.WIFI
        name.startsWith("rmnet") || name.startsWith("pdp") -> NetworkType.CELLULAR
        else -> NetworkType.UNKNOWN
    }
}

fun InetAddress.isPrivateNetwork(): Boolean =
    hostAddress?.let {
        it.startsWith("192.168.") ||
                it.startsWith("172.16.") ||
                it.startsWith("10.")
    } ?: false

fun Collection<NetworkInterface>.filterActive() = filter { it.isUp && !it.isLoopback }

fun getIpAddress(type: NetworkType): String? {
    val interfaces = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filterActive()
    } catch (e: Exception) {
        e.printStackTrace()
        return null
    }

    interfaces
        .find { it.getNetworkType() == type }
        ?.findFirstAddress()
        ?.let { return it }

    if (type == NetworkType.HOTSPOT || type == NetworkType.WIFI) {
        return interfaces
            .filter { it.getNetworkType() != NetworkType.CELLULAR }
            .findAddress { it.isPrivateNetwork() }
    }

    return null
}

fun getLocalIpAddress(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filterActive()
            .firstNotNullOfOrNull { it.findFirstAddress() }
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

fun Collection<NetworkInterface>.findAddress(addressPredicate: (InetAddress) -> Boolean): String? =
    this
        .firstNotNullOfOrNull {
            it.findFirstAddress(addressPredicate)
        }

fun NetworkInterface.findFirstAddress(predicate: (InetAddress) -> Boolean = { true }): String? =
    inetAddresses.asSequence()
        .filter { !it.isLoopbackAddress && it is Inet4Address && predicate(it) }
        .firstNotNullOfOrNull { it.hostAddress }