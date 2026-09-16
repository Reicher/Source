package com.source.client.ui

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.source.client.R
import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.SILVER_ENTITY_TYPE_PREDICATE
import com.source.client.knowledge.SILVER_EXTRACTION_COMPLETE_KIND
import com.source.client.knowledge.SILVER_NAME_PREDICATE
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
import com.source.client.storage.SilverClaimState
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverJsonValue
import com.source.client.storage.SourceDataStore
import com.source.client.storage.SourceDataSync
import java.util.UUID
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class BronzeNodeStatus { NOT_ON_NODE, UPLOADING, STORED }

enum class KnowledgeStatus { WAITING, PROCESSING, SYNCING_TO_CLIENT, CURRENT, ERROR }

enum class LibraryPreviewKind { TEXT }

sealed interface LibraryPreviewContent {
    data class Text(val value: String) : LibraryPreviewContent
}

data class LibraryPreviewData(
    val filename: String,
    val content: LibraryPreviewContent,
)

data class SilverKnowledgeSummary(
    val entityCount: Int,
    val factCount: Int,
) {
    init {
        require(entityCount >= 0 && factCount >= 0)
    }
}

data class LibraryUiItem(
    val id: String,
    val filename: String,
    val sourceType: String,
    val mimeType: String,
    val byteCount: Long,
    val createdAtMillis: Long,
    val bronzeContentSha256: String,
    val bronzeStatus: BronzeNodeStatus,
    val knowledgeStatus: KnowledgeStatus = KnowledgeStatus.WAITING,
    val knowledgeProgress: SilverBatchProgress? = null,
    val knowledgeSummary: SilverKnowledgeSummary? = null,
    val localAvailable: Boolean,
    val canRemoveFromDevice: Boolean,
    val canDeleteFromSource: Boolean,
    val previewKind: LibraryPreviewKind?,
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
    private var uploading = emptySet<String>()
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
        uploading = emptySet()
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
            require(isSupportedText(metadata.mimeType, metadata.name)) { "Only supported text files can be imported" }
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
            require(item.byteCount <= MAXIMUM_TEXT_PREVIEW_BYTES) { "The text file is too large" }
            withContext(Dispatchers.IO) {
                decodeUtf8(blobStore.readPreview(activeSession, item, MAXIMUM_TEXT_PREVIEW_BYTES))
            }
            manifest = manifest.copy(
                items = manifest.items + item,
                modifiedAtMillis = nextModifiedAt(now),
            )
            uploading = uploading - item.id
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

    suspend fun removeFromDevice(itemId: String) = mutex.withLock {
        val activeSession = session() ?: return@withLock
        val item = manifest.items.firstOrNull { it.id == itemId } ?: return@withLock
        if (!item.nodeStored || !blobStore.exists(activeSession, item.id)) return@withLock
        withContext(Dispatchers.IO) { blobStore.delete(activeSession, item.id) }
        uploading = uploading - item.id
        publish(feedback = message(R.string.library_item_removed_from_device))
    }

    suspend fun deleteFromSource(itemId: String) = mutex.withLock {
        val activeSession = session() ?: return@withLock
        val item = manifest.items.firstOrNull { it.id == itemId } ?: return@withLock
        val now = clock().coerceAtLeast(1)
        val tombstone = LibraryTombstone(item.id, item.contentSha256, now)
        manifest = manifest.copy(
            items = manifest.items.filterNot { it.id == item.id },
            tombstones = manifest.tombstones.filterNot { it.itemId == item.id } + tombstone,
            modifiedAtMillis = nextModifiedAt(now),
        )
        uploading = uploading - item.id
        manifestSync.changed()
        manifestSync.persist(activeSession, manifest)
        withContext(Dispatchers.IO) { runCatching { blobStore.delete(activeSession, item.id) } }
        publish(feedback = message(R.string.library_item_deleted_from_source))
    }

    suspend fun readPreview(itemId: String): LibraryPreviewData? = mutex.withLock {
        val activeSession = session() ?: return@withLock null
        val item = manifest.items.firstOrNull { it.id == itemId } ?: return@withLock null
        if (!blobStore.exists(activeSession, item.id)) return@withLock null
        val kind = previewKind(item.mimeType, item.name) ?: return@withLock null
        val maximumBytes = MAXIMUM_TEXT_PREVIEW_BYTES
        val bytes = withContext(Dispatchers.IO) { blobStore.readPreview(activeSession, item, maximumBytes) }
        LibraryPreviewData(
            filename = item.name,
            content = when (kind) {
                LibraryPreviewKind.TEXT -> LibraryPreviewContent.Text(decodeUtf8(bytes))
            },
        )
    }

    suspend fun bronzeTextSources(): List<BronzeTextSource> = mutex.withLock {
        val activeSession = session() ?: return@withLock emptyList()
        manifest.items.mapNotNull { item ->
            if (!item.nodeStored || !isSupportedText(item.mimeType, item.name)) return@mapNotNull null
            val text = if (blobStore.exists(activeSession, item.id) && item.byteCount <= MAXIMUM_TEXT_PREVIEW_BYTES) {
                runCatching {
                    withContext(Dispatchers.IO) {
                        decodeUtf8(blobStore.readPreview(activeSession, item, MAXIMUM_TEXT_PREVIEW_BYTES))
                    }
                }.getOrNull().orEmpty()
            } else {
                ""
            }
            BronzeTextSource(item.id, item.name, item.sourceType, item.contentSha256, text)
        }
    }

    suspend fun syncAll(resetAcknowledgements: Boolean = false) = mutex.withLock {
        val activeSession = session() ?: return@withLock
        val connected = connectedNode() ?: return@withLock
        val encodedDataKey = connected.trusted.dataKey ?: return@withLock
        if (resetAcknowledgements) {
            acknowledgedTombstones.clear()
            uploading = emptySet()
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

        var nodeStatusChanged = false
        manifest.items.toList().forEach { item ->
            if (!blobStore.exists(activeSession, item.id)) {
                uploading = uploading - item.id
                publish()
                return@forEach
            }
            if (item.nodeStored) {
                uploading = uploading - item.id
                return@forEach
            }
            uploading = uploading + item.id
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
                val now = clock().coerceAtLeast(1)
                manifest = manifest.copy(
                    items = manifest.items.map { current ->
                        if (current.id == item.id) current.copy(nodeStored = true) else current
                    },
                    modifiedAtMillis = nextModifiedAt(now),
                )
                manifestSync.changed()
                nodeStatusChanged = true
                uploading = uploading - item.id
            } catch (error: CancellationException) {
                uploading = uploading - item.id
                publish()
                throw error
            } catch (_: Exception) {
                uploading = uploading - item.id
            } finally {
                dataKey.fill(0)
            }
            publish()
        }
        if (nodeStatusChanged) manifestSync.persist(activeSession, manifest)
        manifestSync.backupIfNeeded(activeSession, connected, manifest, ::applySynchronizedManifest)
    }

    private fun applySynchronizedManifest(synchronized: LibraryManifest) {
        manifest = synchronized
        val retainedItems = manifest.items.mapTo(mutableSetOf(), LibraryItem::id)
        uploading = uploading.filterTo(mutableSetOf(), retainedItems::contains)
        publish()
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
                    val activeSession = session()
                    val localAvailable = activeSession != null && blobStore.exists(activeSession, item.id)
                    LibraryUiItem(
                        id = item.id,
                        filename = item.name,
                        sourceType = item.sourceType,
                        mimeType = item.mimeType,
                        byteCount = item.byteCount,
                        createdAtMillis = item.createdAtMillis,
                        bronzeContentSha256 = item.contentSha256,
                        bronzeStatus = when {
                            item.nodeStored -> BronzeNodeStatus.STORED
                            item.id in uploading -> BronzeNodeStatus.UPLOADING
                            else -> BronzeNodeStatus.NOT_ON_NODE
                        },
                        localAvailable = localAvailable,
                        canRemoveFromDevice = localAvailable && item.nodeStored,
                        canDeleteFromSource = true,
                        previewKind = if (localAvailable) previewKind(item.mimeType, item.name) else null,
                    )
                },
            importing = importing,
            feedback = feedback,
        )
        onStateChanged(state)
    }

    private data class SelectedFileMetadata(val name: String, val mimeType: String)
}

