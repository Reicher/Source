package com.source.client.ai

import com.source.client.model.AiSelection
import com.source.client.model.ConnectedNode
import com.source.client.model.DiscoveredNode
import com.source.client.model.TrustedNode
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
            override val capabilities = AiRuntimeRouterTest.capabilities
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

    private fun runtime(answer: String) = object : SourceAiRuntime {
        override val capabilities = AiRuntimeRouterTest.capabilities
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
    }
}
