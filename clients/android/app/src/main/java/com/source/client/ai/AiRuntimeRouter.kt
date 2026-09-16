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

internal enum class AiRuntimeTarget { THIS_DEVICE, NODE, UNAVAILABLE }

internal fun resolveAiRuntime(selection: AiSelection, nodeAiUsable: Boolean): AiRuntimeTarget = when (selection) {
    AiSelection.AUTO -> if (nodeAiUsable) AiRuntimeTarget.NODE else AiRuntimeTarget.THIS_DEVICE
    AiSelection.THIS_DEVICE -> AiRuntimeTarget.THIS_DEVICE
    AiSelection.NODE -> if (nodeAiUsable) AiRuntimeTarget.NODE else AiRuntimeTarget.UNAVAILABLE
}

internal fun canFallbackFromNode(error: Exception): Boolean =
    error !is SourceApiException ||
        (!error.responseStarted && error.code in NODE_FALLBACK_CODES)

private val NODE_FALLBACK_CODES = setOf(
    "model_not_installed",
    "model_unavailable",
    "model_load_failed",
    "inference_failed",
    "timeout",
    "node_unavailable",
    "http_502",
    "http_503",
    "http_504",
)

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
        { connected ->
            NodeAiRuntime(
                nodeApi,
                connected.discovered.apiBaseUrl,
                connected.trusted,
                connected.aiRuntimeState,
            )
        },
    )

    private var selection = AiSelection.AUTO

    override val runtimeState: SourceAiRuntimeState
        get() {
            val connected = connectedNode()
            return when (resolveAiRuntime(selection, connected?.aiRuntimeState?.isUsable == true)) {
                AiRuntimeTarget.THIS_DEVICE -> local.runtimeState
                AiRuntimeTarget.NODE -> checkNotNull(connected).aiRuntimeState
                AiRuntimeTarget.UNAVAILABLE -> SourceAiRuntimeState(
                    SourceAiAvailability.MODEL_LOAD_FAILED,
                    failureCode = SourceAiFailureCode.NODE_UNAVAILABLE.wireValue,
                )
            }
        }

    fun select(selection: AiSelection) {
        this.selection = selection
    }

    override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> {
        val selected = selection
        val connected = connectedNode()
        when (resolveAiRuntime(selected, connected?.aiRuntimeState?.isUsable == true)) {
            AiRuntimeTarget.THIS_DEVICE -> return local.stream(request)
            AiRuntimeTarget.UNAVAILABLE -> return flow {
                emit(SourceAiEvent.Failed(
                    request.runId,
                    SourceAiFailureCode.NODE_UNAVAILABLE.wireValue,
                    SourceAiFailureCode.NODE_UNAVAILABLE.retryable,
                ))
            }
            AiRuntimeTarget.NODE -> Unit
        }
        checkNotNull(connected)
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
                            if (selected == AiSelection.AUTO && canFallbackFromNode(failure)) throw failure
                            if (event.code == SourceAiFailureCode.NODE_UNAVAILABLE.wireValue) {
                                onNodeUnavailable(connected)
                            }
                            pendingStarted?.let { emit(it) }
                            pendingStarted = null
                            emit(event)
                        }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (selected != AiSelection.AUTO || !canFallbackFromNode(error)) {
                    emit(SourceAiEvent.Failed(
                        request.runId,
                        (error as? SourceApiException)?.code ?: SourceAiFailureCode.NODE_UNAVAILABLE.wireValue,
                        retryable = true,
                    ))
                    return@flow
                }
                if (error !is SourceApiException) onNodeUnavailable(connected)
                emitAll(local.stream(request))
            }
        }
    }
}
