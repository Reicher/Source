package com.source.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.source.client.SourceClientApplication
import com.source.client.model.AiSelection
import com.source.client.model.ChatMessage
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeStatus
import com.source.client.model.TrustedNode
import com.source.client.protocol.PairingPayloadException
import com.source.client.protocol.PairingPayloadParser
import com.source.client.protocol.SourceApiException
import com.source.client.security.VaultSession
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val selection: AiSelection = AiSelection.AUTO,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface AppScreen {
    data class Setup(val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Locked(val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Main(val status: NodeStatus, val chat: ChatUiState = ChatUiState()) : AppScreen
    data class Scanner(val node: DiscoveredNode, val error: String? = null) : AppScreen
    data class Pairing(val name: String) : AppScreen
}

internal fun keepActiveConnectionStatus(status: NodeStatus, connectionAttemptActive: Boolean): Boolean =
    status is NodeStatus.Connecting && connectionAttemptActive

private data class ConnectedNode(val discovered: DiscoveredNode, val trusted: TrustedNode)

class SourceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SourceClientApplication
    private val _screen = MutableStateFlow<AppScreen>(
        if (app.secureVault.isInitialized) AppScreen.Locked() else AppScreen.Setup(),
    )
    private var session: VaultSession? = null
    private var chatState = ChatUiState()
    private var foreground = false
    private var backupDirty = true
    private var conversationRevision = 0L
    private val syncMutex = Mutex()
    private var connectedNode: ConnectedNode? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var inferenceJob: Job? = null
    private var lastConnectedService: String? = null
    private var lastConnectedApiBaseUrl: String? = null

    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    init {
        viewModelScope.launch {
            combine(app.nodeDiscovery.nodes, app.networkMonitor.available) { nodes, network -> nodes to network }
                .collect { (nodes, network) -> reconcile(nodes, network) }
        }
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
            _screen.value = AppScreen.Setup(error)
            return
        }
        _screen.value = AppScreen.Setup(busy = true)
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                session = withContext(Dispatchers.Default) { app.secureVault.create(name, chars) }
                chatState = ChatUiState()
                backupDirty = true
                openMain()
            } catch (_: Exception) {
                _screen.value = AppScreen.Setup("Identiteten kunde inte skapas på den här enheten.")
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun unlock(password: String) {
        if (password.isEmpty()) {
            _screen.value = AppScreen.Locked("Ange ditt lösenord.")
            return
        }
        _screen.value = AppScreen.Locked(busy = true)
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                val unlocked = withContext(Dispatchers.Default) { app.secureVault.unlock(chars) }
                if (unlocked == null) {
                    _screen.value = AppScreen.Locked("Fel lösenord.")
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
                _screen.value = AppScreen.Locked("Source-data kunde inte läsas på den här enheten.")
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun selectAi(selection: AiSelection) {
        chatState = chatState.copy(selection = selection, error = null)
        publishChat()
    }

    fun sendMessage(raw: String) {
        val content = raw.trim()
        if (content.isEmpty() || inferenceJob?.isActive == true) return
        if (content.length > MAX_MESSAGE_CHARACTERS) {
            chatState = chatState.copy(error = "Meddelandet får vara högst 4000 tecken.")
            publishChat()
            return
        }
        val activeSession = session ?: return
        val userMessage = ChatMessage.user(content)
        chatState = chatState.copy(messages = chatState.messages + userMessage, busy = true, error = null)
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
                        app.nodeApi.chat(connected.discovered.apiBaseUrl, connected.trusted, context)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (!canFallbackFromNode(error)) throw error
                        if (error !is SourceApiException) {
                            connectedNode = null
                            updateNodeStatus(NodeStatus.PairedOffline(connected.trusted))
                        }
                        app.localAiRuntime.chat(context)
                    }
                } else {
                    app.localAiRuntime.chat(context)
                }
                chatState = chatState.copy(
                    messages = chatState.messages + assistant.copy(content = assistant.content.take(MAX_MESSAGE_CHARACTERS)),
                    busy = false,
                    error = null,
                )
                conversationRevision += 1
                persistConversation(activeSession)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                chatState = chatState.copy(busy = false, error = readableChatError(error))
            } finally {
                publishChat()
                backupConversationIfNeeded()
            }
        }
    }

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
        if (_screen.value is AppScreen.Pairing) updateNodeStatus(statusForCurrentNodes())
        (_screen.value as? AppScreen.Main)?.let { main ->
            val connected = (main.status as? NodeStatus.Connected)?.node
            if (connected != null) updateNodeStatus(NodeStatus.PairedOffline(connected))
        }
    }

    fun scan(node: DiscoveredNode) {
        _screen.value = AppScreen.Scanner(node)
    }

    fun cancelScanner() {
        _screen.value = AppScreen.Main(statusForCurrentNodes(), chatState)
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
        val activeSession = session ?: return
        _screen.value = AppScreen.Pairing(invitation.nodeName)
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                val trusted = app.nodeApi.pair(invitation, activeSession.vault.identity)
                activeSession.vault = activeSession.vault.copy(
                    trustedNodes = activeSession.vault.trustedNodes.filterNot { it.nodeId == trusted.nodeId } + trusted,
                )
                app.secureVault.save(activeSession)
                connectedNode = ConnectedNode(scanner.node, trusted)
                _screen.value = AppScreen.Main(NodeStatus.Connected(trusted), chatState)
                synchronizeConversation()
                startHeartbeat(scanner.node, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _screen.value = AppScreen.Main(NodeStatus.Error(readableError(error)), chatState)
            }
        }
    }

    fun retry() {
        updateNodeStatus(statusForCurrentNodes())
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    private fun openMain() {
        _screen.value = AppScreen.Main(NodeStatus.Searching, chatState)
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
                val refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
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

    private fun startHeartbeat(discovered: DiscoveredNode, trusted: TrustedNode) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (foreground) {
                delay(20_000)
                try {
                    val refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                    connectedNode = ConnectedNode(discovered, refreshed)
                    updateNodeStatus(NodeStatus.Connected(refreshed))
                    backupConversationIfNeeded()
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
        try {
            val snapshot = withContext(Dispatchers.Default) {
                app.secureVault.createConversationSnapshot(activeSession, uploadedMessages)
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
                val remoteMessages = withContext(Dispatchers.Default) {
                    app.secureVault.readConversationSnapshot(activeSession, remoteSnapshot)
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
                backupConversationLocked()
            }
        }
    }

    private fun publishChat() {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(chat = chatState)
    }

    private fun updateNodeStatus(status: NodeStatus) {
        val current = _screen.value
        _screen.value = if (current is AppScreen.Main) current.copy(status = status, chat = chatState)
        else AppScreen.Main(status, chatState)
    }

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
        error is FileNotFoundException || error.message?.contains("source-client-model.litertlm") == true ->
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
