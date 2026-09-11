package com.source.client.ui

import com.source.client.R
import com.source.client.ai.AiRuntimeRouter
import com.source.client.ai.SourceAiContent
import com.source.client.ai.SourceAiMessage
import com.source.client.ai.SourceAiRequest
import com.source.client.ai.SourceAiRole
import com.source.client.ai.SourceAiRuntime
import com.source.client.storage.ChatData
import com.source.client.storage.SourceDataSync
import com.source.client.model.ConnectedNode
import com.source.client.model.AiSelection
import com.source.client.model.ChatConversations
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import com.source.client.security.VaultSession
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal class ChatController(
    private val aiRuntime: AiRuntimeRouter,
    private val scope: CoroutineScope,
    private val session: () -> VaultSession?,
    private val connectedNode: () -> ConnectedNode?,
    private val conversationSync: SourceDataSync<ChatConversations>,
    private val readableError: (Exception) -> String,
    private val message: (Int) -> String,
    private val onStateChanged: (ChatUiState) -> Unit,
) {
    private var inferenceJob: Job? = null
    private var activeRunId: String? = null
    var conversations = ChatData.emptyValue
        private set

    var state = ChatUiState()
        private set

    fun reset(
        loaded: ChatConversations = ChatData.emptyValue,
        startFreshConversation: Boolean = false,
    ): ChatConversations {
        activeRunId = null
        inferenceJob?.cancel()
        inferenceJob = null
        aiRuntime.select(AiSelection.AUTO)
        conversations = if (startFreshConversation || loaded.activeConversation == null) {
            loaded.withFreshConversation()
        } else {
            loaded
        }
        val active = checkNotNull(conversations.activeConversation)
        update(
            ChatUiState(
                conversationId = active.id,
                conversationCreatedAtMillis = active.createdAtMillis,
                messages = active.messages,
            ),
        )
        return conversations
    }

    fun newConversation() {
        if (state.busy) return
        val activeSession = session() ?: return
        conversations = conversations.withFreshConversation()
        val active = checkNotNull(conversations.activeConversation)
        update(
            state.copy(
                conversationId = active.id,
                conversationCreatedAtMillis = active.createdAtMillis,
                messages = emptyList(),
                streamingMessage = null,
                error = null,
            ),
        )
        conversationSync.changed()
        scope.launch {
            conversationSync.persist(activeSession, conversations)
            conversationSync.backupIfNeeded(session(), connectedNode(), conversations)
            onStateChanged(state)
        }
    }

    fun selectAi(selection: AiSelection) {
        aiRuntime.select(selection)
        update(state.copy(selection = selection, error = null))
    }

    fun sendMessage(raw: String) {
        val content = raw.trim()
        if (content.isEmpty() || inferenceJob?.isActive == true) return
        if (conversationSync.recoveryRestorePending) {
            update(state.copy(error = message(R.string.error_recovery_restore_in_progress)))
            return
        }
        if (content.length > MAX_MESSAGE_CHARACTERS) {
            update(state.copy(error = message(R.string.error_message_too_long)))
            return
        }
        val activeSession = session() ?: return
        val runId = UUID.randomUUID().toString()
        activeRunId = runId
        updateMessages(
            state.messages + ChatMessage.user(content),
            state.copy(
                streamingMessage = null,
                busy = true,
                error = null,
            ),
        )
        conversationSync.changed()

        inferenceJob = scope.launch {
            try {
                conversationSync.persist(activeSession, conversations)
                val context = boundedChatContext(state.messages)
                val assistant = streamAnswer(
                    aiRuntime,
                    context,
                    runId,
                    state.conversationId,
                )
                if (activeRunId != runId) return@launch
                updateMessages(
                    state.messages + assistant.copy(
                        content = assistant.content.take(MAX_MESSAGE_CHARACTERS),
                    ),
                    state.copy(
                        streamingMessage = null,
                        busy = false,
                        error = null,
                    ),
                )
                conversationSync.changed()
                conversationSync.persist(activeSession, conversations)
            } catch (error: CancellationException) {
                if (activeRunId == runId) update(state.copy(streamingMessage = null, busy = false))
                throw error
            } catch (error: Exception) {
                update(
                    state.copy(
                        streamingMessage = null,
                        busy = false,
                        error = readableError(error),
                    ),
                )
            } finally {
                if (activeRunId == runId) activeRunId = null
                conversationSync.backupIfNeeded(session(), connectedNode(), conversations)
                onStateChanged(state)
            }
        }
    }

    fun cancelInference() {
        if (activeRunId == null) return
        activeRunId = null
        inferenceJob?.cancel()
        inferenceJob = null
        update(state.copy(streamingMessage = null, busy = false, error = null))
    }

    fun applySynchronizedConversations(data: ChatConversations) {
        applyConversations(data, busy = state.busy)
    }

    fun beginRecoveryRestore() {
        update(state.copy(busy = true, error = null))
    }

    fun completeRecoveryRestore(data: ChatConversations?) {
        if (data == null) {
            update(state.copy(busy = false, error = null))
        } else {
            applyConversations(data, busy = false)
        }
    }

    fun failRecoveryRestore(error: String) {
        update(state.copy(busy = false, error = error))
    }

    private suspend fun streamAnswer(
        runtime: SourceAiRuntime,
        messages: List<ChatMessage>,
        runId: String,
        conversationId: String,
    ): ChatMessage {
        val draft = ChatMessage(id = runId, role = ChatRole.ASSISTANT, content = "")
        val content = collectAiResponse(runtime, sourceAiRequest(messages, runId, conversationId)) { delta ->
            if (activeRunId == runId) update(state.copy(streamingMessage = draft.copy(content = delta)))
        }
        return draft.copy(content = content)
    }

    private fun sourceAiRequest(
        messages: List<ChatMessage>,
        runId: String,
        conversationId: String,
    ) = SourceAiRequest(
        runId = runId,
        conversationId = conversationId,
        messages = messages.map { chatMessage ->
            SourceAiMessage(
                role = when (chatMessage.role) {
                    ChatRole.USER -> SourceAiRole.USER
                    ChatRole.ASSISTANT -> SourceAiRole.ASSISTANT
                },
                content = listOf(SourceAiContent.Text(chatMessage.content)),
            )
        },
    )

    private fun update(next: ChatUiState) {
        state = next
        onStateChanged(next)
    }

    private fun updateMessages(messages: List<ChatMessage>, next: ChatUiState) {
        val active = checkNotNull(conversations.activeConversation).copy(messages = messages)
        conversations = conversations.replaceActive(active)
        update(next.copy(messages = messages))
    }

    private fun applyConversations(data: ChatConversations, busy: Boolean) {
        conversations = if (data.activeConversation == null) data.withFreshConversation() else data
        val active = checkNotNull(conversations.activeConversation)
        update(
            state.copy(
                conversationId = active.id,
                conversationCreatedAtMillis = active.createdAtMillis,
                messages = active.messages,
                streamingMessage = null,
                busy = busy,
                error = null,
            ),
        )
    }

    private companion object {
        const val MAX_MESSAGE_CHARACTERS = 4_000
    }
}
