package com.source.client.ai

import com.source.client.protocol.SourceApiException
import java.io.IOException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeAiRuntimeTest {
    private val request = SourceAiRequest(
        runId = "run-1",
        conversationId = "conversation-1",
        messages = listOf(SourceAiMessage(SourceAiRole.USER, listOf(SourceAiContent.Text("Hello")))),
    )

    @Test
    fun `node failures before output retain their original fallback semantics`() = runBlocking {
        val runtime = NodeAiRuntime {
            flow {
                emit(SourceAiEvent.Started(request.runId))
                throw IOException("offline")
            }
        }

        val failure = runCatching { runtime.stream(request).toList() }.exceptionOrNull()

        assertTrue(failure is IOException)
        assertTrue(failure !is SourceApiException)
    }

    @Test
    fun `node failures after output never allow a second local answer`() = runBlocking {
        val runtime = NodeAiRuntime {
            flow {
                emit(SourceAiEvent.Started(request.runId))
                emit(SourceAiEvent.Delta(request.runId, 0, "partial"))
                throw IOException("offline")
            }
        }

        val failure = runCatching { runtime.stream(request).toList() }.exceptionOrNull()

        assertEquals("node_stream_interrupted_after_output", (failure as SourceApiException).code)
    }

    @Test
    fun `node failed events remain shared runtime events`() = runBlocking {
        val runtime = NodeAiRuntime {
            flow {
                emit(SourceAiEvent.Started(request.runId))
                emit(SourceAiEvent.Failed(request.runId, "chat_rate_limited", false))
            }
        }

        val events = runtime.stream(request).toList()

        assertEquals("chat_rate_limited", (events.last() as SourceAiEvent.Failed).code)
    }

    @Test
    fun `node failed events after output retain the reported event`() = runBlocking {
        val runtime = NodeAiRuntime {
            flow {
                emit(SourceAiEvent.Started(request.runId))
                emit(SourceAiEvent.Delta(request.runId, 0, "partial"))
                emit(SourceAiEvent.Failed(request.runId, "model_unavailable", true))
            }
        }

        val events = runtime.stream(request).toList()

        assertEquals("model_unavailable", (events.last() as SourceAiEvent.Failed).code)
    }
}
