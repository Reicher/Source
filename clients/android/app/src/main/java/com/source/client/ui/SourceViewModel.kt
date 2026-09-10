package com.source.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.source.client.SourceClientApplication
import com.source.client.ai.SourceAiContent
import com.source.client.ai.SourceAiEvent
import com.source.client.ai.SourceAiMessage
import com.source.client.ai.SourceAiRequest
import com.source.client.ai.SourceAiRole
import com.source.client.model.AiSelection
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeStatus
import com.source.client.model.PairingInvitation
import com.source.client.model.TrustedNode
import com.source.client.model.VaultProfile
import com.source.client.protocol.PairingPayloadException
import com.source.client.protocol.PairingPayloadParser
import com.source.client.protocol.SourceApiException
import com.source.client.security.VaultSession
import com.source.client.security.SourceCrypto
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val selection: AiSelection = AiSelection.AUTO,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface AppScreen {
    data class Accounts(val profiles: List<VaultProfile>) : AppScreen
    data class Setup(val canCancel: Boolean = false, val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Locked(val profile: VaultProfile, val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Main(
        val status: NodeStatus,
        val userDisplayName: String,
        val chat: ChatUiState = ChatUiState(),
    ) : AppScreen
    data class Scanner(val node: DiscoveredNode, val error: String? = null) : AppScreen
    data class Recovery(
        val node: DiscoveredNode,
        val invitation: PairingInvitation,
        val error: String? = null,
        val busy: Boolean = false,
    ) : AppScreen
    data class Pairing(val name: String) : AppScreen
}

internal fun keepActiveConnectionStatus(status: NodeStatus, connectionAttemptActive: Boolean): Boolean =
    status is NodeStatus.Connecting && connectionAttemptActive

private data class ConnectedNode(val discovered: DiscoveredNode, val trusted: TrustedNode)

class SourceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SourceClientApplication
    private val _screen = MutableStateFlow<AppScreen>(initialScreen())
    private var session: VaultSession? = null
    private var chatState = ChatUiState()
    private var foreground = false
    private var backupDirty = true
    private var recoveryRestorePending = false
    private var conversationRevision = 0L
    private val syncMutex = Mutex()
    private var connectedNode: ConnectedNode? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var inferenceJob: Job? = null
    private var activeAiRunId: String? = null
    private var lastConnectedService: String? = null
    private var lastConnectedApiBaseUrl: String? = null

    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.nodeDiscovery.nodes, app.networkMonitor.available) { nodes, network -> nodes to network }
                .collect { (nodes, network) -> reconcile(nodes, network) }
        }
    }

    private fun initialScreen(): AppScreen {
        val profiles = app.secureVault.profiles
        return if (profiles.isEmpty()) AppScreen.Setup() else AppScreen.Accounts(profiles)
    }

    fun selectProfile(profile: VaultProfile) {
        if (app.secureVault.profiles.none { it.id == profile.id }) {
            _screen.value = AppScreen.Accounts(app.secureVault.profiles)
            return
        }
        _screen.value = AppScreen.Locked(profile)
    }

    fun showCreateIdentity() {
        _screen.value = AppScreen.Setup(canCancel = app.secureVault.profiles.isNotEmpty())
    }

    fun showAccounts() {
        _screen.value = AppScreen.Accounts(app.secureVault.profiles)
    }

    fun createIdentity(userName: String, password: String, confirmation: String) {
        val name = userName.trim()
        val error = when {
            name.isEmpty() -> "Ange ett användarnamn."
            name.length > 100 || name.any { it.isISOControl() } -> "Användarnamnet är ogiltigt."
            password.isEmpty() -> "Ange ett lösenord."
            password != confirmation -> "Lösenorden matchar inte."
            else -> null
        }
        if (error != null) {
            _screen.value = AppScreen.Setup(canCancel = app.secureVault.profiles.isNotEmpty(), error = error)
            return
        }
        val canCancel = app.secureVault.profiles.isNotEmpty()
        _screen.value = AppScreen.Setup(canCancel = canCancel, busy = true)
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                session = withContext(Dispatchers.Default) { app.secureVault.create(name, chars) }
                chatState = ChatUiState()
                backupDirty = true
                openMain()
            } catch (_: Exception) {
                _screen.value = AppScreen.Setup(
                    canCancel = app.secureVault.profiles.isNotEmpty(),
                    error = "Användaren kunde inte skapas på den här enheten.",
                )
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun unlock(password: String) {
        val locked = _screen.value as? AppScreen.Locked ?: return
        if (password.isEmpty()) {
            _screen.value = locked.copy(error = "Ange ditt lösenord.")
            return
        }
        _screen.value = locked.copy(error = null, busy = true)
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                val unlocked = withContext(Dispatchers.Default) { app.secureVault.unlock(locked.profile.id, chars) }
                if (unlocked == null) {
                    _screen.value = locked.copy(error = "Fel lösenord.")
                } else {
                    session = unlocked
                    chatState = ChatUiState(messages = withContext(Dispatchers.Default) {
                        app.secureVault.loadConversation(unlocked)
                    })
                    backupDirty = true
                    openMain()
                }
            } catch (_: Exception) {
                session?.close()
                session = null
                _screen.value = locked.copy(error = "Source-data kunde inte läsas på den här enheten.")
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun logout() {
        activeAiRunId = null
        inferenceJob?.cancel()
        connectJob?.cancel()
        heartbeatJob?.cancel()
        app.nodeDiscovery.stop()
        app.networkMonitor.stop()
        connectedNode = null
        lastConnectedService = null
        lastConnectedApiBaseUrl = null
        session?.close()
        session = null
        chatState = ChatUiState()
        backupDirty = true
        recoveryRestorePending = false
        conversationRevision = 0L
        _screen.value = AppScreen.Accounts(app.secureVault.profiles)
    }

    fun selectAi(selection: AiSelection) {
        chatState = chatState.copy(selection = selection, error = null)
        publishChat()
    }

    fun sendMessage(raw: String) {
        val content = raw.trim()
        if (content.isEmpty() || inferenceJob?.isActive == true) return
        if (recoveryRestorePending) {
            chatState = chatState.copy(error = "Vänta medan din data återställs från noden.")
            publishChat()
            return
        }
        if (content.length > MAX_MESSAGE_CHARACTERS) {
            chatState = chatState.copy(error = "Meddelandet får vara högst 4000 tecken.")
            publishChat()
            return
        }
        val activeSession = session ?: return
        val runId = UUID.randomUUID().toString()
        activeAiRunId = runId
        val userMessage = ChatMessage.user(content)
        chatState = chatState.copy(
            messages = chatState.messages + userMessage,
            streamingMessage = null,
            busy = true,
            error = null,
        )
        conversationRevision += 1
        publishChat()
        backupDirty = true

        inferenceJob = viewModelScope.launch {
            try {
                persistConversation(activeSession)
                val context = boundedChatContext(chatState.messages)
                val selected = chatState.selection
                val connected = connectedNode
                val resolved = resolveAiRuntime(selected, connected != null)
                val assistant = if (resolved == AiRuntimeTarget.NODE && connected != null) {
                    try {
                        streamNodeAnswer(
                            connected.discovered.apiBaseUrl,
                            connected.trusted,
                            context,
                            runId,
                            activeSession.vault.identity.userId,
                        )
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (!canFallbackFromNode(error)) throw error
                        if (error !is SourceApiException) {
                            connectedNode = null
                            updateNodeStatus(NodeStatus.PairedOffline(connected.trusted))
                        }
                        streamLocalAnswer(context, runId, activeSession.vault.identity.userId)
                    }
                } else {
                    streamLocalAnswer(context, runId, activeSession.vault.identity.userId)
                }
                if (activeAiRunId != runId) return@launch
                chatState = chatState.copy(
                    messages = chatState.messages + assistant.copy(content = assistant.content.take(MAX_MESSAGE_CHARACTERS)),
                    streamingMessage = null,
                    busy = false,
                    error = null,
                )
                conversationRevision += 1
                persistConversation(activeSession)
            } catch (error: CancellationException) {
                if (activeAiRunId == runId) chatState = chatState.copy(streamingMessage = null, busy = false)
                throw error
            } catch (error: Exception) {
                chatState = chatState.copy(
                    streamingMessage = null,
                    busy = false,
                    error = readableChatError(error),
                )
            } finally {
                if (activeAiRunId == runId) activeAiRunId = null
                publishChat()
                backupConversationIfNeeded()
            }
        }
    }

    fun cancelInference() {
        val runId = activeAiRunId ?: return
        activeAiRunId = null
        app.localAiRuntime.cancel(runId)
        inferenceJob?.cancel()
        inferenceJob = null
        chatState = chatState.copy(streamingMessage = null, busy = false, error = null)
        publishChat()
    }

    private suspend fun streamLocalAnswer(
        messages: List<ChatMessage>,
        runId: String,
        conversationId: String,
    ): ChatMessage {
        val draft = ChatMessage(id = runId, role = ChatRole.ASSISTANT, content = "")
        val content = StringBuilder()
        var completed = false
        val request = SourceAiRequest(
            runId = runId,
            conversationId = conversationId,
            messages = messages.map { message ->
                SourceAiMessage(
                    role = when (message.role) {
                        ChatRole.USER -> SourceAiRole.USER
                        ChatRole.ASSISTANT -> SourceAiRole.ASSISTANT
                    },
                    content = listOf(SourceAiContent.Text(message.content)),
                )
            },
        )
        app.localAiRuntime.stream(request).collect { event ->
            check(event.runId == runId) { "The local runtime returned the wrong run identifier" }
            when (event) {
                is SourceAiEvent.Started -> Unit
                is SourceAiEvent.Delta -> {
                    content.append(event.text)
                    if (activeAiRunId == runId) {
                        chatState = chatState.copy(streamingMessage = draft.copy(content = content.toString()))
                        publishChat()
                    }
                }
                is SourceAiEvent.Completed -> completed = true
                is SourceAiEvent.Failed -> error("Local AI failed: ${event.code}")
            }
        }
        check(completed && content.isNotBlank()) { "The local model returned an incomplete response" }
        return draft.copy(content = content.toString().trim())
    }

    private suspend fun streamNodeAnswer(
        apiBaseUrl: String,
        trusted: TrustedNode,
        messages: List<ChatMessage>,
        runId: String,
        conversationId: String,
    ): ChatMessage {
        val draft = ChatMessage(id = runId, role = ChatRole.ASSISTANT, content = "")
        val content = StringBuilder()
        var completed = false
        val request = sourceAiRequest(messages, runId, conversationId)
        try {
            app.nodeApi.streamAi(apiBaseUrl, trusted, request).collect { event ->
                check(event.runId == runId) { "The Node returned the wrong run identifier" }
                when (event) {
                    is SourceAiEvent.Started -> Unit
                    is SourceAiEvent.Delta -> {
                        content.append(event.text)
                        if (activeAiRunId == runId) {
                            chatState = chatState.copy(streamingMessage = draft.copy(content = content.toString()))
                            publishChat()
                        }
                    }
                    is SourceAiEvent.Completed -> completed = true
                    is SourceAiEvent.Failed -> throw SourceApiException(event.code, "Nodens AI kunde inte slutföra svaret.")
                }
            }
        } catch (error: Exception) {
            if (content.isNotEmpty()) {
                throw SourceApiException(
                    "node_stream_interrupted_after_output",
                    "Anslutningen till noden bröts mitt i svaret.",
                )
            }
            throw error
        }
        check(completed && content.isNotBlank()) { "The Node returned an incomplete response" }
        return draft.copy(content = content.toString().trim())
    }

    private fun sourceAiRequest(
        messages: List<ChatMessage>,
        runId: String,
        conversationId: String,
    ) = SourceAiRequest(
        runId = runId,
        conversationId = conversationId,
        messages = messages.map { message ->
            SourceAiMessage(
                role = when (message.role) {
                    ChatRole.USER -> SourceAiRole.USER
                    ChatRole.ASSISTANT -> SourceAiRole.ASSISTANT
                },
                content = listOf(SourceAiContent.Text(message.content)),
            )
        },
    )

    fun onForeground() {
        foreground = true
        if (session != null) startRuntime()
    }

    fun onBackground() {
        foreground = false
        connectedNode = null
        app.nodeDiscovery.stop()
        app.networkMonitor.stop()
        connectJob?.cancel()
        heartbeatJob?.cancel()
        if (_screen.value is AppScreen.Pairing || _screen.value is AppScreen.Recovery) {
            updateNodeStatus(statusForCurrentNodes())
        }
        (_screen.value as? AppScreen.Main)?.let { main ->
            val connected = (main.status as? NodeStatus.Connected)?.node
            if (connected != null) updateNodeStatus(NodeStatus.PairedOffline(connected))
        }
    }

    fun scan(node: DiscoveredNode) {
        _screen.value = AppScreen.Scanner(node)
    }

    fun cancelScanner() {
        _screen.value = mainScreen(statusForCurrentNodes())
    }

    fun onQrScanned(raw: String) {
        val scanner = _screen.value as? AppScreen.Scanner ?: return
        val invitation = try {
            PairingPayloadParser.parse(raw)
        } catch (error: PairingPayloadException) {
            _screen.value = scanner.copy(error = error.message)
            return
        }
        if (invitation.nodeId != scanner.node.nodeIdHint) {
            _screen.value = scanner.copy(error = "QR-koden tillhör inte noden du valde.")
            return
        }
        if (invitation.recovery) {
            _screen.value = AppScreen.Recovery(scanner.node, invitation)
            return
        }
        val activeSession = session ?: return
        _screen.value = AppScreen.Pairing(invitation.nodeName)
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                val recovery = SourceCrypto.generateRecoveryMaterial(invitation.nodeId)
                val result = app.nodeApi.pair(
                    invitation,
                    activeSession.vault.identity,
                    recovery.recoveryKey,
                    recovery.envelope,
                )
                val trusted = result.trustedNode.copy(dataKey = recovery.dataKey)
                saveTrustedNode(activeSession, trusted)
                connectedNode = ConnectedNode(scanner.node, trusted)
                _screen.value = mainScreen(NodeStatus.Connected(trusted))
                synchronizeConversation()
                startHeartbeat(scanner.node, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _screen.value = mainScreen(NodeStatus.Error(readableError(error)))
            }
        }
    }

    fun cancelRecovery() {
        _screen.value = mainScreen(statusForCurrentNodes())
    }

    fun recover(rawRecoveryKey: String) {
        val recoveryScreen = _screen.value as? AppScreen.Recovery ?: return
        val recoveryKey = rawRecoveryKey.trim()
        if (!recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            _screen.value = recoveryScreen.copy(error = "Återställningsnyckeln är ogiltig.")
            return
        }
        val activeSession = session ?: return
        _screen.value = recoveryScreen.copy(error = null, busy = true)
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                val result = app.nodeApi.pair(
                    recoveryScreen.invitation,
                    activeSession.vault.identity,
                    recoveryKey,
                )
                val envelope = result.recoveryEnvelope
                    ?: throw IllegalStateException("Recovery envelope is missing")
                val dataKey = withContext(Dispatchers.Default) {
                    SourceCrypto.unwrapDataKey(recoveryScreen.invitation.nodeId, recoveryKey, envelope)
                }
                val trusted = try {
                    result.trustedNode.copy(dataKey = SourceCrypto.base64Url(dataKey))
                } finally {
                    dataKey.fill(0)
                }
                saveTrustedNode(activeSession, trusted)
                connectedNode = ConnectedNode(recoveryScreen.node, trusted)
                backupDirty = false
                recoveryRestorePending = true
                _screen.value = mainScreen(NodeStatus.Connected(trusted))
                try {
                    restoreRecoveredConversation()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    chatState = chatState.copy(
                        busy = false,
                        error = "Användaren är återansluten, men datan kunde inte hämtas ännu. Klienten försöker igen.",
                    )
                    publishChat()
                }
                startHeartbeat(recoveryScreen.node, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _screen.value = recoveryScreen.copy(error = readableError(error), busy = false)
            }
        }
    }

    private fun saveTrustedNode(activeSession: VaultSession, trusted: TrustedNode) {
        activeSession.vault = activeSession.vault.copy(
            trustedNodes = activeSession.vault.trustedNodes.filterNot { it.nodeId == trusted.nodeId } + trusted,
        )
        app.secureVault.save(activeSession)
    }

    fun retry() {
        updateNodeStatus(statusForCurrentNodes())
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    private fun openMain() {
        val displayName = session?.vault?.identity?.userDisplayName ?: return
        _screen.value = AppScreen.Main(NodeStatus.Searching, displayName, chatState)
        if (foreground) startRuntime()
    }

    private fun startRuntime() {
        app.networkMonitor.start()
        if (app.networkMonitor.available.value) app.nodeDiscovery.start()
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    private fun reconcile(nodes: List<DiscoveredNode>, network: Boolean) {
        if (!foreground || session == null || _screen.value !is AppScreen.Main) return
        if (!network) {
            connectedNode = null
            app.nodeDiscovery.stop()
            heartbeatJob?.cancel()
            connectJob?.cancel()
            updateNodeStatus(session!!.vault.trustedNodes.firstOrNull()?.let(NodeStatus::PairedOffline) ?: NodeStatus.NoneFound)
            return
        }
        app.nodeDiscovery.start()
        val trustedNodes = session!!.vault.trustedNodes
        val candidate = nodes.firstNotNullOfOrNull { discovered ->
            trustedNodes.firstOrNull { it.nodeId == discovered.nodeIdHint }?.let { discovered to it }
        }
        if (candidate != null) {
            val current = (_screen.value as? AppScreen.Main)?.status as? NodeStatus.Connected
            if (current?.node?.nodeId == candidate.second.nodeId &&
                lastConnectedService == candidate.first.serviceName &&
                lastConnectedApiBaseUrl == candidate.first.apiBaseUrl
            ) return
            authenticate(candidate.first, candidate.second)
            return
        }
        connectedNode = null
        val currentStatus = (_screen.value as? AppScreen.Main)?.status
        if (currentStatus != null && keepActiveConnectionStatus(currentStatus, connectJob?.isActive == true)) return
        heartbeatJob?.cancel()
        updateNodeStatus(
            when {
                trustedNodes.isNotEmpty() -> NodeStatus.PairedOffline(trustedNodes.first())
                nodes.isNotEmpty() -> NodeStatus.Found(nodes)
                else -> NodeStatus.NoneFound
            },
        )
    }

    private fun authenticate(discovered: DiscoveredNode, trusted: TrustedNode, attempt: Int = 0) {
        if (connectJob?.isActive == true) return
        updateNodeStatus(NodeStatus.Connecting(trusted.displayName))
        connectJob = viewModelScope.launch {
            try {
                var refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                val activeSession = session ?: return@launch
                if (refreshed.displayName != trusted.displayName) {
                    activeSession.vault = activeSession.vault.copy(
                        trustedNodes = activeSession.vault.trustedNodes.map { if (it.nodeId == refreshed.nodeId) refreshed else it },
                    )
                    app.secureVault.save(activeSession)
                }
                lastConnectedService = discovered.serviceName
                lastConnectedApiBaseUrl = discovered.apiBaseUrl
                connectedNode = ConnectedNode(discovered, refreshed)
                updateNodeStatus(NodeStatus.Connected(refreshed))
                synchronizeConversation()
                refreshed = configureRecoveryIfNeeded(activeSession, discovered, refreshed)
                startHeartbeat(discovered, refreshed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                connectedNode = null
                if (foreground && attempt < 5) {
                    delay((1L shl attempt).coerceAtMost(16) * 1_000)
                    connectJob = null
                    if (app.nodeDiscovery.nodes.value.any { it.serviceName == discovered.serviceName }) {
                        authenticate(discovered, trusted, attempt + 1)
                        return@launch
                    }
                }
                updateNodeStatus(NodeStatus.PairedOffline(trusted))
            }
        }
    }

    private suspend fun configureRecoveryIfNeeded(
        activeSession: VaultSession,
        discovered: DiscoveredNode,
        trusted: TrustedNode,
    ): TrustedNode {
        if (trusted.recoveryKey != null && trusted.dataKey != null && !trusted.recoverySetupPending) return trusted
        val pending = if (trusted.recoveryKey == null || trusted.dataKey == null) {
            val material = SourceCrypto.generateRecoveryMaterial(trusted.nodeId)
            trusted.copy(
                recoveryKey = material.recoveryKey,
                dataKey = material.dataKey,
                recoverySetupPending = true,
            )
        } else {
            trusted.copy(recoverySetupPending = true)
        }
        // Persist the exact recovery material before sending it. If the response
        // is lost, the same key can be submitted again on the next connection.
        saveTrustedNode(activeSession, pending)
        connectedNode = ConnectedNode(discovered, pending)
        updateNodeStatus(NodeStatus.Connected(pending))
        val recoveryKey = checkNotNull(pending.recoveryKey)
        val dataKey = SourceCrypto.base64UrlDecode(checkNotNull(pending.dataKey))
        val envelope = try {
            SourceCrypto.wrapDataKey(pending.nodeId, recoveryKey, dataKey)
        } finally {
            dataKey.fill(0)
        }
        app.nodeApi.setupRecovery(
            discovered.apiBaseUrl,
            pending,
            recoveryKey,
            envelope,
        )
        val updated = pending.copy(recoverySetupPending = false)
        saveTrustedNode(activeSession, updated)
        connectedNode = ConnectedNode(discovered, updated)
        updateNodeStatus(NodeStatus.Connected(updated))
        backupDirty = true
        backupConversationIfNeeded()
        return updated
    }

    private fun startHeartbeat(discovered: DiscoveredNode, trusted: TrustedNode) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (foreground) {
                delay(20_000)
                try {
                    val refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                    connectedNode = ConnectedNode(discovered, refreshed)
                    updateNodeStatus(NodeStatus.Connected(refreshed))
                    if (recoveryRestorePending) {
                        try {
                            restoreRecoveredConversation()
                        } catch (error: CancellationException) {
                            throw error
                        } catch (_: Exception) {
                            chatState = chatState.copy(
                                busy = false,
                                error = "Datan kunde inte hämtas ännu. Klienten försöker igen.",
                            )
                            publishChat()
                        }
                    } else {
                        backupConversationIfNeeded()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    connectedNode = null
                    lastConnectedService = null
                    lastConnectedApiBaseUrl = null
                    updateNodeStatus(NodeStatus.PairedOffline(trusted))
                    connectJob = null
                    authenticate(discovered, trusted)
                    return@launch
                }
            }
        }
    }

    private suspend fun persistConversation(activeSession: VaultSession) {
        val messages = chatState.messages
        syncMutex.withLock {
            withContext(Dispatchers.Default) {
                app.secureVault.saveConversation(activeSession, messages)
            }
            backupDirty = true
        }
    }

    private suspend fun backupConversationIfNeeded() = syncMutex.withLock {
        backupConversationLocked()
    }

    private suspend fun backupConversationLocked() {
        if (!backupDirty) return
        val activeSession = session ?: return
        val connected = connectedNode ?: return
        val uploadedRevision = conversationRevision
        val uploadedMessages = chatState.messages
        val encryptionKey = snapshotKey(activeSession, connected.trusted)
        try {
            val snapshot = withContext(Dispatchers.Default) {
                app.secureVault.createConversationSnapshot(activeSession, uploadedMessages, encryptionKey)
            }
            app.nodeApi.uploadConversationSnapshot(
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
            if (encryptionKey !== activeSession.key) encryptionKey.fill(0)
        }
    }

    private suspend fun synchronizeConversation() {
        syncMutex.withLock {
            val activeSession = session ?: return@withLock
            val connected = connectedNode ?: return@withLock
            val revisionBeforeDownload = conversationRevision
            try {
                val remoteSnapshot = app.nodeApi.latestConversationSnapshot(
                    connected.discovered.apiBaseUrl,
                    connected.trusted,
                )
                if (remoteSnapshot == null) {
                    backupConversationLocked()
                    return@withLock
                }
                val encryptionKey = snapshotKey(activeSession, connected.trusted)
                val remoteMessages = try {
                    withContext(Dispatchers.Default) {
                        app.secureVault.readConversationSnapshot(activeSession, remoteSnapshot, encryptionKey)
                    }
                } finally {
                    if (encryptionKey !== activeSession.key) encryptionKey.fill(0)
                }
                if (conversationRevision != revisionBeforeDownload) {
                    backupConversationLocked()
                    return@withLock
                }
                val remoteUpdatedAt = remoteMessages.maxOfOrNull(ChatMessage::createdAtMillis) ?: Long.MIN_VALUE
                val localUpdatedAt = chatState.messages.maxOfOrNull(ChatMessage::createdAtMillis) ?: Long.MIN_VALUE
                when {
                    remoteUpdatedAt > localUpdatedAt -> {
                        chatState = chatState.copy(messages = remoteMessages)
                        conversationRevision += 1
                        withContext(Dispatchers.Default) {
                            app.secureVault.saveConversation(activeSession, remoteMessages)
                        }
                        backupDirty = false
                        publishChat()
                    }
                    remoteMessages.map(ChatMessage::id) == chatState.messages.map(ChatMessage::id) -> backupDirty = false
                    else -> backupConversationLocked()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                backupDirty = true
            }
        }
    }

    private suspend fun restoreRecoveredConversation() {
        syncMutex.withLock {
            val activeSession = session ?: return@withLock
            val connected = connectedNode ?: return@withLock
            val remoteSnapshot = app.nodeApi.latestConversationSnapshot(
                connected.discovered.apiBaseUrl,
                connected.trusted,
            )
            if (remoteSnapshot == null) {
                recoveryRestorePending = false
                backupDirty = true
                chatState = chatState.copy(busy = false, error = null)
                publishChat()
                return@withLock
            }
            val encryptionKey = snapshotKey(activeSession, connected.trusted)
            val remoteMessages = try {
                withContext(Dispatchers.Default) {
                    app.secureVault.readConversationSnapshot(activeSession, remoteSnapshot, encryptionKey)
                }
            } finally {
                if (encryptionKey !== activeSession.key) encryptionKey.fill(0)
            }
            chatState = chatState.copy(messages = remoteMessages, busy = false, error = null)
            conversationRevision += 1
            withContext(Dispatchers.Default) {
                app.secureVault.saveConversation(activeSession, remoteMessages)
            }
            backupDirty = false
            recoveryRestorePending = false
            publishChat()
        }
    }

    private fun snapshotKey(activeSession: VaultSession, trusted: TrustedNode): ByteArray =
        trusted.dataKey?.let(SourceCrypto::base64UrlDecode) ?: activeSession.key

    private fun publishChat() {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(chat = chatState)
    }

    private fun updateNodeStatus(status: NodeStatus) {
        val current = _screen.value
        _screen.value = if (current is AppScreen.Main) current.copy(status = status, chat = chatState)
        else mainScreen(status)
    }

    private fun mainScreen(status: NodeStatus): AppScreen.Main = AppScreen.Main(
        status = status,
        userDisplayName = session?.vault?.identity?.userDisplayName.orEmpty(),
        chat = chatState,
    )

    private fun statusForCurrentNodes(): NodeStatus {
        val trusted = session?.vault?.trustedNodes.orEmpty()
        val nodes = app.nodeDiscovery.nodes.value
        return when {
            trusted.isNotEmpty() -> NodeStatus.PairedOffline(trusted.first())
            nodes.isNotEmpty() -> NodeStatus.Found(nodes)
            else -> NodeStatus.NoneFound
        }
    }

    private fun readableChatError(error: Exception): String = when {
        error is SourceApiException -> error.message ?: "Nodens AI kunde inte svara."
        error.message?.contains("model part", ignoreCase = true) == true ->
            "Den lokala AI-modellen är inte installerad."
        else -> "AI:n kunde inte svara. Försök igen."
    }

    private fun readableError(error: Exception): String = when {
        error is SourceApiException && error.code == "pairing_unavailable" -> "Inbjudan är inte längre tillgänglig."
        error is SourceApiException && error.code == "duplicate_client" -> "Den här klienten är redan parkopplad."
        error is SourceApiException -> error.message ?: "Anslutningen misslyckades."
        else -> "Source Node kunde inte nås."
    }

    override fun onCleared() {
        app.nodeDiscovery.stop()
        app.networkMonitor.stop()
        session?.close()
        super.onCleared()
    }

    private companion object {
        const val MAX_MESSAGE_CHARACTERS = 4_000
    }
}
