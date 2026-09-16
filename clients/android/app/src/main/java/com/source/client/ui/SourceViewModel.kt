package com.source.client.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.source.client.R
import com.source.client.SourceClientApplication
import com.source.client.ai.AiRuntimeRouter
import com.source.client.knowledge.BronzeTextSource
import com.source.client.storage.ChatData
import com.source.client.storage.EncryptedBlobStore
import com.source.client.storage.SourceDataStore
import com.source.client.storage.SourceDataSync
import com.source.client.model.AiSelection
import com.source.client.model.ChatMessage
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeConnectionState
import com.source.client.model.NodeDisconnectReason
import com.source.client.model.PairingInvitation
import com.source.client.model.VaultProfile
import com.source.client.protocol.PairingPayloadException
import com.source.client.protocol.PairingPayloadError
import com.source.client.protocol.PairingPayloadParser
import com.source.client.protocol.SourceApiException
import com.source.client.security.SourceCrypto
import com.source.client.security.VaultSession
import com.source.client.security.VaultUnlockResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val conversationId: String = "",
    val conversationCreatedAtMillis: Long = 0L,
    val messages: List<ChatMessage> = emptyList(),
    val streamingMessage: ChatMessage? = null,
    val selection: AiSelection = AiSelection.AUTO,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface LibraryPreviewUiState {
    val itemId: String
    val filename: String

    data class Loading(
        override val itemId: String,
        override val filename: String,
    ) : LibraryPreviewUiState

    data class Ready(
        override val itemId: String,
        override val filename: String,
        val content: LibraryPreviewContent,
    ) : LibraryPreviewUiState

    data class Failed(
        override val itemId: String,
        override val filename: String,
        val message: String,
    ) : LibraryPreviewUiState
}

enum class MainDestination { CHAT, LIBRARY }

