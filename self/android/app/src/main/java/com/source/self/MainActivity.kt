package com.source.self

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
    private var needsRescan = false
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
            error = "Self kunde inte skapa sin identitet."
        }
        render()
        if (identityReady) {
            if (state.source() == null) startScan() else startDiscovery()
            handler.postDelayed(tick, 10_000)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (state.source() != null && !needsRescan) {
                if (address != null) connect() else if (discovery == null) startDiscovery()
            }
            handler.postDelayed(this, 10_000)
        }
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        IntentIntegrator(this)
            .setCaptureActivity(SelfCaptureActivity::class.java)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
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
                needsRescan = false
                address = null
                resolvedName = null
                connected = false
                discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
                discovery = null
                startDiscovery()
            } catch (e: Exception) {
                render()
                startScan()
                return
            }
        } else {
            finish()
            return
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
        if (needsRescan) return
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
                Log.w("SelfPairing", "Connection failed: ${e.javaClass.simpleName}: ${e.message}")
                runOnUiThread {
                    if (state.source()?.id != source.id || state.source()?.pin != source.pin) return@runOnUiThread
                    connected = false
                    needsRescan = !state.isPaired() && e is PairingHttpException && (e.status == 403 || e.status == 409)
                    error = when {
                        needsRescan -> null
                        e is PairingHttpException -> "Source avvisade anslutningen."
                        else -> "Kunde inte ansluta till Source."
                    }
                    render()
                    if (needsRescan) startScan()
                }
            } finally { connecting.set(false) }
        }
    }

    private fun render() {
        val backgroundColor = Color.rgb(14, 20, 21)
        val accent = Color.rgb(116, 220, 167)
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = 0
        val root = FrameLayout(this).apply {
            setBackgroundColor(backgroundColor)
            setPadding(dp(28), dp(28), dp(28), dp(28))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(28) + bars.left, dp(28) + bars.top, dp(28) + bars.right, dp(28) + bars.bottom)
                insets
            }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val status = when {
            connected -> "Ansluten"
            state.source() == null -> "Skanna Source-koden"
            state.isPaired() -> "Söker efter Source…"
            else -> "Kopplar ihop…"
        }
        if (connected) {
            content.addView(View(this).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(accent) }
            }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { bottomMargin = dp(20) })
        } else if (error == null && state.source() != null) {
            content.addView(ProgressBar(this).apply { indeterminateTintList = android.content.res.ColorStateList.valueOf(accent) },
                LinearLayout.LayoutParams(dp(32), dp(32)).apply { bottomMargin = dp(20) })
        }
        content.addView(TextView(this).apply {
            text = status
            textSize = 25f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        error?.let {
            content.addView(TextView(this).apply {
                text = it
                textSize = 15f
                setTextColor(Color.rgb(189, 201, 195))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        root.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        discovery?.let { try { nsd.stopServiceDiscovery(it) } catch (_: Exception) {} }
        io.shutdownNow()
        super.onDestroy()
    }
}
