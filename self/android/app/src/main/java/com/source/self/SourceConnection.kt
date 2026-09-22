package com.source.self

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_TYPE = "_sourceself._tcp."

// Owns discovery and the authenticated connection while the screen is open.
class SourceConnection(
    context: Context,
    private val state: PairingState,
    private val onStatus: (connected: Boolean, error: String?, rescan: Boolean) -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val connecting = AtomicBoolean(false)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var address: String? = null
    private var resolvedName: String? = null
    private var port = 0
    private var generation = 0
    private var active = false
    private var needsRescan = false

    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            if (state.source() != null && !needsRescan) {
                if (address != null) connect() else if (discovery == null) startDiscovery()
            }
            handler.postDelayed(this, 10_000)
        }
    }

    fun start() {
        generation++
        active = true
        needsRescan = false
        address = null
        resolvedName = null
        stopDiscovery()
        handler.removeCallbacks(tick)
        startDiscovery()
        handler.postDelayed(tick, 10_000)
    }

    fun stop() {
        generation++
        active = false
        handler.removeCallbacks(tick)
        stopDiscovery()
        io.shutdownNow()
    }

    private fun stopDiscovery() {
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
    }

    private fun startDiscovery() {
        if (!active || discovery != null || state.source() == null) return
        val current = generation
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {
                handler.post { if (discovery === this) discovery = null }
            }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post {
                    if (!active || generation != current) return@post
                    discovery = null
                    onStatus(false, "Local discovery failed ($errorCode)", false)
                }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                handler.post { if (discovery === this) discovery = null }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                handler.post {
                    if (!active || generation != current || serviceInfo.serviceName != resolvedName) return@post
                    address = null
                    resolvedName = null
                    onStatus(false, null, false)
                }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                handler.post {
                    if (!active || generation != current || state.source() == null) return@post
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            handler.post {
                                if (!active || generation != current) return@post
                                // Discovery selects candidates. Only the pinned TLS certificate grants trust.
                                val announcedId = resolved.attributes["id"]?.toString(Charsets.UTF_8)
                                if (announcedId != state.source()?.id) return@post
                                val hosts = if (android.os.Build.VERSION.SDK_INT >= 34) resolved.hostAddresses else listOfNotNull(resolved.host)
                                val host = hosts.firstOrNull { it is Inet4Address } ?: hosts.firstOrNull() ?: return@post
                                address = host.hostAddress
                                resolvedName = resolved.serviceName
                                port = resolved.port
                                connect()
                            }
                        }
                    })
                }
            }
        }
        discovery = listener
        try { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (e: Exception) {
            discovery = null
            onStatus(false, "Local discovery unavailable: ${e.message}", false)
        }
    }

    private fun connect() {
        if (!active || needsRescan) return
        val source = state.source() ?: return
        val host = address ?: return
        val targetPort = port
        val current = generation
        if (!connecting.compareAndSet(false, true)) return
        io.execute {
            try {
                val personId = PairingTransport(state).connect(source, host, targetPort)
                handler.post {
                    if (!isCurrent(current, source)) return@post
                    try {
                        if (!state.isPaired()) state.savePaired(source, personId)
                        onStatus(true, null, false)
                    } catch (e: Exception) {
                        Log.w("SelfPairing", "Could not save pairing", e)
                        onStatus(false, "Kunde inte spara kopplingen till Source.", false)
                    }
                }
            } catch (e: Exception) {
                Log.w("SelfPairing", "Connection failed: ${e.javaClass.simpleName}: ${e.message}")
                handler.post {
                    if (!isCurrent(current, source)) return@post
                    needsRescan = !state.isPaired() && e is PairingHttpException && (e.status == 403 || e.status == 409)
                    val message = when {
                        needsRescan -> null
                        e is PairingHttpException -> "Source avvisade anslutningen."
                        else -> "Kunde inte ansluta till Source."
                    }
                    onStatus(false, message, needsRescan)
                }
            } finally { connecting.set(false) }
        }
    }

    private fun isCurrent(current: Int, source: SourceRef): Boolean {
        val saved = state.source()
        return active && generation == current && saved?.id == source.id && saved.pin == source.pin
    }
}
