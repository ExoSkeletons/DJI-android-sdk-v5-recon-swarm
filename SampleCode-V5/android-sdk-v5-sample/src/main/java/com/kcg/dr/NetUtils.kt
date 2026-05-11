package com.kcg.dr

import java.net.Inet4Address
import java.net.NetworkInterface

object NetUtils {
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            for (inf in interfaces) {
                if (!inf.isUp || inf.isLoopback) continue
                val addresses = inf.inetAddresses.toList()
                for (address in addresses)
                    if (!address.isLoopbackAddress && address is Inet4Address)
                        return address.hostAddress
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }
}