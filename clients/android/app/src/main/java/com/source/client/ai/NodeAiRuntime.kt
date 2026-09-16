package com.source.client.ai

import com.source.client.model.TrustedNode
import com.source.client.protocol.SourceApiException
import com.source.client.protocol.SourceNodeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

class NodeAiRuntime internal constructor(
    private val source: (SourceAiRequest) -> Flow<SourceAiEvent>,
    override val runtimeState: SourceAiRuntimeState,
) : SourceAiRuntime {
    constructor(
        api: SourceNodeApi,
        apiBaseUrl: String,
        trusted: TrustedNode,
        runtimeState: SourceAiRuntimeState,
    ) : this({ request -> api.streamAi(apiBaseUrl, trusted, request) }, runtimeState)

    override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = flow {
        var emittedOutput = false
        var terminated = false
        try {
            source(request).collect { event ->
                when (event) {
                    is SourceAiEvent.Delta -> emittedOutput = emittedOutput || event.text.isNotEmpty()
                    is SourceAiEvent.Completed, is SourceAiEvent.Failed -> terminated = true
                    is SourceAiEvent.Started -> Unit
                }
                emit(event)
            }
            if (!terminated) {
                emit(SourceAiEvent.Failed(request.runId, SourceAiFailureCode.INFERENCE_FAILED.wireValue, true))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val apiError = error as? SourceApiException
            val code = if (emittedOutput) {
                SourceAiFailureCode.STREAM_INTERRUPTED.wireValue
            } else {
                apiError?.code ?: SourceAiFailureCode.NODE_UNAVAILABLE.wireValue
            }
            emit(SourceAiEvent.Failed(request.runId, code, retryable = true))
        }
    }
}
