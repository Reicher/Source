package com.source.self

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.google.zxing.integration.android.IntentIntegrator
import org.json.JSONObject
import java.util.concurrent.Executors

private const val PICK_FILE = 1001

class MainActivity : Activity() {
    private lateinit var state: PairingState
    private lateinit var bronze: BronzeStore
    private lateinit var silver: SilverStore
    private lateinit var desktop: DesktopStore
    private lateinit var connection: SourceConnection
    private lateinit var views: SelfViews
    private val io = Executors.newSingleThreadExecutor()
    private var connected = false
    private var disconnectedAt: Long? = null
    private var error: String? = null
    private var scanning = false
    private var identityReady = true
    private var detailId: String? = null
    private var knowledgeSourceId: String? = null
    private var entityId: String? = null
    private var section = AppSection.DESKTOP
    private val systemBack = OnBackInvokedCallback { handleBack() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, systemBack)
        state = PairingState(this)
        bronze = SelfStores.bronze(this)
        silver = SelfStores.silver(this)
        desktop = DesktopStore(this)
        desktop.reconcileBronze(bronze.all())
        desktop.reconcileSilver(silver.snapshot().entities)
        views = SelfViews(this, bronze)
        detailId = savedInstanceState?.getString("detail")
        knowledgeSourceId = savedInstanceState?.getString("knowledge_source")
        entityId = savedInstanceState?.getString("entity")
        section = savedInstanceState?.getString("section")?.let {
            runCatching { AppSection.valueOf(it) }.getOrNull()
        } ?: AppSection.DESKTOP
        disconnectedAt = savedInstanceState?.getLong("disconnected_at")?.takeIf { it > 0 }
        connection = SourceConnection(this, state, bronze, silver, { isConnected, message, rescan ->
            if (connected && !isConnected && disconnectedAt == null) disconnectedAt = System.currentTimeMillis()
            if (isConnected) disconnectedAt = null
            connected = isConnected
            error = message
            render()
            if (rescan) startScan()
        }, {
            desktop.reconcileBronze(bronze.all())
            desktop.reconcileSilver(silver.snapshot().entities)
        })
        try {
            state.ensureSelfIdentity()
        } catch (_: Exception) {
            identityReady = false
            error = "Could not create Self identity."
        }
        render()
        if (identityReady) {
            if (state.source() == null) startScan() else {
                connection.start()
                BackgroundSyncScheduler.enqueueIfPending(this, state, bronze)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("detail", detailId)
        outState.putString("knowledge_source", knowledgeSourceId)
        outState.putString("entity", entityId)
        outState.putString("section", section.name)
        disconnectedAt?.let { outState.putLong("disconnected_at", it) }
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
                write {
                    val item = bronze.import(uri)
                    desktop.pin(DESKTOP_OBJECT_BRONZE, item.id)
                }
            }
            return
        }
        val result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data)
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        scanning = false
        if (result.contents != null) {
            try {
                val source = SourceRef.fromQr(JSONObject(result.contents))
                if (state.isPaired()) throw IllegalArgumentException("Self already belongs to a Source")
                state.savePending(source)
                error = null
                connected = false
                connection.start()
            } catch (_: Exception) {
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

    private fun write(action: () -> Unit) {
        io.execute {
            try {
                action()
                desktop.reconcileBronze(bronze.all())
                BackgroundSyncScheduler.enqueueIfPending(this, state, bronze)
                runOnUiThread {
                    render()
                    connection.syncSoon()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    AlertDialog.Builder(this).setMessage("Could not save: ${e.message}")
                        .setPositiveButton("Close", null).show()
                }
            }
        }
    }

    private fun noteDialog() {
        val body = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 6
            gravity = Gravity.TOP
            setPadding(views.dp(20), views.dp(16), views.dp(20), views.dp(16))
        }
        AlertDialog.Builder(this).setView(body).setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val value = body.text.toString()
                write {
                    val saved = bronze.createNote(value)
                    desktop.pin(DESKTOP_OBJECT_BRONZE, saved.id)
                }
            }.show()
    }

    private fun render() {
        if (!identityReady || !state.isPaired()) {
            renderPairing()
            return
        }
        val selected = detailId?.let(bronze::get)?.takeUnless { it.deleted }
        if (detailId != null && selected == null) detailId = null
        val silverSnapshot = silver.snapshot()
        val selectedEntity = entityId?.let { id -> silverSnapshot.entities.firstOrNull { it.id == id } }
        if (entityId != null && selectedEntity == null) entityId = null
        val knowledgeItem = knowledgeSourceId?.let(bronze::get)?.takeUnless { it.deleted }
        if (knowledgeSourceId != null && knowledgeItem == null) knowledgeSourceId = null
        val content = if (selectedEntity != null) {
            views.entityDetail(
                selectedEntity,
                silverSnapshot,
                isPinned = desktop.isPinned(DESKTOP_OBJECT_SILVER, selectedEntity.id),
                onPinToggle = {
                    togglePin(DESKTOP_OBJECT_SILVER, selectedEntity.id)
                    render()
                },
                onBronze = { sourceId ->
                    detailId = sourceId
                    knowledgeSourceId = null
                    entityId = null
                    render()
                },
            )
        } else if (knowledgeItem != null) {
            views.silverDetail(knowledgeItem, silverSnapshot.forBronze(knowledgeItem.id)) { id ->
                entityId = id
                render()
            }
        } else if (selected != null) {
            val knowledge = silverSnapshot.forBronze(selected.id)
            views.bronzeDetail(
                selected,
                knowledge,
                onKnowledge = {
                    knowledgeSourceId = selected.id
                    render()
                },
                isPinned = desktop.isPinned(DESKTOP_OBJECT_BRONZE, selected.id),
                onPinToggle = {
                    togglePin(DESKTOP_OBJECT_BRONZE, selected.id)
                    render()
                },
                onDelete = { confirmDelete(selected) },
            )
        } else when (section) {
            AppSection.DESKTOP -> views.desktop(
                desktop.items(),
                silverSnapshot,
                onAdd = ::addMenu,
                onOpen = ::openDesktopItem,
                onItemMenu = ::desktopItemMenu,
            )
            AppSection.SELF -> views.self(bronze.all().count { !it.deleted })
            AppSection.SOURCE -> views.source(connected, disconnectedAt, error, silverSnapshot) { id ->
                entityId = id
                render()
            }
        }
        setContentView(views.app(content, section, connected) { destination ->
            section = destination
            detailId = null
            knowledgeSourceId = null
            entityId = null
            render()
        })
    }

    private fun renderPairing() {
        val status = when {
            connected -> "Connected"
            state.source() == null -> "Scan Source code"
            else -> "Connecting…"
        }
        setContentView(views.pairing(status, error))
    }

    private fun openDesktopItem(ref: DesktopObjectRef) {
        when (ref.objectType) {
            DESKTOP_OBJECT_BRONZE -> detailId = ref.objectId
            DESKTOP_OBJECT_SILVER -> entityId = ref.objectId
            else -> return
        }
        render()
    }

    private fun addMenu() {
        val actions = arrayOf("Note", "File")
        AlertDialog.Builder(this).setTitle("Add").setItems(actions) { _, which ->
            when (which) {
                0 -> noteDialog()
                1 -> pick()
            }
        }.show()
    }

    private fun desktopItemMenu(ref: DesktopObjectRef) {
        val actions = arrayOf("Move earlier", "Move later", "Unpin")
        AlertDialog.Builder(this).setItems(actions) { _, which ->
            when (which) {
                0 -> desktop.move(ref.objectType, ref.objectId, -1)
                1 -> desktop.move(ref.objectType, ref.objectId, 1)
                2 -> desktop.unpin(ref.objectType, ref.objectId)
            }
            render()
        }.show()
    }

    private fun togglePin(objectType: String, objectId: String) {
        if (desktop.isPinned(objectType, objectId)) desktop.unpin(objectType, objectId)
        else desktop.pin(objectType, objectId)
    }

    private fun confirmDelete(item: BronzeItem) {
        AlertDialog.Builder(this).setMessage("Delete ${item.title}?")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                detailId = null
                write { bronze.delete(item.id) }
            }.show()
    }

    private fun handleBack() {
        when {
            entityId != null -> { entityId = null; render() }
            knowledgeSourceId != null -> { knowledgeSourceId = null; render() }
            detailId != null -> { detailId = null; render() }
            section != AppSection.DESKTOP -> { section = AppSection.DESKTOP; render() }
            else -> finish()
        }
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(systemBack)
        connection.stop()
        BackgroundSyncScheduler.enqueueIfPending(this, state, bronze)
        views.close()
        io.shutdownNow()
        super.onDestroy()
    }
}
