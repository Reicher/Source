package com.source.client.ui

import com.source.client.model.ChatConversation
import com.source.client.model.ChatConversations
import com.source.client.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationStateTest {
    @Test
    fun `empty startup uses a UI draft without storing a conversation`() {
        val state = ChatConversationState()

        val active = state.reset(ChatConversations(), startFreshConversation = false)

        assertTrue(active.messages.isEmpty())
        assertTrue(state.conversations.conversations.isEmpty())
        assertNull(state.conversations.activeConversationId)
    }

    @Test
    fun `fresh login leaves existing history unchanged until the draft is used`() {
        val stored = conversation("stored", 100, ChatMessage.user("Existing"))
        val loaded = ChatConversations(listOf(stored), stored.id)
        val state = ChatConversationState()

        val draft = state.reset(loaded, startFreshConversation = true)

        assertEquals(loaded, state.conversations)
        assertTrue(draft.messages.isEmpty())
        assertFalse(state.conversations.conversations.any { it.id == draft.id })
    }

    @Test
    fun `creating and abandoning new conversations does not change stored data`() {
        val stored = conversation("stored", 100, ChatMessage.user("Existing"))
        val loaded = ChatConversations(listOf(stored), stored.id)
        val state = ChatConversationState()
        state.reset(loaded, startFreshConversation = false)

        val firstDraft = state.startDraft()
        val secondDraft = state.startDraft()

        assertEquals(loaded, state.conversations)
        assertFalse(state.conversations.conversations.any { it.id == firstDraft.id })
        assertFalse(state.conversations.conversations.any { it.id == secondDraft.id })
    }

    @Test
    fun `deleting the last conversation leaves only a deletion marker and a UI draft`() {
        val stored = conversation("stored", 100, ChatMessage.user("Existing"))
        val state = ChatConversationState()
        state.reset(ChatConversations(listOf(stored), stored.id), startFreshConversation = false)

        val draft = state.delete(stored.id, deletedAtMillis = 200)

        assertTrue(state.conversations.conversations.isEmpty())
        assertNull(state.conversations.activeConversationId)
        assertEquals(200L, state.conversations.tombstones.single().deletedAtMillis)
        assertFalse(state.conversations.conversations.any { it.id == draft?.id })
    }

    @Test
    fun `first user message materializes and activates the draft`() {
        val state = ChatConversationState()
        val draft = state.reset(ChatConversations(), startFreshConversation = false)
        val firstMessage = ChatMessage.user("Hello")

        val active = state.updateMessages(listOf(firstMessage))

        assertEquals(draft.id, active.id)
        assertEquals(draft.createdAtMillis, active.createdAtMillis)
        assertEquals(listOf(firstMessage), active.messages)
        assertEquals(active.id, state.conversations.activeConversationId)
        assertEquals(active, state.conversations.conversations.single())
    }

    @Test
    fun `synchronization updates storage without replacing an abandoned draft`() {
        val state = ChatConversationState()
        val draft = state.reset(ChatConversations(), startFreshConversation = false)
        val synchronized = conversation("remote", 100, ChatMessage.user("Remote"))

        val active = state.apply(
            ChatConversations(listOf(synchronized), synchronized.id),
            preserveDraft = true,
        )

        assertEquals(draft, active)
        assertEquals(listOf(synchronized), state.conversations.conversations)
    }

    private fun conversation(id: String, createdAtMillis: Long, vararg messages: ChatMessage) = ChatConversation(
        id = id,
        createdAtMillis = createdAtMillis,
        messages = messages.toList(),
    )
}
