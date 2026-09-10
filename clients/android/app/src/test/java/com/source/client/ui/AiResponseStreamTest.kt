package com.source.client.ui

import com.source.client.ai.SourceAiCapabilities
import com.source.client.ai.SourceAiCapability
import com.source.client.ai.SourceAiContent
import com.source.client.ai.SourceAiEvent
import com.source.client.ai.SourceAiMessage
import com.source.client.ai.SourceAiRequest
import com.source.client.ai.SourceAiRole
import com.source.client.ai.SourceAiRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiResponseStreamTest {
    private val request = SourceAiRequest(
        runId = "run-1",
        conversationId = "conversation-1",
        messages = listOf(SourceAiMessage(SourceAiRole.USER, listOf(SourceAiContent.Text("Hello")))),
    )

    @Test
    fun `all runtimes share draft accumulation and completion handling`() = runBlocking {
        val drafts = mutableListOf<String>()
        val answer = collectAiResponse(
            runtime(
                SourceAiEvent.Started(request.runId),
                SourceAiEvent.Delta(request.runId, 0, "Shared "),
                SourceAiEvent.Delta(request.runId, 1, "answer"),
                SourceAiEvent.Completed(request.runId, "stop"),
            ),
            request,
            drafts::add,
        )

        assertEquals("Shared answer", answer)
        assertEquals(listOf("Shared ", "Shared answer"), drafts)
    }

    @Test
    fun `runtime failures preserve their contract code`() = runBlocking {
        val failure = runCatching {
            collectAiResponse(
                runtime(
                    SourceAiEvent.Started(request.runId),
                    SourceAiEvent.Failed(request.runId, "model_missing", false),
                ),
                request,
                {},
            )
        }.exceptionOrNull()

        assertTrue(failure is SourceAiFailureException)
        assertEquals("model_missing", (failure as SourceAiFailureException).code)
    }

    @Test
    fun `wrong run identifiers and incomplete streams are rejected`() = runBlocking {
        val wrongRun = runCatching {
            collectAiResponse(
                runtime(SourceAiEvent.Delta("wrong-run", 0, "answer")),
                request,
                {},
            )
        }.exceptionOrNull()
        val incomplete = runCatching {
            collectAiResponse(
                runtime(SourceAiEvent.Started(request.runId)),
                request,
                {},
            )
        }.exceptionOrNull()

        assertTrue(wrongRun is IllegalStateException)
        assertTrue(incomplete is IllegalStateException)
    }

    private fun runtime(vararg events: SourceAiEvent) = object : SourceAiRuntime {
        override val capabilities = SourceAiCapabilities(
            capabilities = setOf(SourceAiCapability.TEXT),
            streaming = true,
            cancellation = true,
            maximumContextTokens = 1_024,
        )

        override fun stream(request: SourceAiRequest): Flow<SourceAiEvent> = flowOf(*events)
    }
}
