package com.source.self

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors

private const val PICK_FILE = 1001
private val backgroundColor = Color.rgb(14, 20, 21)
private val accent = Color.rgb(116, 220, 167)
private val secondary = Color.rgb(189, 201, 195)

class MainActivity : Activity() {
    private lateinit var state: PairingState
    private lateinit var bronze: BronzeStore
    private lateinit var connection: SourceConnection
    private val io = Executors.newSingleThreadExecutor()
    private var connected = false
    private var error: String? = null
    private var scanning = false
    private var identityReady = true
    private var detailId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = PairingState(this)
        bronze = BronzeStore(this)
        detailId = savedInstanceState?.getString("detail")
        connection = SourceConnection(this, state, bronze, { isConnected, message, rescan ->
            connected = isConnected
            error = message
            render()
            if (rescan) startScan()
        }, { render() })
        try { state.ensureSelfIdentity() }
        catch (_: Exception) { identityReady = false; error = "Could not create Self identity." }
        render()
        if (identityReady) {
            if (state.source() == null) startScan() else connection.start()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("detail", detailId)
        super.onSaveInstanceState(outState)
    }

    private fun startScan() {
        if (scanning) return
        scanning = true
        IntentIntegrator(this).setCaptureActivity(SelfCaptureActivity::class.java)
            .setDesiredBarcodeFormats(IntentIntegrator.QR_CODE).setBeepEnabled(false).initiateScan()
    }

    private fun pick() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, PICK_FILE)
    }

    @Deprecated("Used by the QR scanner and document picker")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_FILE) {
            if (resultCode == RESULT_OK && data?.data != null) {
                val uri = data.data!!
                write { bronze.import(uri) }
            }
            return
        }
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) { super.onActivityResult(requestCode, resultCode, data); return }
        scanning = false
        if (result.contents != null) {
            try {
                val source = SourceRef.fromQr(JSONObject(result.contents))
                if (state.isPaired()) throw IllegalArgumentException("Self already belongs to a Source")
                state.savePending(source)
                error = null
                connected = false
                connection.start()
            } catch (_: Exception) { render(); startScan(); return }
        } else { finish(); return }
        render()
    }

    private fun write(action: () -> Unit) {
        io.execute {
            try {
                action()
                runOnUiThread { render(); connection.syncSoon() }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this).setMessage("Could not save: ${e.message}")
                        .setPositiveButton("Close", null).show()
                }
            }
        }
    }

    private fun noteDialog(item: BronzeItem? = null) {
        val body = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 6; gravity = Gravity.TOP
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setText(item?.let { bronze.content(it).readText(Charsets.UTF_8) } ?: "")
        }
        AlertDialog.Builder(this).setView(body).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val text = body.text.toString()
                write { if (item == null) bronze.createNote(text) else bronze.editText(item.id, text) }
            }.show()
    }

    private fun root(): LinearLayout {
        window.statusBarColor = backgroundColor
        window.navigationBarColor = backgroundColor
        window.decorView.systemUiVisibility = 0
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(backgroundColor)
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(dp(20) + bars.left, dp(20) + bars.top, dp(20) + bars.right, dp(20) + bars.bottom)
                insets
            }
        }
    }

    private fun text(value: String, size: Float = 16f, bold: Boolean = false): TextView = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.WHITE)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun button(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label; isAllCaps = false; setOnClickListener { action() }
    }

    private fun render() {
        if (!identityReady || !state.isPaired()) { renderPairing(); return }
        val selected = detailId?.let { bronze.get(it) }
        if (selected == null || selected.deleted) { detailId = null; renderList() }
        else renderDetail(selected)
    }

    private fun renderPairing() {
        val root = FrameLayout(this).apply { setBackgroundColor(backgroundColor) }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val status = when {
            connected -> "Connected"
            state.source() == null -> "Scan Source code"
            else -> "Connecting…"
        }
        content.addView(text(status, 25f, true).apply { gravity = Gravity.CENTER })
        error?.let { content.addView(text(it, 15f).apply { setTextColor(secondary); gravity = Gravity.CENTER }) }
        root.addView(content, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
    }

    private fun renderList() {
        val root = root()
        error?.let { root.addView(text(it, 13f).apply { setTextColor(secondary) }) }
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("+ Note") { noteDialog() }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("+ File") { pick() }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(actions)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val visible = bronze.all().filterNot { it.deleted }
        visible.forEach { list.addView(bronzeCard(it)) }
        root.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun bronzeCard(item: BronzeItem): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(13), dp(16), dp(13))
        background = GradientDrawable().apply { setColor(Color.rgb(29, 39, 39)); cornerRadius = dp(8).toFloat() }
        addView(text(item.title, 17f, true))
        addView(text(if (item.ackedRevision == item.revision) "Synced" else "Pending", 12f).apply {
            setTextColor(if (item.ackedRevision == item.revision) accent else secondary)
        })
        setOnClickListener { detailId = item.id; render() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
    }

    private fun renderDetail(item: BronzeItem) {
        val root = root()
        root.addView(button("← Back") { detailId = null; render() })
        root.addView(text(item.title, 25f, true), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        when {
            item.mime == "text/plain" -> body.addView(text(bronze.content(item).readText(Charsets.UTF_8)))
            item.mime.startsWith("image/") -> {
                val file = bronze.content(item)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                val sample = maxOf(1, maxOf(bounds.outWidth, bounds.outHeight) / 1200)
                val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                if (bitmap != null) body.addView(ImageView(this).apply { setImageBitmap(bitmap); adjustViewBounds = true })
            }
        }
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        body.addView(text("Size: ${item.size} bytes\nAdded: ${date.format(Date(item.created))}\nModified: ${date.format(Date(item.modified))}\nType: ${item.mime}", 14f).apply {
            setTextColor(secondary)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        if (item.mime == "text/plain") root.addView(button("Edit") { noteDialog(item) })
        root.addView(button("Delete") {
            AlertDialog.Builder(this).setMessage("Delete ${item.title}?")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ -> detailId = null; write { bronze.delete(item.id) } }.show()
        })
        setContentView(root)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()

    @Deprecated("Use the visible back button")
    override fun onBackPressed() {
        if (detailId != null) { detailId = null; render() } else super.onBackPressed()
    }

    override fun onDestroy() {
        connection.stop(); io.shutdownNow(); super.onDestroy()
    }
}
