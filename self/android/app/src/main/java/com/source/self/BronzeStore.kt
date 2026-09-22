package com.source.self

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.URLConnection
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class BronzeItem(
    val id: String,
    val revision: Long,
    val hash: String,
    val deleted: Boolean,
    val title: String,
    val mime: String,
    val size: Long,
    val created: Long,
    val modified: Long,
    val ackedRevision: Long = 0,
) {
    fun json(includeAck: Boolean = false) = JSONObject().apply {
        put("id", id); put("revision", revision); put("hash", hash); put("deleted", deleted)
        put("title", title); put("mime", mime); put("size", size)
        put("created", created); put("modified", modified)
        if (includeAck) put("acked_revision", ackedRevision)
    }

    companion object {
        fun fromJson(value: JSONObject) = BronzeItem(
            value.getString("id"), value.getLong("revision"), value.optString("hash"),
            value.getBoolean("deleted"), value.getString("title"), value.getString("mime"),
            value.getLong("size"), value.getLong("created"), value.getLong("modified"),
            value.optLong("acked_revision", 0),
        )
        fun list(value: JSONArray): List<BronzeItem> = (0 until value.length()).map { fromJson(value.getJSONObject(it)) }
    }
}

class BronzeStore(private val context: Context) {
    private val root = File(context.filesDir, "bronze")
    private val items = File(root, "items")
    private val blobs = File(root, "blobs")
    private val activeInstalls = AtomicInteger(0)

    init {
        check(items.mkdirs() || items.isDirectory)
        check(blobs.mkdirs() || blobs.isDirectory)
        pruneUnused()
        migrateUntitledNotes()
    }

    private fun metadata(id: String) = AtomicFile(File(items, "$id.json"))
    private fun blob(hash: String) = File(blobs, hash)

    @Synchronized fun all(): List<BronzeItem> = items.listFiles()?.filter { it.name.endsWith(".json") }
        ?.map { BronzeItem.fromJson(JSONObject(AtomicFile(it).openRead().bufferedReader().use { reader -> reader.readText() })) }
        ?.sortedWith(compareByDescending<BronzeItem> { it.modified }.thenBy { it.id }) ?: emptyList()

    @Synchronized fun get(id: String): BronzeItem? {
        val file = metadata(id)
        return try {
            BronzeItem.fromJson(JSONObject(file.openRead().bufferedReader().use { it.readText() }))
        } catch (_: FileNotFoundException) { null }
    }

    @Synchronized fun content(item: BronzeItem): File = blob(item.hash)

    private fun save(item: BronzeItem) {
        val old = get(item.id)
        val file = metadata(item.id)
        val output = file.startWrite()
        try {
            output.write(item.json(true).toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            if (old != null && (old.hash != item.hash || item.deleted) && activeInstalls.get() == 0) pruneUnused()
        } catch (e: Exception) {
            file.failWrite(output)
            throw e
        }
    }

    @Synchronized private fun pruneUnused() {
        val used = all().filterNot { it.deleted }.map { it.hash }.toSet()
        blobs.listFiles()?.filter { it.name.startsWith(".import-") ||
            (it.name.matches(Regex("[0-9a-f]{64}")) && it.name !in used) }
            ?.forEach { it.delete() }
    }

    private fun storeContent(input: InputStream, expected: BronzeItem? = null): Pair<String, Long> {
        val temp = File.createTempFile(".import-", ".tmp", blobs)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n)
                    digest.update(buffer, 0, n)
                    size += n
                    if (expected != null && size > expected.size) error("Bronze content exceeds declared size")
                }
                output.flush()
                output.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            check(expected == null || (expected.size == size && expected.hash == hash)) { "Bronze content mismatch" }
            val destination = blob(hash)
            if (!destination.exists()) check(temp.renameTo(destination)) { "Could not store Bronze content" }
            return hash to size
        } finally { temp.delete() }
    }

    private fun noteFilename(id: String): String = "note-${id.take(12)}.txt"

    // Earlier builds used a placeholder instead of a filename for empty notes.
    @Synchronized private fun migrateUntitledNotes() {
        all().filter { !it.deleted && it.mime == "text/plain" && it.title == "Note" }.forEach { item ->
            save(item.copy(title = noteFilename(item.id), revision = item.revision + 1,
                modified = maxOf(System.currentTimeMillis(), item.modified + 1)))
        }
    }

    @Synchronized fun createNote(text: String): BronzeItem {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        val (hash, size) = storeContent(text.byteInputStream())
        return BronzeItem(id, 1, hash, false, noteFilename(id), "text/plain", size, now, now).also(::save)
    }

    @Synchronized fun import(uri: Uri): BronzeItem {
        val resolver = context.contentResolver
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.takeIf { it.isNotBlank() } ?: "File"
        val mime = resolver.getType(uri) ?: URLConnection.guessContentTypeFromName(name) ?: "application/octet-stream"
        val (hash, size) = resolver.openInputStream(uri)?.use { storeContent(it) } ?: error("Could not open selected file")
        val now = System.currentTimeMillis()
        return BronzeItem(UUID.randomUUID().toString(), 1, hash, false, name.take(512), mime, size, now, now).also(::save)
    }

    @Synchronized fun editText(id: String, text: String): BronzeItem {
        val old = get(id) ?: error("Bronze item missing")
        check(!old.deleted && old.mime == "text/plain")
        val (hash, size) = storeContent(text.byteInputStream())
        return old.copy(revision = old.revision + 1, hash = hash, size = size,
            modified = maxOf(System.currentTimeMillis(), old.modified + 1)).also(::save)
    }

    @Synchronized fun delete(id: String) {
        val old = get(id) ?: return
        if (!old.deleted) save(old.copy(revision = old.revision + 1, hash = "", size = 0, deleted = true,
            modified = maxOf(System.currentTimeMillis(), old.modified + 1)))
    }

    @Synchronized fun acknowledge(item: BronzeItem) {
        val current = get(item.id) ?: return
        if (current.revision == item.revision && current.hash == item.hash && current.deleted == item.deleted)
            save(current.copy(ackedRevision = current.revision))
    }

    fun install(item: BronzeItem, input: InputStream?) {
        activeInstalls.incrementAndGet()
        try {
            if (!item.deleted) storeContent(input ?: error("Bronze content missing"), item)
            synchronized(this) {
                val current = get(item.id)
                if (current == null || current.revision < item.revision)
                    save(item.copy(ackedRevision = item.revision))
            }
        } finally {
            synchronized(this) {
                if (activeInstalls.decrementAndGet() == 0) pruneUnused()
            }
        }
    }
}
