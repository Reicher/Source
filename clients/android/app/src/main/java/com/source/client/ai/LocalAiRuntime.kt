package com.source.client.ai

import android.content.Context
import com.source.client.model.AiModelMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean

class LocalAiRuntime(context: Context) : SourceAiRuntime {
    private val applicationContext = context.applicationContext
    private val native = LlamaCppNative(applicationContext)
    private val inferenceMutex = Mutex()
    private val runtimeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val localCapabilities = SourceAiCapabilities(
        capabilities = setOf(SourceAiCapability.TEXT),
        streaming = true,
        cancellation = true,
        maximumContextTokens = CONTEXT_TOKENS,
    )

    @Volatile
    override var runtimeState = if (BundledModelAssets.areInstalled(applicationContext.assets)) {
        SourceAiRuntimeState(SourceAiAvailability.MODEL_PRESENT, LOCAL_AI_MODEL, localCapabilities)
    } else {
        SourceAiRuntimeState(SourceAiAvailability.MODEL_NOT_INSTALLED)
    }
        private set

    override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = callbackFlow {
        validate(request)
        if (runtimeState.availability == SourceAiAvailability.MODEL_NOT_INSTALLED) {
            trySendBlocking(SourceAiEvent.Failed(
                request.runId,
                SourceAiFailureCode.MODEL_NOT_INSTALLED.wireValue,
                SourceAiFailureCode.MODEL_NOT_INSTALLED.retryable,
            ))
            close()
            return@callbackFlow
        }
        trySendBlocking(SourceAiEvent.Started(request.runId, LOCAL_AI_MODEL))
        var sequence = 0L
        val timedOut = AtomicBoolean(false)
        val timeout = launch {
            delay(request.timeoutMillis)
            timedOut.set(true)
            native.cancel(request.runId)
        }
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
                    if (result.finishReason == "cancelled" && timedOut.get()) {
                        runtimeState = SourceAiRuntimeState(
                            SourceAiAvailability.INFERENCE_FAILED,
                            LOCAL_AI_MODEL,
                            localCapabilities,
                            SourceAiFailureCode.TIMEOUT.wireValue,
                        )
                        trySendBlocking(SourceAiEvent.Failed(
                            request.runId,
                            SourceAiFailureCode.TIMEOUT.wireValue,
                            SourceAiFailureCode.TIMEOUT.retryable,
                        ))
                    } else if (result.finishReason != "cancelled") {
                        runtimeState = SourceAiRuntimeState(
                            SourceAiAvailability.READY,
                            LOCAL_AI_MODEL,
                            localCapabilities,
                        )
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
                    val failure = runtimeFailure(error)
                    runtimeState = failure
                    trySendBlocking(
                        SourceAiEvent.Failed(
                            runId = request.runId,
                            code = checkNotNull(failure.failureCode),
                            retryable = failure.failureCode != SourceAiFailureCode.MODEL_NOT_INSTALLED.wireValue,
                        ),
                    )
                    close()
                } finally {
                    timeout.cancel()
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

    private fun runtimeFailure(error: Exception): SourceAiRuntimeState {
        val message = error.message.orEmpty()
        val availability = when {
            message.contains("model part", ignoreCase = true) -> SourceAiAvailability.MODEL_NOT_INSTALLED
            message.contains("load", ignoreCase = true) || message.contains("engine", ignoreCase = true) ->
                SourceAiAvailability.MODEL_LOAD_FAILED
            else -> SourceAiAvailability.INFERENCE_FAILED
        }
        val code = when (availability) {
            SourceAiAvailability.MODEL_NOT_INSTALLED -> SourceAiFailureCode.MODEL_NOT_INSTALLED
            SourceAiAvailability.MODEL_LOAD_FAILED -> SourceAiFailureCode.MODEL_LOAD_FAILED
            else -> SourceAiFailureCode.INFERENCE_FAILED
        }
        return SourceAiRuntimeState(
            availability,
            model = LOCAL_AI_MODEL.takeUnless { availability == SourceAiAvailability.MODEL_NOT_INSTALLED },
            capabilities = localCapabilities.takeUnless { availability == SourceAiAvailability.MODEL_NOT_INSTALLED },
            failureCode = code.wireValue,
        )
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
