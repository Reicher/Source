package com.source.client.ui

import com.source.client.R
import com.source.client.ai.AiRuntimeRouter
import com.source.client.ai.SourceAiContent
import com.source.client.ai.SourceAiMessage
import com.source.client.ai.SourceAiRequest
import com.source.client.ai.SourceAiRole
import com.source.client.ai.SourceAiRuntime
import com.source.client.storage.SourceDataSync
import com.source.client.model.ConnectedNode
import com.source.client.model.AiSelection
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
    private val conversationSync: SourceDataSync<List<ChatMessage>>,
    private val readableError: (Exception) -> String,
    private val message: (Int) -> String,
    private val onStateChanged: (ChatUiState) -> Unit,
) {
    private var inferenceJob: Job? = null
    private var activeRunId: String? = null

    var state = ChatUiState()
        private set

    fun reset(messages: List<ChatMessage> = emptyList()) {
        activeRunId = null
        inferenceJob?.cancel()
        inferenceJob = null
        aiRuntime.select(AiSelection.AUTO)
        update(ChatUiState(messages = messages))
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
        update(
            state.copy(
                messages = state.messages + ChatMessage.user(content),
                streamingMessage = null,
                busy = true,
                error = null,
            ),
        )
        conversationSync.changed()

        inferenceJob = scope.launch {
            try {
                conversationSync.persist(activeSession, state.messages)
                val context = boundedChatContext(state.messages)
                val assistant = streamAnswer(
                    aiRuntime,
                    context,
                    runId,
                    activeSession.vault.identity.userId,
                )
                if (activeRunId != runId) return@launch
                update(
                    state.copy(
                        messages = state.messages + assistant.copy(
                            content = assistant.content.take(MAX_MESSAGE_CHARACTERS),
                        ),
                        streamingMessage = null,
                        busy = false,
                        error = null,
                    ),
                )
                conversationSync.changed()
                conversationSync.persist(activeSession, state.messages)
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
                conversationSync.backupIfNeeded(session(), connectedNode(), state.messages)
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

    fun applySynchronizedMessages(messages: List<ChatMessage>) {
        update(state.copy(messages = messages))
    }

    fun beginRecoveryRestore() {
        update(state.copy(busy = true, error = null))
    }

    fun completeRecoveryRestore(messages: List<ChatMessage>?) {
        update(state.copy(messages = messages ?: state.messages, busy = false, error = null))
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

    private companion object {
        const val MAX_MESSAGE_CHARACTERS = 4_000
    }
}
