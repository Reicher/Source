package com.source.client.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalAiRuntimeDeviceTest {
    @Test
    fun qwenRuntimeStreamsContextualAnswerAndCancelsPromptlyWhenExplicitlyRequested() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("sourceQwenRuntime") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = LocalAiRuntime(context)
        assertTrue(runtime.capabilities.streaming)
        assertTrue(runtime.capabilities.cancellation)
        assertEquals(4_096, runtime.capabilities.maximumContextTokens)

        val runId = UUID.randomUUID().toString()
        val events = withTimeout(180_000) {
            runtime.stream(
                SourceAiRequest(
                    runId = runId,
                    conversationId = "device-test",
                    messages = listOf(
                        SourceAiMessage(SourceAiRole.USER, listOf(SourceAiContent.Text("what is ten times ten?"))),
                        SourceAiMessage(SourceAiRole.ASSISTANT, listOf(SourceAiContent.Text("Ten times ten is 100!"))),
                        SourceAiMessage(SourceAiRole.USER, listOf(SourceAiContent.Text("what AI model are you?"))),
                    ),
                ),
            ).toList()
        }
        assertTrue(events.first() is SourceAiEvent.Started)
        val deltas = events.filterIsInstance<SourceAiEvent.Delta>()
        assertTrue("Expected streamed visible output: $events", deltas.isNotEmpty())
        assertEquals(deltas.indices.map(Int::toLong), deltas.map(SourceAiEvent.Delta::sequence))
        assertTrue(events.last() is SourceAiEvent.Completed)
        val answer = deltas.joinToString(separator = "", transform = SourceAiEvent.Delta::text)
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            android.os.Bundle().apply { putString("stream", "answer=$answer\n") },
        )
        assertTrue(answer.isNotBlank())
        assertFalse(answer.contains("<think>", ignoreCase = true))
        assertFalse(answer.contains("</think>", ignoreCase = true))
        assertFalse(answer.contains("internet access", ignoreCase = true))
        assertFalse(answer.contains("assistant from Source", ignoreCase = true))
        assertFalse("The new question repeated the previous arithmetic answer: $answer", answer.contains("100"))

        val cancellationStarted = CompletableDeferred<Unit>()
        val cancellationRunId = UUID.randomUUID().toString()
        val cancellation = async {
            runtime.stream(
                SourceAiRequest(
                    runId = cancellationRunId,
                    conversationId = "device-test",
                    messages = listOf(
                        SourceAiMessage(
                            SourceAiRole.USER,
                            listOf(SourceAiContent.Text("Write a very long and detailed story.")),
                        ),
                    ),
                ),
            ).onEach { if (it is SourceAiEvent.Started) cancellationStarted.complete(Unit) }.toList()
        }
        cancellationStarted.await()
        delay(750)
        val cancellationBegan = System.nanoTime()
        cancellation.cancelAndJoin()
        val cancellationMillis = (System.nanoTime() - cancellationBegan) / 1_000_000
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            android.os.Bundle().apply { putString("stream", "cancellationMs=$cancellationMillis\n") },
        )
        assertTrue("Cancellation took ${cancellationMillis}ms", cancellationMillis < 10_000)
    }
}