internal fun previewKind(mimeType: String, filename: String): LibraryPreviewKind? {
    return if (isSupportedText(mimeType, filename)) LibraryPreviewKind.TEXT else null
}

internal fun isSupportedText(mimeType: String, filename: String): Boolean {
    val normalizedMime = mimeType.lowercase()
    val extension = filename.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return normalizedMime.startsWith("text/") ||
        normalizedMime in setOf("application/json", "application/xml", "application/yaml", "application/x-yaml") ||
        (normalizedMime == "application/octet-stream" && extension in SUPPORTED_TEXT_EXTENSIONS)
}

private fun decodeUtf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT)
    .onUnmappableCharacter(CodingErrorAction.REPORT)
    .decode(ByteBuffer.wrap(bytes))
    .toString()

internal fun withConversationLibraryItems(
    rawLibrary: LibraryUiState,
    conversations: ChatConversations,
    conversationByteCount: (ChatConversation) -> Long = ChatData::encodedConversationByteCount,
    conversationsBackedUp: Boolean = false,
): LibraryUiState = rawLibrary.copy(
    items = (
        rawLibrary.items +
            conversations.conversations.map { conversation ->
                LibraryUiItem(
                    id = "conversation:${conversation.id}",
                    filename = conversationFilename(conversation.createdAtMillis),
                    sourceType = "conversation",
                    mimeType = "application/json",
                    byteCount = conversationByteCount(conversation),
                    createdAtMillis = conversation.createdAtMillis,
                    bronzeContentSha256 = ChatData.refinementContentSha256(conversation),
                    bronzeStatus = if (conversationsBackedUp) BronzeNodeStatus.STORED else BronzeNodeStatus.NOT_ON_NODE,
                    localAvailable = true,
                    canRemoveFromDevice = false,
                    canDeleteFromSource = true,
                    previewKind = LibraryPreviewKind.TEXT,
                )
            }
        ).sortedByDescending(LibraryUiItem::createdAtMillis),
)

