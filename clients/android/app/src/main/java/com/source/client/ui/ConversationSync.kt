package com.source.client.ui

import com.source.client.model.ChatMessage
import com.source.client.protocol.SourceNodeApi
import com.source.client.security.SecureVault
import com.source.client.security.SourceCrypto
import com.source.client.security.VaultSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class ConversationSync(
    private val secureVault: SecureVault,
    private val nodeApi: SourceNodeApi,
) {
    private val mutex = Mutex()
    private var backupDirty = true
    private var conversationRevision = 0L

    var recoveryRestorePending = false
        private set

    fun reset() {
        backupDirty = true
        recoveryRestorePending = false
        conversationRevision = 0L
    }

    fun conversationChanged() {
        conversationRevision += 1
        backupDirty = true
    }

    fun beginRecoveryRestore() {
        recoveryRestorePending = true
    }

    suspend fun persist(session: VaultSession, messages: List<ChatMessage>) {
        mutex.withLock {
            withContext(Dispatchers.Default) {
                secureVault.saveConversation(session, messages)
            }
            backupDirty = true
        }
    }

    suspend fun backupIfNeeded(
        session: VaultSession?,
        connected: ConnectedNode?,
        messages: List<ChatMessage>,
    ) = mutex.withLock {
        backupLocked(session, connected, messages)
    }

    suspend fun synchronize(
        session: VaultSession?,
        connected: ConnectedNode?,
        localMessages: List<ChatMessage>,
        applyRemoteMessages: (List<ChatMessage>) -> Unit,
    ) = mutex.withLock {
        if (session == null || connected == null) return@withLock
        val revisionBeforeDownload = conversationRevision
        try {
            val remoteSnapshot = nodeApi.latestConversationSnapshot(
                connected.discovered.apiBaseUrl,
                connected.trusted,
            )
            if (remoteSnapshot == null) {
                backupLocked(session, connected, localMessages)
                return@withLock
            }
            val encryptionKey = snapshotKey(session, connected)
            val remoteMessages = try {
                withContext(Dispatchers.Default) {
                    secureVault.readConversationSnapshot(session, remoteSnapshot, encryptionKey)
                }
            } finally {
                if (encryptionKey !== session.key) encryptionKey.fill(0)
            }
            if (conversationRevision != revisionBeforeDownload) {
                backupLocked(session, connected, localMessages)
                return@withLock
            }
            when (resolveConversation(localMessages, remoteMessages)) {
                ConversationResolution.USE_REMOTE -> {
                    conversationRevision += 1
                    withContext(Dispatchers.Default) {
                        secureVault.saveConversation(session, remoteMessages)
                    }
                    backupDirty = false
                    applyRemoteMessages(remoteMessages)
                }
                ConversationResolution.MATCH -> {
                    backupDirty = false
                }
                ConversationResolution.KEEP_LOCAL -> {
                    backupLocked(session, connected, localMessages)
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            backupDirty = true
        }
    }

    suspend fun restoreRecovered(
        session: VaultSession?,
        connected: ConnectedNode?,
    ): List<ChatMessage>? = mutex.withLock {
        if (session == null || connected == null) return@withLock null
        val remoteSnapshot = nodeApi.latestConversationSnapshot(
            connected.discovered.apiBaseUrl,
            connected.trusted,
        )
        if (remoteSnapshot == null) {
            recoveryRestorePending = false
            backupDirty = true
            return@withLock null
        }
        val encryptionKey = snapshotKey(session, connected)
        val remoteMessages = try {
            withContext(Dispatchers.Default) {
                secureVault.readConversationSnapshot(session, remoteSnapshot, encryptionKey)
            }
        } finally {
            if (encryptionKey !== session.key) encryptionKey.fill(0)
        }
        conversationRevision += 1
        withContext(Dispatchers.Default) {
            secureVault.saveConversation(session, remoteMessages)
        }
        backupDirty = false
        recoveryRestorePending = false
        remoteMessages
    }

    private suspend fun backupLocked(
        session: VaultSession?,
        connected: ConnectedNode?,
        messages: List<ChatMessage>,
    ) {
        if (!backupDirty || session == null || connected == null) return
        val uploadedRevision = conversationRevision
        val encryptionKey = snapshotKey(session, connected)
        try {
            val snapshot = withContext(Dispatchers.Default) {
                secureVault.createConversationSnapshot(session, messages, encryptionKey)
            }
            nodeApi.uploadConversationSnapshot(
                connected.discovered.apiBaseUrl,
                connected.trusted,
                UUID.randomUUID().toString(),
                snapshot,
            )
            if (conversationRevision == uploadedRevision) backupDirty = false
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

internal enum class ConversationResolution { USE_REMOTE, MATCH, KEEP_LOCAL }

internal fun resolveConversation(
    localMessages: List<ChatMessage>,
    remoteMessages: List<ChatMessage>,
): ConversationResolution {
    val remoteUpdatedAt = remoteMessages.maxOfOrNull(ChatMessage::createdAtMillis) ?: Long.MIN_VALUE
    val localUpdatedAt = localMessages.maxOfOrNull(ChatMessage::createdAtMillis) ?: Long.MIN_VALUE
    return when {
        remoteUpdatedAt > localUpdatedAt -> ConversationResolution.USE_REMOTE
        remoteMessages.map(ChatMessage::id) == localMessages.map(ChatMessage::id) -> ConversationResolution.MATCH
        else -> ConversationResolution.KEEP_LOCAL
    }
}
