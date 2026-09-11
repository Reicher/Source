package com.source.client.storage

import com.source.client.model.ConnectedNode
import com.source.client.protocol.SourceNodeApi
import com.source.client.security.SourceCrypto
import com.source.client.security.VaultSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stores locally and reconciles encrypted snapshots for any Source dataset. */
class SourceDataSync<T>(
    private val data: SourceData<T>,
    private val localStore: SourceDataStore,
    private val nodeApi: SourceNodeApi,
) {
    private val mutex = Mutex()
    @Volatile private var backupDirty = true
    private var localRevision = 0L

    var recoveryRestorePending = false
        private set

    val isBackedUp: Boolean
        get() = !backupDirty

    fun reset() {
        backupDirty = true
        recoveryRestorePending = false
        localRevision = 0L
    }

    fun changed() {
        localRevision += 1
        backupDirty = true
    }

    fun requireBackup() {
        backupDirty = true
    }

    fun beginRecoveryRestore() {
        recoveryRestorePending = true
    }

    suspend fun persist(session: VaultSession, value: T) {
        mutex.withLock {
            withContext(Dispatchers.Default) { localStore.save(session, data, value) }
            backupDirty = true
        }
    }

    suspend fun backupIfNeeded(session: VaultSession?, connected: ConnectedNode?, value: T) = mutex.withLock {
        backupLocked(session, connected, value)
    }

    suspend fun synchronize(
        session: VaultSession?,
        connected: ConnectedNode?,
        localValue: T,
        applyRemote: (T) -> Unit,
    ) = mutex.withLock {
        if (session == null || connected == null) return@withLock
        val revisionBeforeDownload = localRevision
        try {
            val remoteSnapshot = nodeApi.latestSnapshot(
                connected.discovered.apiBaseUrl,
                connected.trusted,
                data.descriptor.remoteAppId,
            )
            if (remoteSnapshot == null) {
                backupLocked(session, connected, localValue)
                return@withLock
            }
            val encryptionKey = snapshotKey(session, connected)
            val remoteValue = try {
                withContext(Dispatchers.Default) {
                    localStore.readSnapshot(session, data, remoteSnapshot, encryptionKey)
                }
            } finally {
                if (encryptionKey !== session.key) encryptionKey.fill(0)
            }
            if (localRevision != revisionBeforeDownload) {
                backupLocked(session, connected, localValue)
                return@withLock
            }
            data.merge(localValue, remoteValue)?.let { merged ->
                val localIdentity = data.version(localValue).contentIdentity
                val remoteIdentity = data.version(remoteValue).contentIdentity
                val mergedIdentity = data.version(merged).contentIdentity
                when (mergedIdentity) {
                    localIdentity -> backupLocked(session, connected, localValue)
                    remoteIdentity -> {
                        localRevision += 1
                        withContext(Dispatchers.Default) { localStore.save(session, data, remoteValue) }
                        backupDirty = false
                        applyRemote(remoteValue)
                    }
                    else -> {
                        localRevision += 1
                        withContext(Dispatchers.Default) { localStore.save(session, data, merged) }
                        backupDirty = true
                        applyRemote(merged)
                        backupLocked(session, connected, merged)
                    }
                }
                return@withLock
            }
            when (resolveSourceData(data.version(localValue), data.version(remoteValue))) {
                SourceDataResolution.USE_REMOTE -> {
                    localRevision += 1
                    withContext(Dispatchers.Default) { localStore.save(session, data, remoteValue) }
                    backupDirty = false
                    applyRemote(remoteValue)
                }
                SourceDataResolution.MATCH -> backupDirty = false
                SourceDataResolution.KEEP_LOCAL -> backupLocked(session, connected, localValue)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            backupDirty = true
        }
    }

    suspend fun restoreRecovered(session: VaultSession?, connected: ConnectedNode?): T? = mutex.withLock {
        if (session == null || connected == null) return@withLock null
        val remoteSnapshot = nodeApi.latestSnapshot(
            connected.discovered.apiBaseUrl,
            connected.trusted,
            data.descriptor.remoteAppId,
        )
        if (remoteSnapshot == null) {
            recoveryRestorePending = false
            backupDirty = true
            return@withLock null
        }
        val encryptionKey = snapshotKey(session, connected)
        val remoteValue = try {
            withContext(Dispatchers.Default) {
                localStore.readSnapshot(session, data, remoteSnapshot, encryptionKey)
            }
        } finally {
            if (encryptionKey !== session.key) encryptionKey.fill(0)
        }
        localRevision += 1
        withContext(Dispatchers.Default) { localStore.save(session, data, remoteValue) }
        backupDirty = false
        recoveryRestorePending = false
        remoteValue
    }

    private suspend fun backupLocked(session: VaultSession?, connected: ConnectedNode?, value: T) {
        if (!backupDirty || session == null || connected == null) return
        val uploadedRevision = localRevision
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
            if (localRevision == uploadedRevision) backupDirty = false
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            backupDirty = true
        } finally {
            if (encryptionKey !== session.key) encryptionKey.fill(0)
        }
    }

    private fun snapshotKey(session: VaultSession, connected: ConnectedNode): ByteArray =
        connected.trusted.dataKey?.let(SourceCrypto::base64UrlDecode) ?: session.key
}
