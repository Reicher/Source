package com.source.client.ui

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.source.client.R
import com.source.client.SourceClientApplication
import com.source.client.model.AiSelection
import com.source.client.model.ChatMessage
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeStatus
import com.source.client.model.PairingInvitation
import com.source.client.model.VaultProfile
import com.source.client.protocol.PairingPayloadException
import com.source.client.protocol.PairingPayloadError
import com.source.client.protocol.PairingPayloadParser
import com.source.client.protocol.SourceApiException
import com.source.client.security.SourceCrypto
import com.source.client.security.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

class SourceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SourceClientApplication
    private val _screen = MutableStateFlow<AppScreen>(initialScreen())
    private val conversationSync = ConversationSync(app.secureVault, app.nodeApi)
    private val nodeConnection: NodeConnection
    private val chatController: ChatController
    private var session: VaultSession? = null
    private var foreground = false
    private var pairingJob: Job? = null

    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    init {
        nodeConnection = NodeConnection(
            app = app,
            scope = viewModelScope,
            session = { session },
            connectionAllowed = { _screen.value is AppScreen.Main },
            onStatus = ::updateNodeStatus,
            onConnected = ::onNodeConnected,
            onHeartbeat = ::onNodeHeartbeat,
            onRecoveryConfigured = ::backupConversationIfNeeded,
        )
        chatController = ChatController(
            localAiRuntime = app.localAiRuntime,
            nodeApi = app.nodeApi,
            scope = viewModelScope,
            session = { session },
            connectedNode = { nodeConnection.current },
            conversationSync = conversationSync,
            onNodeUnavailable = { nodeConnection.disconnect(it.trusted) },
            readableError = ::readableChatError,
            message = ::message,
            onStateChanged = ::publishChat,
        )
        nodeConnection.start()
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
            name.isEmpty() -> message(R.string.error_user_name_required)
            name.length > 100 || name.any { it.isISOControl() } -> message(R.string.error_user_name_invalid)
            password.isEmpty() -> message(R.string.error_password_required)
            password != confirmation -> message(R.string.error_passwords_mismatch)
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
                conversationSync.reset()
                chatController.reset()
                openMain()
            } catch (_: Exception) {
                _screen.value = AppScreen.Setup(
                    canCancel = app.secureVault.profiles.isNotEmpty(),
                    error = message(R.string.error_user_creation_failed),
                )
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun unlock(password: String) {
        val locked = _screen.value as? AppScreen.Locked ?: return
        if (password.isEmpty()) {
            _screen.value = locked.copy(error = message(R.string.error_enter_password))
            return
        }
        _screen.value = locked.copy(error = null, busy = true)
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                val unlocked = withContext(Dispatchers.Default) {
                    app.secureVault.unlock(locked.profile.id, chars)
                }
                if (unlocked == null) {
                    _screen.value = locked.copy(error = message(R.string.error_incorrect_password))
                } else {
                    session = unlocked
                    val messages = withContext(Dispatchers.Default) {
                        app.secureVault.loadConversation(unlocked)
                    }
                    conversationSync.reset()
                    chatController.reset(messages)
                    openMain()
                }
            } catch (_: Exception) {
                session?.close()
                session = null
                _screen.value = locked.copy(error = message(R.string.error_source_data_unreadable))
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun logout() {
        pairingJob?.cancel()
        chatController.reset()
        nodeConnection.clear()
        conversationSync.reset()
        session?.close()
        session = null
        _screen.value = AppScreen.Accounts(app.secureVault.profiles)
    }

    fun selectAi(selection: AiSelection) = chatController.selectAi(selection)

    fun sendMessage(raw: String) = chatController.sendMessage(raw)

    fun cancelInference() = chatController.cancelInference()

    fun onForeground() {
        foreground = true
        nodeConnection.onForeground()
    }

    fun onBackground() {
        foreground = false
        pairingJob?.cancel()
        val connected = ((_screen.value as? AppScreen.Main)?.status as? NodeStatus.Connected)?.node
        nodeConnection.onBackground()
        if (_screen.value is AppScreen.Pairing || _screen.value is AppScreen.Recovery) {
            updateNodeStatus(nodeConnection.statusForCurrentNodes())
        }
        if (connected != null) updateNodeStatus(NodeStatus.PairedOffline(connected))
    }

    fun scan(node: DiscoveredNode) {
        _screen.value = AppScreen.Scanner(node)
    }

    fun cancelScanner() {
        _screen.value = mainScreen(nodeConnection.statusForCurrentNodes())
    }

    fun onQrScanned(raw: String) {
        val scanner = _screen.value as? AppScreen.Scanner ?: return
        val invitation = try {
            PairingPayloadParser.parse(raw)
        } catch (error: PairingPayloadException) {
            _screen.value = scanner.copy(error = pairingErrorMessage(error.error))
            return
        }
        if (invitation.nodeId != scanner.node.nodeIdHint) {
            _screen.value = scanner.copy(error = message(R.string.error_qr_code_wrong_node))
            return
        }
        if (invitation.recovery) {
            _screen.value = AppScreen.Recovery(scanner.node, invitation)
            return
        }
        val activeSession = session ?: return
        _screen.value = AppScreen.Pairing(invitation.nodeName)
        nodeConnection.cancelActiveAttempt()
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            try {
                val recovery = SourceCrypto.generateRecoveryMaterial(invitation.nodeId)
                val result = app.nodeApi.pair(
                    invitation,
                    activeSession.vault.identity,
                    recovery.recoveryKey,
                    recovery.envelope,
                )
                val trusted = result.trustedNode.copy(dataKey = recovery.dataKey)
                nodeConnection.saveTrustedNode(activeSession, trusted)
                nodeConnection.acceptConnection(scanner.node, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _screen.value = mainScreen(NodeStatus.Error(readableError(error)))
            }
        }
    }

    fun cancelRecovery() {
        _screen.value = mainScreen(nodeConnection.statusForCurrentNodes())
    }

    fun recover(rawRecoveryKey: String) {
        val recoveryScreen = _screen.value as? AppScreen.Recovery ?: return
        val recoveryKey = rawRecoveryKey.trim()
        if (!recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$"))) {
            _screen.value = recoveryScreen.copy(error = message(R.string.error_recovery_key_invalid))
            return
        }
        val activeSession = session ?: return
        _screen.value = recoveryScreen.copy(error = null, busy = true)
        nodeConnection.cancelActiveAttempt()
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
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
                nodeConnection.saveTrustedNode(activeSession, trusted)
                conversationSync.beginRecoveryRestore()
                chatController.beginRecoveryRestore()
                nodeConnection.acceptConnection(recoveryScreen.node, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _screen.value = recoveryScreen.copy(error = readableError(error), busy = false)
            }
        }
    }

    fun retry() = nodeConnection.retry()

    private fun openMain() {
        val displayName = session?.vault?.identity?.userDisplayName ?: return
        _screen.value = AppScreen.Main(NodeStatus.Searching, displayName, chatController.state)
        if (foreground) nodeConnection.onForeground()
    }

    private suspend fun onNodeConnected() {
        if (conversationSync.recoveryRestorePending) {
            restoreRecoveredConversation(R.string.error_recovery_data_pending)
        } else {
            synchronizeConversation()
        }
    }

    private suspend fun onNodeHeartbeat() {
        if (conversationSync.recoveryRestorePending) {
            restoreRecoveredConversation(R.string.error_data_pending)
        } else {
            backupConversationIfNeeded()
        }
    }

    private suspend fun synchronizeConversation() {
        conversationSync.synchronize(
            session,
            nodeConnection.current,
            chatController.state.messages,
            chatController::applySynchronizedMessages,
        )
    }

    private suspend fun restoreRecoveredConversation(@StringRes failureMessage: Int) {
        try {
            val messages = conversationSync.restoreRecovered(session, nodeConnection.current)
            chatController.completeRecoveryRestore(messages)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            chatController.failRecoveryRestore(message(failureMessage))
        }
    }

    private suspend fun backupConversationIfNeeded() {
        conversationSync.backupIfNeeded(session, nodeConnection.current, chatController.state.messages)
    }

    private fun publishChat(chat: ChatUiState) {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(chat = chat)
    }

    private fun updateNodeStatus(status: NodeStatus) {
        val current = _screen.value
        _screen.value = if (current is AppScreen.Main) current.copy(status = status, chat = chatController.state)
        else mainScreen(status)
    }

    private fun mainScreen(status: NodeStatus): AppScreen.Main = AppScreen.Main(
        status = status,
        userDisplayName = session?.vault?.identity?.userDisplayName.orEmpty(),
        chat = chatController.state,
    )

    private fun readableChatError(error: Exception): String = when {
        error is SourceApiException -> apiErrorMessage(error.code, R.string.error_node_ai_failed)
        error.message?.contains("model part", ignoreCase = true) == true ->
            message(R.string.error_local_model_unavailable)
        else -> message(R.string.error_ai_failed)
    }

    private fun readableError(error: Exception): String = when {
        error is SourceApiException -> apiErrorMessage(error.code, R.string.error_connection_failed)
        else -> message(R.string.error_node_unreachable)
    }

    private fun apiErrorMessage(code: String, @StringRes fallback: Int): String = message(
        when (code) {
            "pairing_unavailable" -> R.string.error_invitation_unavailable
            "duplicate_client" -> R.string.error_client_already_paired
            "pairing_proof_failed" -> R.string.error_pairing_proof_failed
            "invalid_recovery_key" -> R.string.error_recovery_key_incorrect
            "recovery_not_configured" -> R.string.error_recovery_not_configured
            "authentication_required" -> R.string.error_authentication_required
            "model_unavailable" -> R.string.error_node_model_unavailable
            "chat_rate_limited" -> R.string.error_chat_rate_limited
            "storage_quota_exceeded" -> R.string.error_storage_quota_exceeded
            "node_stream_interrupted_after_output" -> R.string.error_node_stream_interrupted
            else -> fallback
        },
    )

    private fun pairingErrorMessage(error: PairingPayloadError): String = message(
        when (error) {
            PairingPayloadError.INVALID_SIZE -> R.string.error_qr_invalid_size
            PairingPayloadError.NOT_SOURCE_CODE -> R.string.error_qr_not_source_code
            PairingPayloadError.MISSING_CONTENT -> R.string.error_qr_missing_content
            PairingPayloadError.UNEXPECTED_FIELDS -> R.string.error_qr_unexpected_fields
            PairingPayloadError.UNSUPPORTED_PROTOCOL -> R.string.error_qr_unsupported_protocol
            PairingPayloadError.INVALID_NODE_IDENTITY -> R.string.error_qr_invalid_node_identity
            PairingPayloadError.INVALID_NODE_KEY -> R.string.error_qr_invalid_node_key
            PairingPayloadError.NODE_IDENTITY_MISMATCH -> R.string.error_qr_node_identity_mismatch
            PairingPayloadError.INVALID_NODE_NAME -> R.string.error_qr_invalid_node_name
            PairingPayloadError.INVALID_INVITATION -> R.string.error_qr_invalid_invitation
            PairingPayloadError.INVALID_EXPIRATION -> R.string.error_qr_invalid_expiration
            PairingPayloadError.EXPIRED_INVITATION -> R.string.error_qr_expired_invitation
            PairingPayloadError.INVALID_ACTION -> R.string.error_qr_invalid_action
            PairingPayloadError.INVALID_CA_CERTIFICATE -> R.string.error_qr_invalid_ca_certificate
            PairingPayloadError.EXPIRED_CA_CERTIFICATE -> R.string.error_qr_expired_ca_certificate
            PairingPayloadError.INVALID_ENDPOINT -> R.string.error_qr_invalid_endpoint
            PairingPayloadError.NON_LOCAL_ENDPOINT -> R.string.error_qr_non_local_endpoint
            PairingPayloadError.MALFORMED -> R.string.error_qr_malformed
            PairingPayloadError.INVALID_ENCODING -> R.string.error_qr_invalid_encoding
        },
    )

    private fun message(@StringRes resourceId: Int): String = app.getString(resourceId)

    override fun onCleared() {
        pairingJob?.cancel()
        chatController.cancelInference()
        nodeConnection.close()
        session?.close()
        super.onCleared()
    }
}
