package com.source.self

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.net.Inet4Address
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val SERVICE_TYPE = "_sourceself._tcp."

class MainActivity : Activity() {
    private lateinit var state: PairingState
    private lateinit var nsd: NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val connecting = AtomicBoolean(false)
    private var discovery: NsdManager.DiscoveryListener? = null
    private var address: String? = null
    private var resolvedName: String? = null
    private var port: Int = 0
    private var connected = false
    private var error: String? = null
    private var scanning = false
    private var identityReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = PairingState(this)
        nsd = getSystemService(Context.NSD_SERVICE) as NsdManager
        try {
            state.ensureSelfIdentity()
        } catch (e: Exception) {
            identityReady = false
            error = "Self identity is unavailable: ${e.message}"
        }
        render()
        if (identityReady) {
            if (state.source() == null) startScan() else startDiscovery()
            handler.postDelayed(tick, 10_000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state.source() != null) {
                if (address != null) connect() else if (discovery == null) startDiscovery()
                handler.postDelayed(this, 10_000)
            }
        }
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        IntentIntegrator(this)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
            .setPrompt("Scan the QR code on Source")
            .setBeepEnabled(false)
            .initiateScan()
    }

    @Deprecated("Used by the QR scanner activity")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        scanning = false
        if (result.contents != null) {
            try {
                val qr = JSONObject(result.contents)
                val source = SourceRef.fromQr(qr)
                if (state.isPaired()) throw IllegalArgumentException("Self already belongs to a Source")
                state.savePending(source)
                error = null
                address = null
                resolvedName = null
                connected = false
                discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
                discovery = null
                startDiscovery()
            } catch (e: Exception) {
                error = "Invalid Source QR code: ${e.message}"
            }
        }
        render()
    }

    private fun startDiscovery() {
        if (discovery != null || state.source() == null) return
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) { runOnUiThread { if (discovery === this) discovery = null } }
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { discovery = null; error = "Local discovery failed ($errorCode)"; render() }
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { runOnUiThread { discovery = null } }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                runOnUiThread {
                    if (serviceInfo.serviceName == resolvedName) {
                        address = null
                        resolvedName = null
                        connected = false
                        render()
                    }
                }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                runOnUiThread {
                    if (state.source() == null) return@runOnUiThread
                    // TXT and service names select candidates; only TLS pinning grants trust.
                    nsd.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            runOnUiThread {
                                val announcedId = resolved.attributes["id"]?.toString(Charsets.UTF_8)
                                if (announcedId != state.source()?.id) return@runOnUiThread
                                val hosts = if (android.os.Build.VERSION.SDK_INT >= 34) resolved.hostAddresses else listOfNotNull(resolved.host)
                                val host = hosts.firstOrNull { it is Inet4Address } ?: hosts.firstOrNull() ?: return@runOnUiThread
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
        catch (e: Exception) { discovery = null; error = "Local discovery unavailable: ${e.message}"; render() }
    }

    private fun connect() {
        val source = state.source() ?: return
        val host = address ?: return
        if (!connecting.compareAndSet(false, true)) return
        io.execute {
            try {
                val result = PairingTransport(state).connect(source, host, port)
                runOnUiThread {
                    if (state.source()?.id != source.id || state.source()?.pin != source.pin) return@runOnUiThread
                    if (!state.isPaired()) state.savePaired(source, result)
                    connected = true
                    error = null
                    render()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (state.source()?.id != source.id || state.source()?.pin != source.pin) return@runOnUiThread
                    connected = false
                    error = e.message ?: "Connection failed"
                    render()
                }
            } finally { connecting.set(false) }
        }
    }

    private fun render() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
        }
        val status = when {
            connected -> "Connected"
            state.source() == null -> "Scan Source QR code"
            state.isPaired() -> "Looking for paired Source…"
            else -> "Pairing with Source…"
        }
        layout.addView(TextView(this).apply { text = status; textSize = 26f; gravity = Gravity.CENTER })
        error?.let { layout.addView(TextView(this).apply { text = it; textSize = 16f; gravity = Gravity.CENTER }) }
        if (identityReady && !state.isPaired()) layout.addView(Button(this).apply { text = if (state.source() == null) "Scan QR" else "Rescan QR"; setOnClickListener { startScan() } })
        setContentView(layout)
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        io.shutdownNow()
        super.onDestroy()
    }
}
