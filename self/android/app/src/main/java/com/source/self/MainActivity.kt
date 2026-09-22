package com.source.self

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var state: PairingState
    private lateinit var connection: SourceConnection
    private var connected = false
    private var error: String? = null
    private var scanning = false
    private var identityReady = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = PairingState(this)
        connection = SourceConnection(this, state) { isConnected, message, rescan ->
            connected = isConnected
            error = message
            render()
            if (rescan) startScan()
        }
        try {
            state.ensureSelfIdentity()
        } catch (e: Exception) {
            identityReady = false
            error = "Self kunde inte skapa sin identitet."
        }
        render()
        if (identityReady) {
            if (state.source() == null) startScan() else connection.start()
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
                connected = false
                connection.start()
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
        connection.stop()
        super.onDestroy()
    }
}
