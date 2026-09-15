package com.source.client.storage

import com.source.client.model.ConnectedNode
import com.source.client.protocol.SourceApiException
import com.source.client.protocol.SourceNodeApi
import com.source.client.security.SourceCrypto
import com.source.client.security.VaultSession
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stores local data and incrementally moves supported datasets onto canonical sync. */
class SourceDataSync<T>(
    private val data: SourceData<T>,
    private val localStore: SourceDataStore,
    private val nodeApi: SourceNodeApi,
) {
    private val mutex = Mutex()
    private val localRevisionGate = Any()
    @Volatile private var backupDirty = true
    private val localRevision = AtomicLong(0)
    private var canonicalState: CanonicalSyncState? = null
    @Volatile private var explicitlyChanged = false

    var recoveryRestorePending = false
        private set

    @Volatile
    var lastError: Exception? = null
        private set

    val isBackedUp: Boolean
        get() = !backupDirty

    fun reset() {
        synchronized(localRevisionGate) {
            backupDirty = true
            recoveryRestorePending = false
            localRevision.incrementAndGet()
            canonicalState = null
            explicitlyChanged = false
            lastError = null
        }
    }

    fun changed() {
        synchronized(localRevisionGate) {
            localRevision.incrementAndGet()
            backupDirty = true
            explicitlyChanged = true
        }
    }

    fun requireBackup() {
        backupDirty = true
        explicitlyChanged = true
    }

    fun beginRecoveryRestore() {
        recoveryRestorePending = true
    }

    suspend fun persist(session: VaultSession, value: T) {
        mutex.withLock {
            if (data.descriptor.canonicalCollection == null || !backupDirty) {
                withContext(Dispatchers.Default) { localStore.save(session, data, value) }
                return@withLock
            }
            val trusted = session.vault.authoritativeNode
            val state = state(session)
            val queued = if (trusted == null) state else queueMutation(state, trusted.userId, value)
            canonicalState = queued
            if (queued != state) explicitlyChanged = false
            withContext(Dispatchers.Default) {
                if (queued == state) localStore.save(session, data, value)
                else localStore.saveWithSyncState(session, data, value, queued)
            }
        }
    }

    suspend fun backupIfNeeded(
        session: VaultSession?,
        connected: ConnectedNode?,
        value: T,
        applyRemote: (T) -> Unit,
    ) = mutex.withLock {
        if (data.descriptor.canonicalCollection == null) {
            legacyBackupLocked(session, connected, value)
        } else {
            canonicalSynchronizeLocked(session, connected, value, applyRemote)
        }
    }

    suspend fun synchronize(
        session: VaultSession?,
        connected: ConnectedNode?,
        localValue: T,
        applyRemote: (T) -> Unit,
    ) = mutex.withLock {
        if (data.descriptor.canonicalCollection == null) {
            legacySynchronizeLocked(session, connected, localValue, applyRemote)
        } else {
            canonicalSynchronizeLocked(session, connected, localValue, applyRemote)
        }
    }

    suspend fun restoreRecovered(session: VaultSession?, connected: ConnectedNode?): T? = mutex.withLock {
        if (session == null || connected == null) return@withLock null
        if (data.descriptor.canonicalCollection == null) {
            return@withLock legacyRestoreRecovered(session, connected)
        }
        try {
            val collection = checkNotNull(data.descriptor.canonicalCollection)
            val objectId = canonicalObjectId(collection)
            var changes = readChanges(connected, collection, objectId, null)
            val restored = when {
                changes.heads.isEmpty() -> legacyRemoteValue(session, connected)
                else -> reconcileHeads(connected, changes.heads)
                    ?: throw SourceApiException("storage_conflict", "Recovery found unresolved concurrent revisions.")
            }
            val previous = state(session)
            var state = CanonicalSyncState(
                originEpoch = previous.originEpoch,
                nextOriginSequence = previous.nextOriginSequence,
                cursor = changes.cursor,
                heads = changes.heads.map(StorageRevision::revisionId).sorted(),
            )
            canonicalState = state
            if (restored != null) {
                localRevision.incrementAndGet()
                if (changes.heads.isEmpty() || changes.heads.size > 1) {
                    state = queueMutation(
                        state, connected.trusted.userId, restored,
                        force = changes.heads.size > 1,
                    )
                    canonicalState = state
                }
                withContext(Dispatchers.Default) { localStore.saveWithSyncState(session, data, restored, state) }
            } else {
                withContext(Dispatchers.Default) { localStore.saveSyncState(session, data, state) }
            }
            if (state.pending.isNotEmpty()) {
                state = replayPending(session, connected, state)
                changes = readChanges(connected, collection, objectId, state.cursor)
                state = state.copy(
                    cursor = changes.cursor,
                    heads = changes.heads.map(StorageRevision::revisionId).sorted(),
                )
                canonicalState = state
                withContext(Dispatchers.Default) { localStore.saveSyncState(session, data, state) }
            }
            nodeApi.acknowledgeStorageCursor(
                connected.discovered.apiBaseUrl, connected.trusted, collection,
                objectId, changes.cursor,
            )
            backupDirty = state.pending.isNotEmpty() || changes.heads.size > 1
            recoveryRestorePending = false
            lastError = null
            restored
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            backupDirty = true
            throw error
        }
    }

    private suspend fun canonicalSynchronizeLocked(
        session: VaultSession?,
        connected: ConnectedNode?,
        initialValue: T,
        applyRemote: (T) -> Unit,
    ) {
        if (session == null || connected == null) return
        lastError = null
        var localValue = initialValue
        val initialIdentity = data.version(initialValue).contentIdentity
        val revisionBeforeSync = localRevision.get()
        try {
            val collection = checkNotNull(data.descriptor.canonicalCollection)
            val objectId = canonicalObjectId(collection)
            var state = state(session)
            var remote = readChanges(connected, collection, objectId, state.cursor)
            requireLocalRevision(revisionBeforeSync)

            val epochChanged = state.cursor != null && (
                state.cursor.authorityNodeId != remote.cursor.authorityNodeId ||
                    state.cursor.authorityEpoch != remote.cursor.authorityEpoch
                )
            val initialManifest = state.cursor == null && remote.requiresManifest &&
                state.pending.isEmpty() && remote.heads.isNotEmpty()
            if (initialManifest) {
                val authorityValue = reconcileHeads(connected, remote.heads)
                    ?: throw SourceApiException("storage_conflict", "The Node manifest contains unresolved revisions.")
                requireLocalRevision(revisionBeforeSync)
                val emptyIdentity = data.version(data.emptyValue).contentIdentity
                val localIdentity = data.version(localValue).contentIdentity
                val authorityIdentity = data.version(authorityValue).contentIdentity
                state = state.copy(
                    cursor = remote.cursor,
                    heads = remote.heads.map(StorageRevision::revisionId).sorted(),
                )
                if (localIdentity != emptyIdentity && localIdentity != authorityIdentity) {
                    localValue = data.merge(authorityValue, localValue)
                        ?: throw SourceApiException("storage_conflict", "Existing local data conflicts with the Node manifest.")
                    if (data.version(localValue).contentIdentity != authorityIdentity) {
                        state = queueMutation(state, connected.trusted.userId, localValue)
                        canonicalState = state
                        requireLocalRevision(revisionBeforeSync)
                        withContext(Dispatchers.Default) {
                            localStore.saveWithSyncState(session, data, localValue, state)
                        }
                        requireLocalRevision(revisionBeforeSync)
                    }
                }
            } else if (epochChanged) {
                val authorityValue = if (remote.heads.isEmpty()) null else {
                    reconcileHeads(connected, remote.heads)
                        ?: throw SourceApiException("storage_conflict", "The restored Node history contains unresolved revisions.")
                }
                requireLocalRevision(revisionBeforeSync)
                localValue = when {
                    authorityValue == null -> localValue
                    data.version(authorityValue).contentIdentity == data.version(localValue).contentIdentity -> authorityValue
                    else -> data.merge(authorityValue, localValue)
                        ?: throw SourceApiException("storage_conflict", "Local changes conflict with the restored Node history.")
                }
                state.pending.forEach { it.payloadBytes.fill(0) }
                state = state.copy(
                    originEpoch = UUID.randomUUID().toString(),
                    nextOriginSequence = 1,
                    cursor = remote.cursor,
                    heads = remote.heads.map(StorageRevision::revisionId).sorted(),
                    pending = emptyList(),
                    receipts = emptyList(),
                )
                val emptyIdentity = data.version(data.emptyValue).contentIdentity
                val authorityIdentity = authorityValue?.let { data.version(it).contentIdentity }
                if (data.version(localValue).contentIdentity != authorityIdentity &&
                    data.version(localValue).contentIdentity != emptyIdentity
                ) {
                    state = queueMutation(state, connected.trusted.userId, localValue)
                }
                canonicalState = state
                requireLocalRevision(revisionBeforeSync)
                withContext(Dispatchers.Default) { localStore.saveWithSyncState(session, data, localValue, state) }
                requireLocalRevision(revisionBeforeSync)
            } else if (explicitlyChanged && state.pending.isEmpty()) {
                state = queueMutation(state, connected.trusted.userId, localValue)
                canonicalState = state
                explicitlyChanged = false
                requireLocalRevision(revisionBeforeSync)
                withContext(Dispatchers.Default) { localStore.saveWithSyncState(session, data, localValue, state) }
                requireLocalRevision(revisionBeforeSync)
            }

            state = replayPending(session, connected, state)
            requireLocalRevision(revisionBeforeSync)
            remote = readChanges(connected, collection, objectId, state.cursor)
            requireLocalRevision(revisionBeforeSync)

            if (remote.heads.isEmpty()) {
                val migrated = legacyRemoteValue(session, connected)
                requireLocalRevision(revisionBeforeSync)
                val emptyIdentity = data.version(data.emptyValue).contentIdentity
                localValue = when {
                    migrated == null -> localValue
                    data.version(localValue).contentIdentity == emptyIdentity -> migrated
                    data.version(localValue).contentIdentity == data.version(migrated).contentIdentity -> localValue
                    else -> data.merge(localValue, migrated)
                        ?: throw SourceApiException("storage_conflict", "Legacy Node data conflicts with local data.")
                }
                if (data.version(localValue).contentIdentity != emptyIdentity) {
                    state = state.copy(cursor = remote.cursor, heads = emptyList())
                    state = queueMutation(state, connected.trusted.userId, localValue)
                    canonicalState = state
                    requireLocalRevision(revisionBeforeSync)
                    withContext(Dispatchers.Default) { localStore.saveWithSyncState(session, data, localValue, state) }
                    requireLocalRevision(revisionBeforeSync)
                    state = replayPending(session, connected, state)
                    requireLocalRevision(revisionBeforeSync)
                    remote = readChanges(connected, collection, objectId, state.cursor)
                    requireLocalRevision(revisionBeforeSync)
                }
            }

            if (remote.heads.size > 1) {
                val merged = reconcileHeads(connected, remote.heads)
                    ?: throw SourceApiException("storage_conflict", "Concurrent storage revisions require explicit resolution.")
                requireLocalRevision(revisionBeforeSync)
                state = state.copy(
                    cursor = remote.cursor,
                    heads = remote.heads.map(StorageRevision::revisionId).sorted(),
                )
                state = queueMutation(state, connected.trusted.userId, merged, force = true)
                canonicalState = state
                requireLocalRevision(revisionBeforeSync)
                withContext(Dispatchers.Default) { localStore.saveWithSyncState(session, data, merged, state) }
                requireLocalRevision(revisionBeforeSync)
                state = replayPending(session, connected, state)
                requireLocalRevision(revisionBeforeSync)
                remote = readChanges(connected, collection, objectId, state.cursor)
                requireLocalRevision(revisionBeforeSync)
                if (remote.heads.size != 1) {
                    throw SourceApiException("storage_conflict", "Concurrent storage revisions could not be merged.")
                }
                localValue = merged
            }

            if (remote.heads.size == 1) {
                val remoteValue = if (remote.heads.single().kind == "tombstone") {
                    data.emptyValue
                } else {
                    readRevision(connected, remote.heads.single())
                }
                requireLocalRevision(revisionBeforeSync)
                if (data.version(remoteValue).contentIdentity != data.version(localValue).contentIdentity) {
                    localValue = remoteValue
                }
            }
            state = state.copy(
                cursor = remote.cursor,
                heads = remote.heads.map(StorageRevision::revisionId).sorted(),
                pending = emptyList(),
            )
            canonicalState = state
            requireLocalRevision(revisionBeforeSync)
            withContext(Dispatchers.Default) { localStore.saveWithSyncState(session, data, localValue, state) }
            requireLocalRevision(revisionBeforeSync)
            val publishedRevision = publishRemoteIfUnchanged(
                revisionBeforeSync,
                localValue,
                data.version(localValue).contentIdentity != initialIdentity,
                applyRemote,
            )
            nodeApi.acknowledgeStorageCursor(
                connected.discovered.apiBaseUrl, connected.trusted, collection, objectId, remote.cursor,
            )
            backupDirty = localRevision.get() != publishedRevision ||
                state.pending.isNotEmpty() || remote.heads.size > 1
            lastError = null
        } catch (error: CancellationException) {
            throw error
        } catch (_: LocalDataChangedDuringSync) {
            lastError = null
            backupDirty = true
        } catch (error: Exception) {
            lastError = error
            backupDirty = true
        }
    }

    private suspend fun replayPending(
        session: VaultSession,
        connected: ConnectedNode,
        initial: CanonicalSyncState,
    ): CanonicalSyncState {
        var state = initial
        while (state.pending.isNotEmpty()) {
            val mutation = state.pending.first()
            val (receipt, heads) = nodeApi.commitStorageMutation(
                connected.discovered.apiBaseUrl,
                connected.trusted,
                mutation,
            )
            validateHeads(connected, mutation.revision.objectKey.collection, mutation.revision.objectKey.objectId, heads)
            require(
                receipt.authorityNodeId == connected.trusted.nodeId &&
                    receipt.operationId == mutation.operationId &&
                    receipt.revisionId == mutation.revision.revisionId
            )
            state = state.copy(
                heads = heads.map(StorageRevision::revisionId).sorted(),
                pending = state.pending.drop(1),
                receipts = (state.receipts + receipt).distinctBy(StorageCommitReceipt::revisionId),
            )
            mutation.payloadBytes.fill(0)
            canonicalState = state
            withContext(Dispatchers.Default) { localStore.saveSyncState(session, data, state) }
        }
        return state
    }

    private fun queueMutation(
        state: CanonicalSyncState,
        profileId: String,
        value: T,
        force: Boolean = false,
    ): CanonicalSyncState {
        val payload = data.encode(value)
        val payloadForJournal = payload.copyOf()
        payload.fill(0)
        val last = state.pending.lastOrNull()
        if (!force && last?.revision?.payload?.plaintextSha256 == sha256Hex(payloadForJournal)) {
            payloadForJournal.fill(0)
            return state
        }
        val parents = last?.let { listOf(it.revision.revisionId) } ?: state.heads
        val revision = contentRevision(
            profileId,
            data.descriptor,
            parents,
            payloadForJournal,
            System.currentTimeMillis(),
        )
        val mutation = StorageMutation(
            operationId = UUID.randomUUID().toString(),
            originEpoch = state.originEpoch,
            originSequence = state.nextOriginSequence,
            expectedAuthorityEpoch = state.cursor?.authorityEpoch,
            revision = revision,
            payloadBytes = payloadForJournal,
        )
        return state.copy(
            nextOriginSequence = state.nextOriginSequence + 1,
            pending = state.pending + mutation,
        )
    }

    private suspend fun reconcileHeads(connected: ConnectedNode, heads: List<StorageRevision>): T? {
        if (heads.isEmpty()) return null
        if (heads.any { it.kind != "content" }) {
            if (heads.size == 1 && heads.single().kind == "tombstone") return data.emptyValue
            return null
        }
        val values = heads.sortedBy(StorageRevision::revisionId).map { readRevision(connected, it) }
        var merged: T = values.first()
        values.drop(1).forEach { next ->
            merged = data.merge(merged, next) ?: return null
        }
        return merged
    }

    private suspend fun readChanges(
        connected: ConnectedNode,
        collection: String,
        objectId: String,
        cursor: StorageCursor?,
    ): StorageChanges = nodeApi.storageChanges(
        connected.discovered.apiBaseUrl,
        connected.trusted,
        collection,
        objectId,
        cursor,
    ).also { changes ->
        require(changes.cursor.authorityNodeId == connected.trusted.nodeId) {
            "The synchronization authority does not match the paired Node"
        }
        validateHeads(connected, collection, objectId, changes.heads)
        changes.changes.forEach { change ->
            validateHeads(connected, collection, objectId, listOf(change.revision))
            require(
                change.receipt.authorityNodeId == connected.trusted.nodeId &&
                    change.receipt.revisionId == change.revision.revisionId
            )
        }
    }

    private fun validateHeads(
        connected: ConnectedNode,
        collection: String,
        objectId: String,
        revisions: List<StorageRevision>,
    ) {
        require(revisions.all { revision ->
            revision.objectKey.profileId == connected.trusted.userId &&
                revision.objectKey.collection == collection && revision.objectKey.objectId == objectId
        }) { "The Node returned storage data from a different profile or scope" }
    }

    private suspend fun readRevision(connected: ConnectedNode, revision: StorageRevision): T {
        val payload = checkNotNull(revision.payload)
        if (payload.format != data.descriptor.snapshotFormat || payload.formatVersion !in data.supportedFormatVersions) {
            throw SourceApiException("unsupported_storage_payload", "The Node returned an unsupported storage payload.")
        }
        val bytes = nodeApi.storagePayload(connected.discovered.apiBaseUrl, connected.trusted, revision)
        return try {
            data.decode(bytes)
        } finally {
            bytes.fill(0)
        }
    }

    private fun state(session: VaultSession): CanonicalSyncState = canonicalState
        ?: localStore.loadSyncState(session, data).also { canonicalState = it }

    private suspend fun legacyRemoteValue(session: VaultSession, connected: ConnectedNode): T? {
        val remoteSnapshot = nodeApi.latestSnapshot(
            connected.discovered.apiBaseUrl,
            connected.trusted,
            data.descriptor.remoteAppId,
        ) ?: return null
        val encryptionKey = snapshotKey(session, connected)
        return try {
            withContext(Dispatchers.Default) {
                localStore.readSnapshot(session, data, remoteSnapshot, encryptionKey)
            }
        } finally {
            if (encryptionKey !== session.key) encryptionKey.fill(0)
        }
    }

    private suspend fun legacySynchronizeLocked(
        session: VaultSession?,
        connected: ConnectedNode?,
        localValue: T,
        applyRemote: (T) -> Unit,
    ) {
        if (session == null || connected == null) return
        lastError = null
        val revisionBeforeDownload = localRevision.get()
        try {
            val remoteValue = legacyRemoteValue(session, connected)
            if (remoteValue == null) {
                legacyBackupLocked(session, connected, localValue)
                return
            }
            if (localRevision.get() != revisionBeforeDownload) {
                legacyBackupLocked(session, connected, localValue)
                return
            }
            data.merge(localValue, remoteValue)?.let { merged ->
                val localIdentity = data.version(localValue).contentIdentity
                val remoteIdentity = data.version(remoteValue).contentIdentity
                val mergedIdentity = data.version(merged).contentIdentity
                when (mergedIdentity) {
                    localIdentity -> legacyBackupLocked(session, connected, localValue)
                    remoteIdentity -> {
                        localRevision.incrementAndGet()
                        withContext(Dispatchers.Default) { localStore.save(session, data, remoteValue) }
                        backupDirty = false
                        applyRemote(remoteValue)
                    }
                    else -> {
                        localRevision.incrementAndGet()
                        withContext(Dispatchers.Default) { localStore.save(session, data, merged) }
                        backupDirty = true
                        applyRemote(merged)
                        legacyBackupLocked(session, connected, merged)
                    }
                }
                return
            }
            when (resolveLegacySourceData(data.version(localValue), data.version(remoteValue))) {
                SourceDataResolution.USE_REMOTE -> {
                    localRevision.incrementAndGet()
                    withContext(Dispatchers.Default) { localStore.save(session, data, remoteValue) }
                    backupDirty = false
                    applyRemote(remoteValue)
                }
                SourceDataResolution.MATCH -> backupDirty = false
                SourceDataResolution.KEEP_LOCAL -> legacyBackupLocked(session, connected, localValue)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            backupDirty = true
        }
    }

    private suspend fun legacyRestoreRecovered(session: VaultSession, connected: ConnectedNode): T? {
        val remoteValue = legacyRemoteValue(session, connected)
        if (remoteValue == null) {
            recoveryRestorePending = false
            backupDirty = true
            return null
        }
        localRevision.incrementAndGet()
        withContext(Dispatchers.Default) { localStore.save(session, data, remoteValue) }
        backupDirty = false
        recoveryRestorePending = false
        return remoteValue
    }

    private suspend fun legacyBackupLocked(session: VaultSession?, connected: ConnectedNode?, value: T) {
        if (!backupDirty || session == null || connected == null) return
        val uploadedRevision = localRevision.get()
        val encryptionKey = snapshotKey(session, connected)
        try {
            val snapshot = withContext(Dispatchers.Default) {
                localStore.createSnapshot(session, data, value, encryptionKey)
            }
            nodeApi.uploadSnapshot(
                connected.discovered.apiBaseUrl,
                connected.trusted,
                data.descriptor.remoteAppId,
                UUID.randomUUID().toString(),
                snapshot,
            )
            lastError = null
            if (localRevision.get() == uploadedRevision) backupDirty = false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            lastError = error
            backupDirty = true
        } finally {
            if (encryptionKey !== session.key) encryptionKey.fill(0)
        }
    }

    private fun snapshotKey(session: VaultSession, connected: ConnectedNode): ByteArray =
        connected.trusted.dataKey?.let(SourceCrypto::base64UrlDecode) ?: session.key

    private fun requireLocalRevision(expected: Long) {
        if (localRevision.get() != expected) throw LocalDataChangedDuringSync()
    }

    private fun publishRemoteIfUnchanged(
        expected: Long,
        value: T,
        changed: Boolean,
        applyRemote: (T) -> Unit,
    ): Long = synchronized(localRevisionGate) {
        requireLocalRevision(expected)
        if (changed) {
            localRevision.incrementAndGet()
            applyRemote(value)
        }
        localRevision.get()
    }
}

private class LocalDataChangedDuringSync : Exception()
