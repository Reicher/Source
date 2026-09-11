package com.source.client.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.source.client.R
import com.source.client.model.ChatConversation
import com.source.client.model.ConnectedNode
import com.source.client.model.ChatConversations
import com.source.client.protocol.SourceNodeApi
import com.source.client.security.SourceCrypto
import com.source.client.security.VaultSession
import com.source.client.storage.EncryptedBlobStore
import com.source.client.storage.ChatData
import com.source.client.storage.LibraryData
import com.source.client.storage.LibraryItem
import com.source.client.storage.ImportedLibraryBlob
import com.source.client.storage.LibraryManifest
import com.source.client.storage.LibraryTombstone
import com.source.client.storage.SourceDataStore
import com.source.client.storage.SourceDataSync
import java.util.UUID
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class LibrarySyncState { LOCAL, SYNCING, SYNCED, FAILED }

data class LibraryUiItem(
    val id: String,
    val filename: String,
    val byteCount: Long,
    val createdAtMillis: Long,
    val syncState: LibrarySyncState,
    val deletable: Boolean,
)

data class LibraryUiState(
    val items: List<LibraryUiItem> = emptyList(),
    val importing: Boolean = false,
    val feedback: String? = null,
)

internal class LibraryController(
    private val contentResolver: ContentResolver,
    private val blobStore: EncryptedBlobStore,
    private val localStore: SourceDataStore,
    private val nodeApi: SourceNodeApi,
    private val session: () -> VaultSession?,
    private val connectedNode: () -> ConnectedNode?,
    private val message: (Int) -> String,
    private val onStateChanged: (LibraryUiState) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val manifestSync = SourceDataSync(LibraryData, localStore, nodeApi)
    private var manifest = LibraryManifest()
    private var syncStates = emptyMap<String, LibrarySyncState>()
    private val acknowledgedTombstones = mutableSetOf<String>()

    var state = LibraryUiState()
        private set

    suspend fun reset(activeSession: VaultSession? = null) = mutex.withLock {
        manifestSync.reset()
        acknowledgedTombstones.clear()
        manifest = if (activeSession == null) {
            LibraryManifest()
        } else {
            withContext(Dispatchers.Default) { localStore.load(activeSession, LibraryData) }
        }
        if (activeSession != null) {
            withContext(Dispatchers.IO) {
                blobStore.cleanup(activeSession, manifest.items.mapTo(mutableSetOf(), LibraryItem::id))
            }
        }
        syncStates = manifest.items.associate { it.id to LibrarySyncState.LOCAL }
        publish()
    }

    fun clearFeedback() {
        if (state.feedback != null) publish(feedback = null)
    }

    suspend fun import(uri: Uri): Boolean = mutex.withLock {
        val activeSession = session() ?: return@withLock false
        publish(importing = true, feedback = null)
        var imported: ImportedLibraryBlob? = null
        var committed = false
        try {
            val metadata = withContext(Dispatchers.IO) { readMetadata(uri) }
            imported = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(uri)?.use { input -> blobStore.importFile(activeSession, input) }
                    ?: throw IllegalArgumentException("The selected file could not be opened")
            }
            val importedBlob = checkNotNull(imported)
            val duplicate = manifest.items.firstOrNull { it.contentSha256 == importedBlob.contentSha256 }
            if (duplicate != null) {
                withContext(Dispatchers.IO) { blobStore.discardImported(activeSession, importedBlob.id) }
                publish(importing = false, feedback = message(R.string.library_item_already_exists))
                return@withLock false
            }
            val now = clock().coerceAtLeast(1)
            val item = LibraryItem(
                id = importedBlob.id,
                name = metadata.name,
                mimeType = metadata.mimeType,
                byteCount = importedBlob.byteCount,
                createdAtMillis = now,
                contentSha256 = importedBlob.contentSha256,
            )
            manifest = manifest.copy(
                items = manifest.items + item,
                modifiedAtMillis = nextModifiedAt(now),
            )
            syncStates = syncStates + (item.id to LibrarySyncState.LOCAL)
            manifestSync.changed()
            manifestSync.persist(activeSession, manifest)
            committed = true
            publish(importing = false, feedback = message(R.string.library_item_added))
            true
        } catch (error: CancellationException) {
            publish(importing = false)
            throw error
        } catch (_: Exception) {
            if (!committed) imported?.let { blob ->
                withContext(Dispatchers.IO) { blobStore.discardImported(activeSession, blob.id) }
            }
            publish(importing = false, feedback = message(R.string.error_library_import_failed))
            false
        }
    }

    suspend fun delete(itemId: String) = mutex.withLock {
        val activeSession = session() ?: return@withLock
        val item = manifest.items.firstOrNull { it.id == itemId } ?: return@withLock
        val now = clock().coerceAtLeast(1)
        val tombstone = LibraryTombstone(item.id, item.contentSha256, now)
        manifest = manifest.copy(
            items = manifest.items.filterNot { it.id == item.id },
            tombstones = manifest.tombstones.filterNot { it.itemId == item.id } + tombstone,
            modifiedAtMillis = nextModifiedAt(now),
        )
        syncStates = syncStates - item.id
        manifestSync.changed()
        manifestSync.persist(activeSession, manifest)
        withContext(Dispatchers.IO) { runCatching { blobStore.delete(activeSession, item.id) } }
        publish(feedback = message(R.string.library_item_deleted))
    }

    suspend fun syncAll(resetAcknowledgements: Boolean = false) = mutex.withLock {
        val activeSession = session() ?: return@withLock
        val connected = connectedNode() ?: return@withLock
        val encodedDataKey = connected.trusted.dataKey ?: return@withLock
        if (resetAcknowledgements) {
            acknowledgedTombstones.clear()
            syncStates = manifest.items.associate { it.id to LibrarySyncState.LOCAL }
            publish()
        }

        manifest.tombstones.filterNot { it.itemId in acknowledgedTombstones }.forEach { tombstone ->
            try {
                nodeApi.deleteLibraryItem(
                    connected.discovered.apiBaseUrl,
                    connected.trusted,
                    tombstone.itemId,
                    tombstone.contentSha256,
                )
                acknowledgedTombstones += tombstone.itemId
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // The durable local tombstone remains queued for the next heartbeat.
            }
        }

        manifest.items.forEach { item ->
            if (syncStates[item.id] == LibrarySyncState.SYNCED) return@forEach
            if (!blobStore.exists(activeSession, item.id)) {
                syncStates = syncStates + (item.id to LibrarySyncState.FAILED)
                publish()
                return@forEach
            }
            syncStates = syncStates + (item.id to LibrarySyncState.SYNCING)
            publish()
            val dataKey = SourceCrypto.base64UrlDecode(encodedDataKey)
            try {
                withContext(Dispatchers.IO) {
                    blobStore.createUploadPayload(activeSession, item, dataKey).use { payload ->
                        nodeApi.uploadLibraryItem(
                            connected.discovered.apiBaseUrl,
                            connected.trusted,
                            item,
                            payload,
                        )
                    }
                }
                syncStates = syncStates + (item.id to LibrarySyncState.SYNCED)
            } catch (error: CancellationException) {
                syncStates = syncStates + (item.id to LibrarySyncState.LOCAL)
                publish()
                throw error
            } catch (_: Exception) {
                syncStates = syncStates + (item.id to LibrarySyncState.FAILED)
            } finally {
                dataKey.fill(0)
            }
            publish()
        }
        manifestSync.backupIfNeeded(activeSession, connected, manifest)
    }

    private fun readMetadata(uri: Uri): SelectedFileMetadata {
        var displayName: String? = null
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) displayName = cursor.getString(0)
        }
        val safeName = displayName.orEmpty()
            .filterNot(Char::isISOControl)
            .trim()
            .take(255)
            .ifBlank { "file-${UUID.randomUUID()}" }
        val mimeType = contentResolver.getType(uri)
            ?.filterNot(Char::isISOControl)
            ?.take(200)
            ?.takeIf(String::isNotBlank)
            ?: "application/octet-stream"
        return SelectedFileMetadata(safeName, mimeType)
    }

    private fun nextModifiedAt(now: Long) = maxOf(now, manifest.modifiedAtMillis + 1)

    private fun publish(
        importing: Boolean = state.importing,
        feedback: String? = state.feedback,
    ) {
        state = LibraryUiState(
            items = manifest.items
                .sortedByDescending(LibraryItem::createdAtMillis)
                .map { item ->
                    LibraryUiItem(
                        id = item.id,
                        filename = item.name,
                        byteCount = item.byteCount,
                        createdAtMillis = item.createdAtMillis,
                        syncState = syncStates[item.id] ?: LibrarySyncState.LOCAL,
                        deletable = true,
                    )
                },
            importing = importing,
            feedback = feedback,
        )
        onStateChanged(state)
    }

    private data class SelectedFileMetadata(val name: String, val mimeType: String)
}

internal fun withConversationLibraryItems(
    rawLibrary: LibraryUiState,
    conversations: ChatConversations,
    conversationByteCount: (ChatConversation) -> Long = ChatData::encodedConversationByteCount,
): LibraryUiState = rawLibrary.copy(
    items = (
        rawLibrary.items +
            conversations.conversations.map { conversation ->
                LibraryUiItem(
                    id = "conversation:${conversation.id}",
                    filename = conversationFilename(conversation.createdAtMillis),
                    byteCount = conversationByteCount(conversation),
                    createdAtMillis = conversation.createdAtMillis,
                    syncState = LibrarySyncState.LOCAL,
                    deletable = false,
                )
            }
        ).sortedByDescending(LibraryUiItem::createdAtMillis),
)

private val conversationFilenameFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC)

internal fun conversationFilename(createdAtMillis: Long): String =
    "conversation-${conversationFilenameFormat.format(Instant.ofEpochMilli(createdAtMillis))}.json"
