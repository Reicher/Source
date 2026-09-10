package com.source.client.ui

import com.source.client.model.AiSelection
import com.source.client.model.ChatMessage
import com.source.client.protocol.SourceApiException
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRoutingTest {
    @Test
    fun `auto prefers an authenticated node and otherwise stays local`() {
        assertEquals(AiRuntimeTarget.NODE, resolveAiRuntime(AiSelection.AUTO, true))
        assertEquals(AiRuntimeTarget.THIS_DEVICE, resolveAiRuntime(AiSelection.AUTO, false))
    }

    @Test
    fun `explicit device is independent of the node connection`() {
        assertEquals(AiRuntimeTarget.THIS_DEVICE, resolveAiRuntime(AiSelection.THIS_DEVICE, true))
    }

    @Test
    fun `unavailable explicit node falls back to the device`() {
        assertEquals(AiRuntimeTarget.THIS_DEVICE, resolveAiRuntime(AiSelection.NODE, false))
    }

    @Test
    fun `transport and unavailable model errors fall back but rate limits do not`() {
        assertTrue(canFallbackFromNode(IOException("offline")))
        assertTrue(canFallbackFromNode(SourceApiException("model_unavailable", "offline")))
        assertTrue(!canFallbackFromNode(SourceApiException("chat_rate_limited", "wait")))
    }

    @Test
    fun `context keeps newest messages within node limits`() {
        val messages = (1..24).map { ChatMessage.user("$it-${"x".repeat(995)}") }
        val result = boundedChatContext(messages)

        assertEquals(16, result.size)
        assertEquals(messages.takeLast(16).map(ChatMessage::id), result.map(ChatMessage::id))
        assertTrue(result.sumOf { it.content.length } <= 16_000)
    }

    @Test
    fun `context never starts with an orphaned assistant reply`() {
        val omittedOlderUser = ChatMessage.user("A question outside the context window")
        val orphanedAssistant = ChatMessage.assistant("An older answer")
        val newerMessages = (1..19).map { ChatMessage.user("Newer question $it") }

        val result = boundedChatContext(
            listOf(omittedOlderUser, orphanedAssistant) + newerMessages,
        )

        assertEquals(newerMessages.map(ChatMessage::id), result.map(ChatMessage::id))
    }
}
