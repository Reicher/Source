package com.source.client.ui

import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationSyncTest {
    @Test
    fun `newer remote conversation replaces local state`() {
        val local = listOf(ChatMessage(role = ChatRole.USER, content = "local", createdAtMillis = 100))
        val remote = listOf(ChatMessage(role = ChatRole.USER, content = "remote", createdAtMillis = 200))

        assertEquals(ConversationResolution.USE_REMOTE, resolveConversation(local, remote))
    }

    @Test
    fun `matching message identities need no upload`() {
        val message = ChatMessage(role = ChatRole.USER, content = "same", createdAtMillis = 100)

        assertEquals(ConversationResolution.MATCH, resolveConversation(listOf(message), listOf(message)))
    }

    @Test
    fun `local state wins when remote state is not newer or identical`() {
        val local = listOf(ChatMessage(role = ChatRole.USER, content = "local", createdAtMillis = 200))
        val remote = listOf(ChatMessage(role = ChatRole.USER, content = "remote", createdAtMillis = 100))

        assertEquals(ConversationResolution.KEEP_LOCAL, resolveConversation(local, remote))
    }
}
