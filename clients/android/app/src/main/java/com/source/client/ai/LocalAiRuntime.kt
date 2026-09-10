package com.source.client.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
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
        trySend(SourceAiEvent.Started(request.runId))
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
                                trySend(SourceAiEvent.Delta(request.runId, sequence++, text))
                            }
                        },
                    )
                    if (result.finishReason != "cancelled") {
                        trySend(
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
                    trySend(
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
    }.buffer(Channel.UNLIMITED)

    fun cancel(runId: String) = native.cancel(runId)

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
