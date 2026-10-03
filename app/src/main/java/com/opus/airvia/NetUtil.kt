package com.opus.airvia

import java.net.NetworkInterface

/** Small network helpers shared by the RAOP fallback and stream server. */
object NetUtil {
    /** Best-effort LAN IPv4 of this device (prefers wlan interfaces). */
    fun localIp(): String {
        var fallback: String? = null
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return "0.0.0.0"
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback || iface.isVirtual) continue
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    val host = addr.hostAddress ?: continue
                    if (host.contains(':')) continue // IPv6 — AirPlay/DLNA here are v4
                    if (iface.name.startsWith("wlan")) return host
                    if (fallback == null) fallback = host
                }
            }
        } catch (_: Exception) {
        }
        return fallback ?: "0.0.0.0"
    }
}
