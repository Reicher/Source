package com.source.client.ai

import com.source.client.model.AiSelection
import com.source.client.model.ConnectedNode
import com.source.client.protocol.SourceApiException
import com.source.client.protocol.SourceNodeApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

internal enum class AiRuntimeTarget { THIS_DEVICE, NODE }

internal fun resolveAiRuntime(selection: AiSelection, nodeConnected: Boolean): AiRuntimeTarget = when (selection) {
    AiSelection.AUTO -> if (nodeConnected) AiRuntimeTarget.NODE else AiRuntimeTarget.THIS_DEVICE
    AiSelection.THIS_DEVICE -> AiRuntimeTarget.THIS_DEVICE
    AiSelection.NODE -> if (nodeConnected) AiRuntimeTarget.NODE else AiRuntimeTarget.THIS_DEVICE
}

internal fun canFallbackFromNode(error: Exception): Boolean =
    error !is SourceApiException ||
        (!error.responseStarted && error.code in setOf("model_unavailable", "http_502", "http_503", "http_504"))

/** Routes one shared AI stream contract to the selected inference runtime. */
class AiRuntimeRouter internal constructor(
    private val local: SourceAiRuntime,
    private val connectedNode: () -> ConnectedNode?,
    private val onNodeUnavailable: (ConnectedNode) -> Unit,
    private val nodeRuntime: (ConnectedNode) -> SourceAiRuntime,
) : SourceAiRuntime {
    constructor(
        local: SourceAiRuntime,
        nodeApi: SourceNodeApi,
        connectedNode: () -> ConnectedNode?,
        onNodeUnavailable: (ConnectedNode) -> Unit,
    ) : this(
        local,
        connectedNode,
        onNodeUnavailable,
        { connected -> NodeAiRuntime(nodeApi, connected.discovered.apiBaseUrl, connected.trusted) },
    )

    private var selection = AiSelection.AUTO

    override val capabilities: SourceAiCapabilities = local.capabilities

    fun select(selection: AiSelection) {
        this.selection = selection
    }

    override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> {
        val selected = selection
        val connected = connectedNode()
        if (resolveAiRuntime(selected, connected != null) == AiRuntimeTarget.THIS_DEVICE || connected == null) {
            return local.stream(request)
        }
        return flow {
            var responseStarted = false
            var pendingStarted: SourceAiEvent.Started? = null
            try {
                nodeRuntime(connected).stream(request).collect { event ->
                    when (event) {
                        is SourceAiEvent.Started -> pendingStarted = event
                        is SourceAiEvent.Delta -> {
                            pendingStarted?.let { emit(it) }
                            pendingStarted = null
                            responseStarted = responseStarted || event.text.isNotEmpty()
                            emit(event)
                        }
                        is SourceAiEvent.Completed -> {
                            pendingStarted?.let { emit(it) }
                            pendingStarted = null
                            emit(event)
                        }
                        is SourceAiEvent.Failed -> {
                            val failure = SourceApiException(
                                event.code,
                                "The Node AI could not complete the response.",
                                responseStarted,
                            )
                            if (canFallbackFromNode(failure)) throw failure
                            pendingStarted?.let { emit(it) }
                            pendingStarted = null
                            emit(event)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!canFallbackFromNode(error)) throw error
                if (error !is SourceApiException) onNodeUnavailable(connected)
                emitAll(local.stream(request))
            }
        }
    }
}
