package com.source.client.discovery

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class LocalNetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _available = MutableStateFlow(hasLocalNetwork())
    private val localNetworks = mutableSetOf<Network>()
    private var running = false
    val available: StateFlow<Boolean> = _available

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = update(network, connectivity.getNetworkCapabilities(network))
        override fun onLost(network: Network) {
            localNetworks.remove(network)
            _available.value = localNetworks.isNotEmpty()
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = update(network, capabilities)
    }

    fun start() {
        if (running) return
        running = true
        localNetworks.clear()
        connectivity.activeNetwork?.let { update(it, connectivity.getNetworkCapabilities(it)) }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        connectivity.registerNetworkCallback(request, callback)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private fun update(network: Network, capabilities: NetworkCapabilities?) {
        if (isLocal(capabilities)) localNetworks.add(network) else localNetworks.remove(network)
        _available.value = localNetworks.isNotEmpty()
    }

    private fun hasLocalNetwork(): Boolean = isLocal(connectivity.getNetworkCapabilities(connectivity.activeNetwork))

    private fun isLocal(capabilities: NetworkCapabilities?): Boolean = capabilities?.let {
        it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    } == true
}
