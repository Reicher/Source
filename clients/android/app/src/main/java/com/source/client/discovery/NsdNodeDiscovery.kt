package com.source.client.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import com.source.client.model.DiscoveredNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class NsdNodeDiscovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val _nodes = MutableStateFlow<List<DiscoveredNode>>(emptyList())
    private val byServiceName = linkedMapOf<String, DiscoveredNode>()
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var running = false
    private var multicastLock: WifiManager.MulticastLock? = null

    val nodes: StateFlow<List<DiscoveredNode>> = _nodes

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit

        override fun onServiceFound(service: NsdServiceInfo) {
            if (!running || !service.serviceType.startsWith(SERVICE_TYPE.removeSuffix("."))) return
            pending.removeAll { it.serviceName == service.serviceName }
            pending.addLast(service)
            resolveNext()
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            byServiceName.remove(service.serviceName)
            publish()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            running = false
            releaseMulticastLock()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            running = false
            releaseMulticastLock()
        }
    }

    fun start() {
        if (running) return
        running = true
        multicastLock = wifi.createMulticastLock("source-client-nsd").apply {
            setReferenceCounted(false)
            acquire()
        }
        runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            .onFailure {
                running = false
                releaseMulticastLock()
            }
    }

    fun stop() {
        if (running) runCatching { nsd.stopServiceDiscovery(discoveryListener) }
        running = false
        pending.clear()
        resolving = false
        byServiceName.clear()
        publish()
        releaseMulticastLock()
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        if (!running || resolving || pending.isEmpty()) return
        resolving = true
        val service = pending.removeFirst()
        nsd.resolveService(service, object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = resolved()

            override fun onServiceResolved(info: NsdServiceInfo) {
                parse(info)?.let { byServiceName[info.serviceName] = it }
                publish()
                resolved()
            }
        })
    }

    private fun resolved() {
        resolving = false
        resolveNext()
    }

    @Suppress("DEPRECATION")
    private fun parse(info: NsdServiceInfo): DiscoveredNode? {
        val id = info.attributes["id"]?.toString(Charsets.UTF_8) ?: return null
        val version = info.attributes["v"]?.toString(Charsets.UTF_8)
        val apiPath = info.attributes["api"]?.toString(Charsets.UTF_8)
        val advertisedName = info.attributes["name"]?.toString(Charsets.UTF_8)?.trim()
        if (version != "1" || apiPath != "/api/v1" || !id.matches(Regex("^srcnode_[A-Za-z0-9_-]{43}$")) ||
            advertisedName.isNullOrEmpty() || advertisedName.length > 100 || advertisedName.any { it.isISOControl() }
        ) return null
        val address = if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host
        val rawHost = address?.hostAddress?.substringBefore('%') ?: return null
        val host = if (rawHost.contains(':')) "[$rawHost]" else rawHost
        return DiscoveredNode(info.serviceName, id, advertisedName, "https://$host:${info.port}$apiPath")
    }

    private fun publish() {
        _nodes.value = byServiceName.values.sortedBy { it.displayName.lowercase() }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        const val SERVICE_TYPE = "_source._tcp."
    }
}
