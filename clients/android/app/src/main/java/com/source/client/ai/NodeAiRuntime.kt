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
) : SourceAiRuntime {
    constructor(
        api: SourceNodeApi,
        apiBaseUrl: String,
        trusted: TrustedNode,
    ) : this({ request -> api.streamAi(apiBaseUrl, trusted, request) })

    override val capabilities = SourceAiCapabilities(
        capabilities = setOf(SourceAiCapability.TEXT),
        streaming = true,
        cancellation = true,
        maximumContextTokens = NODE_CONTEXT_TOKENS,
    )

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
                throw SourceApiException("model_unavailable", "The Node returned an incomplete AI response.")
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val apiError = error as? SourceApiException
            if (emittedOutput && apiError?.responseStarted != true && apiError?.code != STREAM_INTERRUPTED_CODE) {
                throw SourceApiException(
                    STREAM_INTERRUPTED_CODE,
                    "The connection to the Node was interrupted during the response.",
                    responseStarted = true,
                )
            }
            throw error
        }
    }

    private companion object {
        const val NODE_CONTEXT_TOKENS = 8_192
        const val STREAM_INTERRUPTED_CODE = "node_stream_interrupted_after_output"
    }
}
