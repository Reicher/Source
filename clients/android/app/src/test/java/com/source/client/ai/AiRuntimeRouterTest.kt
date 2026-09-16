package com.source.client.ai

import com.source.client.model.AiSelection
import com.source.client.model.ConnectedNode
import com.source.client.model.DiscoveredNode
import com.source.client.model.TrustedNode
import com.source.client.model.AiModelMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AiRuntimeRouterTest {
    private val request = SourceAiRequest(
        runId = "run-1",
        conversationId = "conversation-1",
        messages = listOf(SourceAiMessage(SourceAiRole.USER, listOf(SourceAiContent.Text("Hello")))),
    )
    private val connected = ConnectedNode(
        DiscoveredNode("service", "node", "Node", "https://node/api/v1"),
        TrustedNode("node", "key", "ca", "Node", "credential", "user", "client"),
        readyState("node-model"),
    )

    @Test
    fun `router exposes the same event stream for node and local inference`() = runBlocking {
        val local = runtime("local")
        val router = AiRuntimeRouter(local, { connected }, {}, { runtime("node") })
        router.select(AiSelection.NODE)

        val nodeEvents = router.stream(request).toList()
        router.select(AiSelection.THIS_DEVICE)
        val localEvents = router.stream(request).toList()

        assertEquals("node", (nodeEvents[1] as SourceAiEvent.Delta).text)
        assertEquals("local", (localEvents[1] as SourceAiEvent.Delta).text)
        assertEquals(nodeEvents.map { it::class }, localEvents.map { it::class })
    }

    @Test
    fun `router owns fallback before node output starts`() = runBlocking {
        val unavailable = object : SourceAiRuntime {
            override val runtimeState = readyState("node-model")
            override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = flowOf(
                SourceAiEvent.Started(request.runId),
                SourceAiEvent.Failed(request.runId, "model_unavailable", true),
            )
        }
        val router = AiRuntimeRouter(runtime("local"), { connected }, {}, { unavailable })
        router.select(AiSelection.AUTO)

        val events = router.stream(request).toList()

        assertEquals("local", (events.last { it is SourceAiEvent.Delta } as SourceAiEvent.Delta).text)
        assertEquals(1, events.count { it is SourceAiEvent.Started })
        assertEquals(SourceAiEvent.Completed::class, events.last()::class)
    }

    @Test
    fun `auto marks node unavailable before falling back from a transport failure`() = runBlocking {
        var unavailableSignals = 0
        val unavailable = object : SourceAiRuntime {
            override val runtimeState = readyState("node-model")
            override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = flowOf(
                SourceAiEvent.Started(request.runId),
                SourceAiEvent.Failed(request.runId, SourceAiFailureCode.NODE_UNAVAILABLE.wireValue, true),
            )
        }
        val router = AiRuntimeRouter(
            runtime("local"),
            { connected },
            { unavailableSignals += 1 },
            { unavailable },
        )
        router.select(AiSelection.AUTO)

        val events = router.stream(request).toList()

        assertEquals(1, unavailableSignals)
        assertEquals("local", (events.last { it is SourceAiEvent.Delta } as SourceAiEvent.Delta).text)
    }

    @Test
    fun `interrupted node stream marks node unavailable without starting a local answer`() = runBlocking {
        var unavailableSignals = 0
        val interrupted = object : SourceAiRuntime {
            override val runtimeState = readyState("node-model")
            override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = flowOf(
                SourceAiEvent.Started(request.runId),
                SourceAiEvent.Delta(request.runId, 0, "partial"),
                SourceAiEvent.Failed(request.runId, SourceAiFailureCode.STREAM_INTERRUPTED.wireValue, true),
            )
        }
        val router = AiRuntimeRouter(
            runtime("local"),
            { connected },
            { unavailableSignals += 1 },
            { interrupted },
        )
        router.select(AiSelection.AUTO)

        val events = router.stream(request).toList()

        assertEquals(1, unavailableSignals)
        assertEquals(listOf("partial"), events.filterIsInstance<SourceAiEvent.Delta>().map { it.text })
        assertEquals(SourceAiFailureCode.STREAM_INTERRUPTED.wireValue,
            (events.last() as SourceAiEvent.Failed).code)
    }

    @Test
    fun `explicit node selection never silently falls back to the client`() = runBlocking {
        val disconnected = connected.copy(
            aiRuntimeState = SourceAiRuntimeState(SourceAiAvailability.MODEL_NOT_INSTALLED),
        )
        val router = AiRuntimeRouter(runtime("local"), { disconnected }, {}, { runtime("node") })
        router.select(AiSelection.NODE)

        val events = router.stream(request).toList()

        assertEquals(SourceAiFailureCode.NODE_UNAVAILABLE.wireValue, (events.single() as SourceAiEvent.Failed).code)
    }

    @Test
    fun `auto snapshots the runtime when a request starts`() = runBlocking {
        var current: ConnectedNode? = null
        val router = AiRuntimeRouter(runtime("local"), { current }, {}, { runtime("node") })
        router.select(AiSelection.AUTO)
        val stream = router.stream(request)
        current = connected

        assertEquals("local", (stream.toList()[1] as SourceAiEvent.Delta).text)
    }

    private fun runtime(answer: String) = object : SourceAiRuntime {
        override val runtimeState = readyState("$answer-model")
        override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = flowOf(
            SourceAiEvent.Started(request.runId),
            SourceAiEvent.Delta(request.runId, 0, answer),
            SourceAiEvent.Completed(request.runId, "stop"),
        )
    }

    private companion object {
        val capabilities = SourceAiCapabilities(
            capabilities = setOf(SourceAiCapability.TEXT),
            streaming = true,
            cancellation = true,
            maximumContextTokens = 1_024,
        )

        fun readyState(modelId: String) = SourceAiRuntimeState(
            SourceAiAvailability.READY,
            AiModelMetadata(modelId, 1),
            capabilities,
        )
    }
}
