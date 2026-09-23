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
import java.util.concurrent.atomic.AtomicInteger

private const val SERVICE_TYPE = "_sourceself._tcp."

// Owns discovery and the authenticated connection while the screen is open.
class SourceConnection(
    context: Context,
    private val state: PairingState,
    private val bronze: BronzeStore,
    private val silver: SilverStore,
    private val onStatus: (connected: Boolean, error: String?, rescan: Boolean) -> Unit,
    private val onDataChanged: () -> Unit,
) {
    private val nsd = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val connecting = AtomicBoolean(false)
    private val syncVersion = AtomicInteger(0)
    private val transport = PairingTransport(state)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var address: String? = null
    private var resolvedName: String? = null
    private var port = 0
    @Volatile private var generation = 0
    @Volatile private var active = false
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
        syncVersion.incrementAndGet()
        transport.cancel()
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
        syncVersion.incrementAndGet()
        transport.cancel()
        active = false
        handler.removeCallbacks(tick)
        stopDiscovery()
        io.shutdownNow()
    }

    fun syncSoon() { if (active && address != null) connect() }

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
                    syncVersion.incrementAndGet()
                    transport.cancel()
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
        val session = syncVersion.get()
        if (!connecting.compareAndSet(false, true)) return
        io.execute {
            var bronzeChanged = false
            var silverChanged = false
            try {
                val personId = transport.connect(source, host, targetPort)
                if (!isCurrent(current, source) || session != syncVersion.get()) return@execute
                if (!state.isPaired()) state.savePaired(source, personId)
                BronzeSync(bronze, transport).run(source, host, targetPort,
                    { isCurrent(current, source) && session == syncVersion.get() },
                    { bronzeChanged = true })
                if (!isCurrent(current, source) || session != syncVersion.get()) return@execute
                silverChanged = silver.install(transport.silver(source, host, targetPort))
                handler.post {
                    if (!isCurrent(current, source) || session != syncVersion.get()) return@post
                    if (bronzeChanged || silverChanged) onDataChanged()
                    onStatus(true, null, false)
                }
            } catch (e: Exception) {
                Log.w("SelfPairing", "Connection failed: ${e.javaClass.simpleName}: ${e.message}")
                handler.post {
                    if (!isCurrent(current, source) || session != syncVersion.get()) return@post
                    if (bronzeChanged || silverChanged) onDataChanged()
                    needsRescan = !state.isPaired() && e is PairingHttpException && (e.status == 403 || e.status == 409)
                    val message = when {
                        needsRescan -> null
                        e is PairingHttpException && e.status == 404 && e.path == "/v1/bronze" ->
                            "Source needs the Bronze sync update."
                        e is PairingHttpException && e.status == 404 && e.path == "/v1/silver" ->
                            "Source needs the Silver sync update."
                        e is PairingHttpException -> "Source rejected the connection."
                        else -> "Could not connect or sync with Source."
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
