package com.source.client.storage

import com.source.client.model.ChatConversation
import com.source.client.model.ChatConversationTombstone
import com.source.client.model.ChatConversations
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SourceDataTest {
    @Test
    fun `newer remote conversation replaces local state`() {
        val local = conversations("local", 100)
        val remote = conversations("remote", 200)

        assertEquals(SourceDataResolution.USE_REMOTE, resolveSourceData(ChatData.version(local), ChatData.version(remote)))
    }

    @Test
    fun `matching message identities need no upload`() {
        val message = ChatMessage(role = ChatRole.USER, content = "same", createdAtMillis = 100)
        val data = ChatConversations(
            conversations = listOf(ChatConversation("conversation", 50, listOf(message))),
            activeConversationId = "conversation",
        )

        assertEquals(
            SourceDataResolution.MATCH,
            resolveSourceData(ChatData.version(data), ChatData.version(data)),
        )
    }

    @Test
    fun `local state wins when remote state is not newer or identical`() {
        val local = conversations("local", 200)
        val remote = conversations("remote", 100)

        assertEquals(SourceDataResolution.KEEP_LOCAL, resolveSourceData(ChatData.version(local), ChatData.version(remote)))
    }

    @Test
    fun `chat uses the compatible generic storage descriptor`() {
        assertEquals("source-client", ChatData.descriptor.remoteAppId)
        assertEquals("conversation", ChatData.descriptor.id)
        assertEquals("source-client-conversation", ChatData.descriptor.snapshotFormat)
        assertEquals(3, ChatData.descriptor.formatVersion)
        assertEquals(setOf(1, 2, 3), ChatData.supportedFormatVersions)
    }

    @Test
    fun `fresh conversations preserve existing conversations and get unique ids`() {
        val existing = conversations("existing", 100)

        val firstFresh = existing.withFreshConversation(ChatConversation("fresh-1", 200))
        val secondFresh = firstFresh.withFreshConversation(ChatConversation("fresh-2", 300))

        assertEquals(3, secondFresh.conversations.size)
        assertEquals("fresh-2", secondFresh.activeConversationId)
        assertNotEquals(existing.activeConversationId, secondFresh.activeConversationId)
        assertEquals("existing", secondFresh.conversations.first().messages.single().content)
    }

    @Test
    fun `conversation tombstone makes offline deletion newer than stored content`() {
        val stored = conversations("stored", 100)
        val deleted = ChatConversations(
            tombstones = listOf(ChatConversationTombstone(stored.conversations.single().id, 200)),
        )

        assertEquals(
            SourceDataResolution.KEEP_LOCAL,
            resolveSourceData(ChatData.version(deleted), ChatData.version(stored)),
        )
    }

    @Test
    fun `Silver refinement uses user authored conversation text only`() {
        val conversation = ChatConversation(
            id = "conversation",
            createdAtMillis = 100,
            messages = listOf(
                ChatMessage("user-1", ChatRole.USER, "Robin lives in Stockholm.", 101),
                ChatMessage("assistant-1", ChatRole.ASSISTANT, "Perhaps Source should use Ollama.", 102),
                ChatMessage("user-2", ChatRole.USER, "Robin created Source.", 103),
            ),
        )

        assertEquals(
            "User: Robin lives in Stockholm.\n\nUser: Robin created Source.",
            ChatData.refinementText(conversation),
        )
    }

    private fun conversations(content: String, createdAtMillis: Long): ChatConversations {
        val conversation = ChatConversation(
            id = "conversation-$content",
            createdAtMillis = createdAtMillis,
            messages = listOf(
                ChatMessage(
                    id = "message-$content",
                    role = ChatRole.USER,
                    content = content,
                    createdAtMillis = createdAtMillis,
                ),
            ),
        )
        return ChatConversations(listOf(conversation), conversation.id)
    }
}
