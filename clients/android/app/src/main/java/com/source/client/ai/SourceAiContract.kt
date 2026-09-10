package com.source.client.ai

import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class SourceAiCapability { TEXT, VISION }
enum class SourceAiReasoning { OFF }

enum class SourceAiRole { USER, ASSISTANT }

sealed interface SourceAiContent {
    data class Text(val text: String) : SourceAiContent
    data class Image(val uri: String, val mimeType: String? = null) : SourceAiContent
}

data class SourceAiMessage(
    val role: SourceAiRole,
    val content: List<SourceAiContent>,
)

data class SourceAiRequest(
    val runId: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val messages: List<SourceAiMessage>,
)

data class SourceAiCapabilities(
    val capabilities: Set<SourceAiCapability>,
    val streaming: Boolean,
    val cancellation: Boolean,
    val maximumContextTokens: Int,
    val promptPolicy: String = PROMPT_POLICY_NONE_V1,
    val reasoning: SourceAiReasoning = SourceAiReasoning.OFF,
)

sealed interface SourceAiEvent {
    val runId: String

    data class Started(override val runId: String) : SourceAiEvent
    data class Delta(override val runId: String, val sequence: Long, val text: String) : SourceAiEvent
    data class Completed(
        override val runId: String,
        val finishReason: String,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
    ) : SourceAiEvent
    data class Failed(override val runId: String, val code: String, val retryable: Boolean) : SourceAiEvent
}

interface SourceAiRuntime {
    val capabilities: SourceAiCapabilities
    fun stream(request: SourceAiRequest): Flow<SourceAiEvent>
}

const val SOURCE_AI_CONTRACT_VERSION = 1
const val PROMPT_POLICY_NONE_V1 = "none-v1"
