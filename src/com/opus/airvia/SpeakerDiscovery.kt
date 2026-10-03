package com.opus.airvia

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import java.util.ArrayDeque

/** An AirPlay receiver found on the local network. */
data class Speaker(val name: String, val host: String, val port: Int)

/**
 * NSD discovery of AirPlay receivers (`_raop._tcp`, with `_airplay._tcp`
 * merged in and de-duplicated by endpoint — a HomePod advertises both).
 * Resolves services one at a time (NSD resolves are not parallel-safe on
 * older Android), preferring the clean `_airplay` display name over the
 * `MAC@Name` form `_raop` uses.
 */
class SpeakerDiscovery(
    context: Context,
    private val onChanged: (List<Speaker>) -> Unit,
) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val wifi = app.getSystemService(WifiManager::class.java)
    private var multicastLock: WifiManager.MulticastLock? = null

    private val byEndpoint = LinkedHashMap<String, Speaker>()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    @Volatile
    private var resolving = false
    private var running = false
    private val listeners = ArrayList<NsdManager.DiscoveryListener>()

    // One listener instance per service type (NSD rejects reusing a
    // single listener for two simultaneous discoveries).
    private fun makeListener() = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            synchronized(resolveQueue) { resolveQueue.add(info) }
            resolveNext()
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            synchronized(byEndpoint) {
                val gone = byEndpoint.values.filter {
                    it.name == cleanName(info.serviceName)
                }
                gone.forEach { byEndpoint.remove(endpoint(it.host, it.port)) }
                if (gone.isNotEmpty()) publish()
            }
        }

        override fun onDiscoveryStarted(type: String) {
            LogBus.log("[discover] browsing $type")
        }

        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) {
            LogBus.log("[discover] start failed for $type: $code")
        }

        override fun onStopDiscoveryFailed(type: String, code: Int) {}
    }

    fun start() {
        if (running) return
        running = true
        try {
            multicastLock = wifi.createMulticastLock("airvia-nsd").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: Exception) {
        }
        for (type in listOf("_raop._tcp", "_airplay._tcp")) {
            try {
                val listener = makeListener()
                listeners.add(listener)
                nsd.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
            } catch (e: Exception) {
                LogBus.log("[discover] cannot browse $type: ${e.message}")
            }
        }
    }

    fun stop() {
        if (!running) return
        running = false
        for (listener in listeners) {
            try {
                nsd.stopServiceDiscovery(listener)
            } catch (_: Exception) {
            }
        }
        listeners.clear()
        try {
            multicastLock?.release()
        } catch (_: Exception) {
        }
        multicastLock = null
    }

    private fun resolveNext() {
        val next: NsdServiceInfo
        synchronized(resolveQueue) {
            if (resolving) return
            next = resolveQueue.poll() ?: return
            resolving = true
        }
        try {
            @Suppress("DEPRECATION")
            nsd.resolveService(next, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    resolving = false
                    val host = info.host?.hostAddress
                    if (host != null) {
                        val name = cleanName(info.serviceName)
                        val key = endpoint(host, info.port)
                        synchronized(byEndpoint) {
                            val existing = byEndpoint[key]
                            // Prefer the name without the "MAC@" prefix.
                            if (existing == null || existing.name.contains("@")) {
                                byEndpoint[key] = Speaker(name, host, info.port)
                            }
                            publish()
                        }
                        LogBus.log("[discover] '$name' @ $host:${info.port}")
                    }
                    resolveNext()
                }

                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    resolving = false
                    resolveNext()
                }
            })
        } catch (_: Exception) {
            resolving = false
            resolveNext()
        }
    }

    private fun publish() {
        val list = synchronized(byEndpoint) { byEndpoint.values.toList() }
        onChanged(list)
    }

    private fun endpoint(host: String, port: Int) = "$host:$port"

    private fun cleanName(raw: String): String =
        if (raw.contains("@")) raw.substringAfterLast("@").trim() else raw.trim()
}