sealed interface AppScreen {
    data class Accounts(val profiles: List<VaultProfile>) : AppScreen
    data class Setup(val canCancel: Boolean = false, val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Locked(val profile: VaultProfile, val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Main(
        val status: NodeConnectionState,
        val userDisplayName: String,
        val chat: ChatUiState = ChatUiState(),
        val library: LibraryUiState = LibraryUiState(),
        val knowledge: KnowledgeUiState = KnowledgeUiState(),
        val destination: MainDestination = MainDestination.CHAT,
        val libraryPreview: LibraryPreviewUiState? = null,
        val knowledgeSourceId: String? = null,
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
    private val sourceDataStore = SourceDataStore(app.secureVault)
    private val conversationSync = SourceDataSync(ChatData, sourceDataStore, app.nodeApi)
    private val nodeConnection: NodeConnection
    private val chatController: ChatController
    private val libraryController: LibraryController
    private val silverController: SilverController
    private var session: VaultSession? = null
    private var foreground = false
    private var pairingJob: Job? = null
    private var mainDestination = MainDestination.CHAT

    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    init {
        nodeConnection = NodeConnection(
            app = app,
            scope = viewModelScope,
            session = { session },
            onState = ::updateNodeState,
            onConnected = ::onNodeConnected,
            onHeartbeat = ::onNodeHeartbeat,
            onRecoveryConfigured = ::backupConversationAfterRecoveryConfigured,
        )
        val aiRuntime = AiRuntimeRouter(
            local = app.localAiRuntime,
            nodeApi = app.nodeApi,
            connectedNode = { nodeConnection.current },
            onNodeUnavailable = { nodeConnection.disconnect(it.trusted) },
        )
        chatController = ChatController(
            aiRuntime = aiRuntime,
            scope = viewModelScope,
            session = { session },
            connectedNode = { nodeConnection.current },
            conversationSync = conversationSync,
            readableError = ::readableChatError,
            message = ::message,
            onStateChanged = ::publishChat,
        )
        libraryController = LibraryController(
            contentResolver = app.contentResolver,
            blobStore = EncryptedBlobStore(app),
            localStore = sourceDataStore,
            nodeApi = app.nodeApi,
            session = { session },
            connectedNode = { nodeConnection.current },
            message = ::message,
            onStateChanged = ::publishLibrary,
        )
        silverController = SilverController(
            scope = viewModelScope,
            localStore = sourceDataStore,
            nodeApi = app.nodeApi,
            session = { session },
            connectedNode = { nodeConnection.current },
            bronzeSources = ::bronzeTextSources,
            onStateChanged = ::publishSilver,
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
                val conversations = chatController.reset(
                    startFreshConversation = app.shouldStartFreshConversationOnLogin(),
                )
                withContext(Dispatchers.Default) {
                    sourceDataStore.save(checkNotNull(session), ChatData, conversations)
                }
                libraryController.reset(session)
                silverController.reset(session)
                app.markLoginConversationStarted()
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
                val unlockResult = withContext(Dispatchers.Default) {
                    app.secureVault.unlockDetailed(locked.profile.id, chars)
                }
                when (unlockResult) {
                    VaultUnlockResult.IncorrectPassword -> {
                        _screen.value = locked.copy(error = message(R.string.error_incorrect_password))
                    }
                    VaultUnlockResult.Unreadable -> {
                        _screen.value = locked.copy(error = message(R.string.error_source_data_unreadable))
                    }
                    is VaultUnlockResult.Success -> {
                        val unlocked = unlockResult.session
                        session = unlocked
                        val conversations = withContext(Dispatchers.Default) {
                            sourceDataStore.load(unlocked, ChatData)
                        }
                        conversationSync.reset()
                        val activeConversations = chatController.reset(
                            conversations,
                            startFreshConversation = app.shouldStartFreshConversationOnLogin(),
                        )
                        withContext(Dispatchers.Default) {
                            sourceDataStore.save(unlocked, ChatData, activeConversations)
                        }
                        libraryController.reset(unlocked)
                        silverController.reset(unlocked)
                        app.markLoginConversationStarted()
                        openMain()
                    }
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
        clearSession()
        _screen.value = AppScreen.Accounts(app.secureVault.profiles)
    }

    fun deleteCurrentUser() {
        val profileId = session?.profileId ?: return
        val profile = app.secureVault.profiles.firstOrNull { it.id == profileId } ?: return
        clearSession()
        deleteLocalProfile(profile)
    }

    fun deleteLockedUser() {
        val locked = _screen.value as? AppScreen.Locked ?: return
        _screen.value = locked.copy(error = null, busy = true)
        deleteLocalProfile(locked.profile)
    }

    private fun deleteLocalProfile(profile: VaultProfile) {
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { app.secureVault.deleteProfile(profile.id) }
            }
            if (result.isFailure) {
                _screen.value = AppScreen.Locked(
                    app.secureVault.profiles.firstOrNull { it.id == profile.id } ?: profile,
                    error = message(R.string.error_user_deletion_failed),
                )
                return@launch
            }
            val remainingProfiles = app.secureVault.profiles
            _screen.value = if (remainingProfiles.isEmpty()) AppScreen.Setup() else AppScreen.Accounts(remainingProfiles)
        }
    }

    private fun clearSession() {
        pairingJob?.cancel()
        pairingJob = null
        chatController.reset()
        nodeConnection.clear()
        conversationSync.reset()
        session?.close()
        session = null
        mainDestination = MainDestination.CHAT
        viewModelScope.launch {
            libraryController.reset()
            silverController.reset()
        }
    }

    fun selectAi(selection: AiSelection) = chatController.selectAi(selection)

    fun sendMessage(raw: String) = chatController.sendMessage(raw)

    fun newConversation() = chatController.newConversation()

    fun cancelInference() = chatController.cancelInference()

    fun selectDestination(destination: MainDestination) {
        val main = _screen.value as? AppScreen.Main ?: return
        mainDestination = destination
        _screen.value = main.copy(destination = destination)
    }

    fun importFile(uri: Uri) {
        viewModelScope.launch {
            if (libraryController.import(uri)) {
                libraryController.syncAll()
                silverController.refresh()
            }
        }
    }

    fun removeLibraryItemFromDevice(itemId: String) {
        viewModelScope.launch {
            libraryController.removeFromDevice(itemId)
        }
    }

    fun deleteLibraryItemFromSource(itemId: String) {
        if (itemId.startsWith("conversation:")) {
            chatController.deleteConversation(itemId.removePrefix("conversation:"))
            silverController.removeSource(itemId)
            return
        }
        viewModelScope.launch {
            libraryController.deleteFromSource(itemId)
            silverController.removeSource(itemId)
            libraryController.syncAll()
        }
    }

    fun openLibraryItem(itemId: String) {
        val main = _screen.value as? AppScreen.Main ?: return
        val item = presentedLibrary().items.firstOrNull { it.id == itemId } ?: return
        if (item.previewKind == null) return
        _screen.value = main.copy(libraryPreview = LibraryPreviewUiState.Loading(item.id, item.filename))
        if (item.sourceType == "conversation") {
            val conversationId = item.id.removePrefix("conversation:")
            val conversation = chatController.conversations.conversations.firstOrNull { it.id == conversationId }
            val content = conversation?.let(ChatData::encodedConversation)
            _screen.value = (_screen.value as? AppScreen.Main)?.copy(
                libraryPreview = if (content == null) {
                    LibraryPreviewUiState.Failed(item.id, item.filename, message(R.string.error_library_preview_failed))
                } else {
                    LibraryPreviewUiState.Ready(
                        item.id,
                        item.filename,
                        LibraryPreviewContent.Text(content),
                    )
                },
            ) ?: return
            return
        }
        viewModelScope.launch {
            val preview = runCatching { libraryController.readPreview(item.id) }.getOrNull()
            val current = _screen.value as? AppScreen.Main ?: return@launch
            if (current.libraryPreview?.itemId != item.id) return@launch
            _screen.value = current.copy(
                libraryPreview = if (preview == null) {
                    LibraryPreviewUiState.Failed(item.id, item.filename, message(R.string.error_library_preview_failed))
                } else {
                    LibraryPreviewUiState.Ready(item.id, preview.filename, preview.content)
                },
            )
        }
    }

    fun closeLibraryPreview() {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(libraryPreview = null)
    }

    fun openKnowledgeSource(itemId: String) {
        val main = _screen.value as? AppScreen.Main ?: return
        if (main.knowledge.sources.none { it.id == itemId }) return
        mainDestination = MainDestination.LIBRARY
        _screen.value = main.copy(destination = MainDestination.LIBRARY, knowledgeSourceId = itemId)
    }

    fun closeKnowledgeSource() {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(knowledgeSourceId = null)
    }

    fun clearLibraryFeedback() = libraryController.clearFeedback()

    fun onForeground() {
        foreground = true
        nodeConnection.onForeground()
    }

    fun onBackground() {
        foreground = false
        pairingJob?.cancel()
        nodeConnection.onBackground()
    }

    fun scan(node: DiscoveredNode) {
        _screen.value = AppScreen.Scanner(node)
    }

    fun cancelScanner() {
        _screen.value = mainScreen(nodeConnection.stateForCurrentNodes())
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
        val activeSession = session ?: return
        val authority = activeSession.vault.authoritativeNode
        if (authority != null && authority.nodeId != invitation.nodeId) {
            _screen.value = scanner.copy(error = message(R.string.error_profile_bound_to_another_node))
            return
        }
        if (invitation.recovery) {
            _screen.value = AppScreen.Recovery(scanner.node, invitation)
            nodeConnection.beginRecovery(scanner.node, invitation.nodeName)
            return
        }
        _screen.value = AppScreen.Pairing(invitation.nodeName)
        nodeConnection.beginPairing(scanner.node, invitation.nodeName)
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
                nodeConnection.saveAuthoritativeNode(activeSession, trusted)
                nodeConnection.acceptConnection(scanner.node, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                nodeConnection.fail(readableError(error))
            }
        }
    }

    fun cancelRecovery() {
        nodeConnection.cancelConnectionFlow()
        _screen.value = mainScreen(nodeConnection.state)
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
                nodeConnection.saveAuthoritativeNode(activeSession, trusted)
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
        _screen.value = AppScreen.Main(
            NodeConnectionState.Discovering,
            displayName,
            chatController.state,
            presentedLibrary(),
            presentedKnowledge(),
            mainDestination,
        )
        if (foreground) nodeConnection.onForeground()
    }

    private suspend fun onNodeConnected() {
        if (conversationSync.recoveryRestorePending) {
            restoreRecoveredConversation(R.string.error_recovery_data_pending)
        } else {
            synchronizeConversation()
        }
        libraryController.syncAll(resetAcknowledgements = true)
        silverController.synchronize()
    }

    private suspend fun onNodeHeartbeat() {
        if (conversationSync.recoveryRestorePending) {
            restoreRecoveredConversation(R.string.error_data_pending)
        } else {
            backupConversationIfNeeded()
        }
        libraryController.syncAll()
        silverController.synchronize()
    }

    private suspend fun synchronizeConversation() {
        conversationSync.synchronize(
            session,
            nodeConnection.current,
            chatController.conversations,
            chatController::applySynchronizedConversations,
        )
    }

    private suspend fun restoreRecoveredConversation(@StringRes failureMessage: Int) {
        try {
            val conversations = conversationSync.restoreRecovered(session, nodeConnection.current)
            chatController.completeRecoveryRestore(conversations)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            chatController.failRecoveryRestore(message(failureMessage))
        }
    }

    private suspend fun backupConversationIfNeeded() {
        conversationSync.backupIfNeeded(
            session,
            nodeConnection.current,
            chatController.conversations,
            chatController::applySynchronizedConversations,
        )
    }

    private suspend fun backupConversationAfterRecoveryConfigured() {
        conversationSync.requireBackup()
        backupConversationIfNeeded()
        libraryController.syncAll(resetAcknowledgements = true)
    }

    private fun publishChat(chat: ChatUiState) {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(chat = chat, library = presentedLibrary(), knowledge = presentedKnowledge())
    }

    private fun publishLibrary(library: LibraryUiState) {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(library = presentedLibrary(library), knowledge = presentedKnowledge(library))
    }

    private fun publishSilver(@Suppress("UNUSED_PARAMETER") silver: SilverUiState) {
        val main = _screen.value as? AppScreen.Main ?: return
        _screen.value = main.copy(library = presentedLibrary(), knowledge = presentedKnowledge())
    }

    private fun updateNodeState(status: NodeConnectionState) {
        val current = _screen.value
        _screen.value = when {
            current is AppScreen.Main -> current.copy(status = status, chat = chatController.state)
            current is AppScreen.Pairing && status.nodeStorageUsable() -> mainScreen(status)
            current is AppScreen.Pairing && status is NodeConnectionState.Failed -> mainScreen(status)
            current is AppScreen.Recovery && status.nodeStorageUsable() -> mainScreen(status)
            current is AppScreen.Pairing && status is NodeConnectionState.Disconnected -> mainScreen(status)
            current is AppScreen.Recovery && status is NodeConnectionState.Disconnected &&
                status.reason == NodeDisconnectReason.BACKGROUND -> mainScreen(status)
            else -> current
        }
    }

    private fun mainScreen(status: NodeConnectionState): AppScreen.Main = AppScreen.Main(
        status = status,
        userDisplayName = session?.vault?.identity?.userDisplayName.orEmpty(),
        chat = chatController.state,
        library = presentedLibrary(),
        knowledge = presentedKnowledge(),
        destination = mainDestination,
    )

    private fun presentedLibrary(raw: LibraryUiState = libraryController.state): LibraryUiState =
        withSilverState(withConversationLibraryItems(
            raw,
            chatController.conversations,
            conversationsBackedUp = conversationSync.isBackedUp,
        ), silverController.state)

    private fun presentedKnowledge(raw: LibraryUiState = libraryController.state): KnowledgeUiState =
        buildKnowledgeUiState(
            silverController.state.dataset,
            withConversationLibraryItems(
                raw,
                chatController.conversations,
                conversationsBackedUp = conversationSync.isBackedUp,
            ),
        )

    private suspend fun bronzeTextSources(): List<BronzeTextSource> =
        libraryController.bronzeTextSources() + chatController.conversations.conversations.mapNotNull { conversation ->
            if (!conversationSync.isBackedUp) return@mapNotNull null
            val text = ChatData.refinementText(conversation)
            if (text.isBlank()) null else BronzeTextSource(
                id = "conversation:${conversation.id}",
                name = conversationFilename(conversation.createdAtMillis),
                sourceType = "conversation",
                contentSha256 = ChatData.refinementContentSha256(conversation),
                text = text,
            )
        }

    private fun readableChatError(error: Exception): String = when {
        error is SourceAiFailureException -> apiErrorMessage(error.code, R.string.error_ai_failed)
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
            "model_unavailable", "node_unavailable" -> R.string.error_node_model_unavailable
            "model_not_installed", "model_load_failed" -> R.string.error_local_model_unavailable
            "chat_rate_limited" -> R.string.error_chat_rate_limited
            "storage_quota_exceeded" -> R.string.error_storage_quota_exceeded
            "silver_source_too_large" -> R.string.error_silver_source_too_large
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
