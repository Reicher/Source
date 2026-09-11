package com.source.client.storage

import org.json.JSONArray
import org.json.JSONObject

data class LibraryItem(
    val id: String,
    val name: String,
    val sourceType: String = "file",
    val mimeType: String,
    val byteCount: Long,
    val createdAtMillis: Long,
    val contentSha256: String,
    /** True only after the Node has acknowledged a complete encrypted copy. */
    val nodeStored: Boolean = false,
) {
    init {
        require(ID_PATTERN.matches(id)) { "Invalid Library item identifier" }
        require(name.isNotBlank() && name.length <= 255) { "Invalid Library item name" }
        require(sourceType.matches(TYPE_PATTERN)) { "Invalid Library item type" }
        require(mimeType.isNotBlank() && mimeType.length <= 200) { "Invalid Library MIME type" }
        require(byteCount >= 0) { "Invalid Library item size" }
        require(createdAtMillis > 0) { "Invalid Library creation time" }
        require(SHA256_PATTERN.matches(contentSha256)) { "Invalid Library content hash" }
    }
}

data class LibraryTombstone(
    val itemId: String,
    val contentSha256: String,
    val deletedAtMillis: Long,
) {
    init {
        require(ID_PATTERN.matches(itemId)) { "Invalid Library tombstone identifier" }
        require(SHA256_PATTERN.matches(contentSha256)) { "Invalid Library tombstone hash" }
        require(deletedAtMillis > 0) { "Invalid Library deletion time" }
    }
}

data class LibraryManifest(
    val items: List<LibraryItem> = emptyList(),
    val tombstones: List<LibraryTombstone> = emptyList(),
    val modifiedAtMillis: Long = 0L,
)

object LibraryData : SourceData<LibraryManifest> {
    override val descriptor = SourceDataDescriptor(
        id = "library",
        remoteAppId = "source-library",
        snapshotFormat = "source-library-manifest",
        formatVersion = 1,
    )
    override val emptyValue = LibraryManifest()

    override fun encode(value: LibraryManifest): ByteArray {
        validateManifest(value)
        return JSONObject().apply {
        put("version", descriptor.formatVersion)
        put("modifiedAtMillis", value.modifiedAtMillis)
        put("items", JSONArray().apply {
            value.items.forEach { item ->
                put(JSONObject().apply {
                    put("id", item.id)
                    put("name", item.name)
                    put("sourceType", item.sourceType)
                    put("mimeType", item.mimeType)
                    put("byteCount", item.byteCount)
                    put("createdAtMillis", item.createdAtMillis)
                    put("contentSha256", item.contentSha256)
                    put("nodeStored", item.nodeStored)
                })
            }
        })
        put("tombstones", JSONArray().apply {
            value.tombstones.forEach { tombstone ->
                put(JSONObject().apply {
                    put("itemId", tombstone.itemId)
                    put("contentSha256", tombstone.contentSha256)
                    put("deletedAtMillis", tombstone.deletedAtMillis)
                })
            }
        })
        }.toString().toByteArray(Charsets.UTF_8)
    }

    override fun decode(value: ByteArray): LibraryManifest {
        val root = JSONObject(value.toString(Charsets.UTF_8))
        require(root.getInt("version") == descriptor.formatVersion)
        val items = root.getJSONArray("items")
        val tombstones = root.getJSONArray("tombstones")
        return LibraryManifest(
            items = List(items.length()) { index ->
                items.getJSONObject(index).let {
                    LibraryItem(
                        id = it.getString("id"),
                        name = it.getString("name"),
                        sourceType = it.getString("sourceType"),
                        mimeType = it.getString("mimeType"),
                        byteCount = it.getLong("byteCount"),
                        createdAtMillis = it.getLong("createdAtMillis"),
                        contentSha256 = it.getString("contentSha256"),
                        nodeStored = it.optBoolean("nodeStored", false),
                    )
                }
            },
            tombstones = List(tombstones.length()) { index ->
                tombstones.getJSONObject(index).let {
                    LibraryTombstone(
                        itemId = it.getString("itemId"),
                        contentSha256 = it.getString("contentSha256"),
                        deletedAtMillis = it.getLong("deletedAtMillis"),
                    )
                }
            },
            modifiedAtMillis = root.getLong("modifiedAtMillis"),
        ).also(::validateManifest)
    }

    override fun version(value: LibraryManifest): SourceDataVersion {
        validateManifest(value)
        return SourceDataVersion(
            modifiedAtMillis = value.modifiedAtMillis,
            contentIdentity = buildString {
            value.items.sortedBy(LibraryItem::id).forEach {
                append(it.id).append('\u0000').append(it.contentSha256).append('\u0000')
                    .append(it.nodeStored).append('\u0000')
            }
            value.tombstones.sortedBy(LibraryTombstone::itemId).forEach {
                append(it.itemId).append('\u0000').append(it.contentSha256)
                    .append('\u0000').append(it.deletedAtMillis).append('\u0000')
            }
            },
        )
    }

    private fun validateManifest(value: LibraryManifest) {
        require(value.modifiedAtMillis >= 0)
        require(value.items.map(LibraryItem::id).distinct().size == value.items.size)
        require(value.items.map(LibraryItem::contentSha256).distinct().size == value.items.size)
        require(value.tombstones.map(LibraryTombstone::itemId).distinct().size == value.tombstones.size)
        val activeIds = value.items.mapTo(mutableSetOf(), LibraryItem::id)
        require(value.tombstones.none { it.itemId in activeIds })
    }
}

private val ID_PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE)
private val SHA256_PATTERN = Regex("^[0-9a-f]{64}$")
private val TYPE_PATTERN = Regex("^[a-z][a-z0-9-]{0,31}$")
