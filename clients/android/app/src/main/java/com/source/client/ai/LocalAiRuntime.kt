package com.source.client.ai

import android.content.Context
import com.source.client.model.AiModelMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class LocalAiRuntime(context: Context) : SourceAiRuntime {
    private val native = LlamaCppNative(context.applicationContext)
    private val inferenceMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val capabilities = SourceAiCapabilities(
        capabilities = setOf(SourceAiCapability.TEXT),
        streaming = true,
        cancellation = true,
        maximumContextTokens = CONTEXT_TOKENS,
    )

    override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = callbackFlow {
        validate(request)
        trySendBlocking(SourceAiEvent.Started(request.runId, LOCAL_AI_MODEL))
        var sequence = 0L
        val generation = launch(Dispatchers.IO) {
            inferenceMutex.withLock {
                try {
                    val result = native.generate(
                        request = request,
                        contextTokens = CONTEXT_TOKENS,
                        maximumOutputTokens = MAXIMUM_OUTPUT_TOKENS,
                        threads = INFERENCE_THREADS,
                        onToken = { text ->
                            if (text.isNotEmpty()) {
                                val sent = trySendBlocking(SourceAiEvent.Delta(request.runId, sequence, text))
                                if (sent.isSuccess) sequence += 1 else native.cancel(request.runId)
                            }
                        },
                    )
                    if (result.finishReason != "cancelled") {
                        trySendBlocking(
                            SourceAiEvent.Completed(
                                runId = request.runId,
                                finishReason = result.finishReason,
                                inputTokens = result.inputTokens,
                                outputTokens = result.outputTokens,
                            ),
                        )
                    }
                    close()
                } catch (_: CancellationException) {
                    close()
                } catch (error: Exception) {
                    trySendBlocking(
                        SourceAiEvent.Failed(
                            runId = request.runId,
                            code = errorCode(error),
                            retryable = false,
                        ),
                    )
                    close(error)
                }
            }
        }
        awaitClose {
            native.cancel(request.runId)
            generation.cancel()
        }
    }.buffer(LOCAL_AI_EVENT_BUFFER_CAPACITY)

    fun releaseMemory() {
        runtimeScope.launch {
            inferenceMutex.withLock { native.close() }
        }
    }

    private fun validate(request: SourceAiRequest) {
        require(request.messages.isNotEmpty()) { "At least one message is required" }
        require(request.messages.last().role == SourceAiRole.USER) { "The final message must be from the user" }
        require(request.messages.size <= MAXIMUM_MESSAGES) { "Too many AI messages" }
        require(request.messages.all { message ->
            message.content.isNotEmpty() && message.content.all { it is SourceAiContent.Text }
        }) { "The local runtime supports non-empty text messages only" }
    }

    private fun errorCode(error: Exception): String = when {
        error.message?.contains("token budget", ignoreCase = true) == true -> "context_exhausted"
        error.message?.contains("output budget", ignoreCase = true) == true -> "output_exhausted"
        error.message?.contains("model part", ignoreCase = true) == true -> "model_missing"
        else -> "inference_failed"
    }

    private companion object {
        const val CONTEXT_TOKENS = 4_096
        const val MAXIMUM_OUTPUT_TOKENS = 1_024
        const val INFERENCE_THREADS = 4
        const val MAXIMUM_MESSAGES = 64
    }
}

internal const val LOCAL_AI_EVENT_BUFFER_CAPACITY = 64
internal val LOCAL_AI_MODEL = AiModelMetadata(
    modelId = "qwen3.5-4b-q4-k-m",
    parameterCount = 4_000_000_000L,
)
