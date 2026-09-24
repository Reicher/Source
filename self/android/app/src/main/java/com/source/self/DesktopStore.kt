package com.source.self

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

const val DESKTOP_OBJECT_BRONZE = "bronze"
const val DESKTOP_OBJECT_SILVER = "silver"

data class DesktopObjectRef(
    val objectType: String,
    val objectId: String,
    val order: Int,
) {
    val key: String get() = "$objectType:$objectId"
}

internal data class DesktopDocument(
    val items: List<DesktopObjectRef> = emptyList(),
    val knownObjects: Set<String> = emptySet(),
)

/** Local presentation state. It stores references only; Source data remains in its own stores. */
class DesktopStore(context: Context) {
    private val root = File(context.filesDir, "presentation")
    private val file = AtomicFile(File(root, "desktop.json"))
    private var document: DesktopDocument

    init {
        check(root.mkdirs() || root.isDirectory)
        document = load()
    }

    @Synchronized fun items(): List<DesktopObjectRef> = document.items.sortedBy { it.order }

    /**
     * Places Bronze that this installation has never seen. A removed shortcut stays in
     * knownObjects, so synchronization and restarts cannot silently put it back.
     */
    @Synchronized fun reconcileBronze(bronzeItems: List<BronzeItem>) {
        val visibleIds = bronzeItems.filterNot { it.deleted }.map { it.id }.toSet()
        val present = document.items.filterNot {
            it.objectType == DESKTOP_OBJECT_BRONZE && it.objectId !in visibleIds
        }.toMutableList()
        val known = document.knownObjects.toMutableSet()
        var changed = present.size != document.items.size
        bronzeItems.forEach { item ->
            val key = "$DESKTOP_OBJECT_BRONZE:${item.id}"
            if (key !in known) {
                known += key
                changed = true
                if (!item.deleted) present += DesktopObjectRef(
                    DESKTOP_OBJECT_BRONZE, item.id, present.size,
                )
            }
        }
        if (changed) replace(document.copy(items = normalized(present), knownObjects = known))
    }

    @Synchronized fun place(objectType: String, objectId: String) {
        val key = "$objectType:$objectId"
        val known = document.knownObjects + key
        if (document.items.any { it.key == key }) {
            if (known != document.knownObjects) replace(document.copy(knownObjects = known))
            return
        }
        replace(document.copy(
            items = normalized(document.items + DesktopObjectRef(objectType, objectId, document.items.size)),
            knownObjects = known,
        ))
    }

    @Synchronized fun remove(objectType: String, objectId: String) {
        val key = "$objectType:$objectId"
        replace(document.copy(
            items = normalized(document.items.filterNot { it.key == key }),
            knownObjects = document.knownObjects + key,
        ))
    }

    @Synchronized fun move(objectType: String, objectId: String, offset: Int) {
        val items = document.items.sortedBy { it.order }.toMutableList()
        val from = items.indexOfFirst { it.objectType == objectType && it.objectId == objectId }
        if (from < 0) return
        val to = (from + offset).coerceIn(0, items.lastIndex)
        if (from == to) return
        val moved = items.removeAt(from)
        items.add(to, moved)
        replace(document.copy(items = normalized(items)))
    }

    private fun normalized(items: List<DesktopObjectRef>) =
        items.mapIndexed { index, item -> item.copy(order = index) }

    private fun replace(updated: DesktopDocument) {
        document = updated
        save(updated)
    }

    private fun load(): DesktopDocument {
        val json = try {
            JSONObject(file.openRead().bufferedReader().use { it.readText() })
        } catch (_: FileNotFoundException) {
            return DesktopDocument()
        }
        return desktopDocumentFromJson(json)
    }

    private fun save(value: DesktopDocument) {
        val json = desktopDocumentToJson(value)
        val output = file.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }
}

internal fun desktopDocumentFromJson(json: JSONObject): DesktopDocument {
    val itemValues = json.optJSONArray("items") ?: JSONArray()
    val knownValues = json.optJSONArray("known_objects") ?: JSONArray()
    val items = (0 until itemValues.length()).map { index ->
        itemValues.getJSONObject(index).let {
            DesktopObjectRef(
                it.getString("object_type"),
                it.getString("object_id"),
                it.optInt("order", index),
            )
        }
    }.sortedBy { it.order }.mapIndexed { index, item -> item.copy(order = index) }
    val known = (0 until knownValues.length()).map { knownValues.getString(it) }.toSet() + items.map { it.key }
    return DesktopDocument(items, known)
}

internal fun desktopDocumentToJson(value: DesktopDocument): JSONObject = JSONObject().apply {
    put("version", 1)
    put("items", JSONArray().apply {
        value.items.forEach { item -> put(JSONObject().apply {
            put("object_type", item.objectType)
            put("object_id", item.objectId)
            put("order", item.order)
        }) }
    })
    put("known_objects", JSONArray(value.knownObjects.sorted()))
}
