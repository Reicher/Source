package com.source.client.ai

import com.source.client.model.AiModelMetadata
import java.util.UUID
import kotlinx.coroutines.flow.Flow

enum class SourceAiCapability { TEXT, VISION }
enum class SourceAiReasoning { OFF }
enum class SourceAiWorkload { INTERACTIVE, BACKGROUND }

enum class SourceAiAvailability {
    MODEL_NOT_INSTALLED,
    MODEL_PRESENT,
    MODEL_LOAD_FAILED,
    READY,
    INFERENCE_FAILED,
}

enum class SourceAiFailureCode(val wireValue: String, val retryable: Boolean) {
    MODEL_NOT_INSTALLED("model_not_installed", false),
    MODEL_LOAD_FAILED("model_load_failed", true),
    INFERENCE_FAILED("inference_failed", true),
    TIMEOUT("timeout", true),
    CANCELLED("cancelled", false),
    NODE_UNAVAILABLE("node_unavailable", true),
    STREAM_INTERRUPTED("node_stream_interrupted_after_output", true),
}

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
    val workload: SourceAiWorkload = SourceAiWorkload.INTERACTIVE,
    val timeoutMillis: Long = DEFAULT_SOURCE_AI_TIMEOUT_MILLIS,
) {
    init {
        require(timeoutMillis in 1_000..MAXIMUM_SOURCE_AI_TIMEOUT_MILLIS) { "Invalid AI timeout" }
    }
}

data class SourceAiCapabilities(
    val capabilities: Set<SourceAiCapability>,
    val streaming: Boolean,
    val cancellation: Boolean,
    val maximumContextTokens: Int,
    val promptPolicy: String = PROMPT_POLICY_NONE_V1,
    val reasoning: SourceAiReasoning = SourceAiReasoning.OFF,
)

data class SourceAiRuntimeState(
    val availability: SourceAiAvailability,
    val model: AiModelMetadata? = null,
    val capabilities: SourceAiCapabilities? = null,
    val failureCode: String? = null,
) {
    val isUsable: Boolean
        get() = availability in setOf(SourceAiAvailability.READY, SourceAiAvailability.INFERENCE_FAILED) &&
            model != null && capabilities != null

    init {
        require(availability != SourceAiAvailability.READY || model != null) {
            "A ready AI runtime must identify its model"
        }
        require(availability != SourceAiAvailability.READY || capabilities != null) {
            "A ready AI runtime must publish capabilities"
        }
        require(availability != SourceAiAvailability.INFERENCE_FAILED || model != null && capabilities != null) {
            "An inference failure must retain model capabilities for retry"
        }
        require(
            availability !in setOf(SourceAiAvailability.MODEL_LOAD_FAILED, SourceAiAvailability.INFERENCE_FAILED) ||
                !failureCode.isNullOrBlank(),
        ) { "A failed AI runtime must publish a failure code" }
    }
}

sealed interface SourceAiEvent {
    val runId: String

    data class Started(
        override val runId: String,
        val model: AiModelMetadata? = null,
    ) : SourceAiEvent
    data class Delta(override val runId: String, val sequence: Long, val text: String) : SourceAiEvent
    data class Completed(
        override val runId: String,
        val finishReason: String,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
        val reasoningBytes: Int? = null,
    ) : SourceAiEvent
    data class Failed(override val runId: String, val code: String, val retryable: Boolean) : SourceAiEvent
}

interface SourceAiRuntime {
    val runtimeState: SourceAiRuntimeState
    val capabilities: SourceAiCapabilities
        get() = checkNotNull(runtimeState.capabilities) { "The AI runtime has no current capabilities" }

    fun stream(request: SourceAiRequest): Flow<SourceAiEvent>
}

const val SOURCE_AI_CONTRACT_VERSION = 1
const val PROMPT_POLICY_NONE_V1 = "none-v1"
const val DEFAULT_SOURCE_AI_TIMEOUT_MILLIS = 300_000L
const val MAXIMUM_SOURCE_AI_TIMEOUT_MILLIS = 600_000L