internal fun withSilverState(library: LibraryUiState, silver: SilverUiState): LibraryUiState = library.copy(
    items = library.items.map { item ->
        val matchingEvidence = silver.dataset.evidence.filter { evidence ->
            evidence.bronzeSourceId == item.id && evidence.bronzeContentSha256 == item.bronzeContentSha256
        }.mapTo(mutableSetOf()) { it.id }
        val currentSilver = matchingEvidence.isNotEmpty() && silver.dataset.observations.any { observation ->
            observation.kind == SILVER_EXTRACTION_COMPLETE_KIND &&
                observation.evidenceIds.any(matchingEvidence::contains)
        }
        val job = silver.jobs[item.id]?.takeIf { it.sourceContentSha256 == item.bronzeContentSha256 }
        item.copy(
            knowledgeStatus = when {
                currentSilver -> KnowledgeStatus.CURRENT
                item.id in silver.syncErrors || job?.state == "failed" -> KnowledgeStatus.ERROR
                job?.state == "completed" -> KnowledgeStatus.SYNCING_TO_CLIENT
                job?.state == "running" -> KnowledgeStatus.PROCESSING
                else -> KnowledgeStatus.WAITING
            },
            knowledgeProgress = job?.takeIf { it.state == "running" }?.let {
                SilverBatchProgress(it.completedBatches, it.totalBatches)
            },
            knowledgeSummary = silverKnowledgeSummary(silver.dataset, matchingEvidence),
        )
    },
)

private data class SilverFactIdentity(
    val subjectEntityId: String,
    val predicate: String,
    val objectEntityId: String?,
    val value: SilverJsonValue?,
)

private fun silverKnowledgeSummary(
    dataset: SilverDataset,
    evidenceIds: Set<String>,
): SilverKnowledgeSummary? {
    if (evidenceIds.isEmpty()) return null
    val observationIds = dataset.observations.asSequence()
        .filter { observation -> observation.evidenceIds.any(evidenceIds::contains) }
        .mapTo(mutableSetOf()) { it.id }
    val sourceClaims = dataset.claims.filter { claim ->
        claim.state == SilverClaimState.ACTIVE && claim.supportingObservationIds.any(observationIds::contains)
    }
    val entityIds = sourceClaims.flatMapTo(mutableSetOf()) { claim ->
        listOfNotNull(claim.subjectEntityId, claim.objectEntityId)
    }
    val facts = sourceClaims.asSequence()
        .filterNot { it.predicate == SILVER_NAME_PREDICATE || it.predicate == SILVER_ENTITY_TYPE_PREDICATE }
        .distinctBy { claim ->
            SilverFactIdentity(
                claim.subjectEntityId,
                claim.predicate,
                claim.objectEntityId,
                claim.value,
            )
        }
        .count()
    return SilverKnowledgeSummary(entityIds.size, facts)
}

private val conversationFilenameFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC)

internal fun conversationFilename(createdAtMillis: Long): String =
    "conversation-${conversationFilenameFormat.format(Instant.ofEpochMilli(createdAtMillis))}.json"

private const val MAXIMUM_TEXT_PREVIEW_BYTES = 2L * 1024 * 1024
internal val SUPPORTED_TEXT_MIME_TYPES = arrayOf("text/*", "application/json", "application/xml", "application/yaml")
private val SUPPORTED_TEXT_EXTENSIONS = setOf("txt", "json", "md", "csv", "log", "xml", "yaml", "yml")
