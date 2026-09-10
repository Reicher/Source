package com.source.client.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessageTest {
    @Test
    fun chatFactoriesUseApiRolesAndUniqueIds() {
        val user = ChatMessage.user("Hello")
        val assistant = ChatMessage.assistant("Hello back")

        assertEquals("user", user.role.apiValue)
        assertEquals("assistant", assistant.role.apiValue)
        assertTrue(user.id != assistant.id)
    }

    @Test
    fun apiRolesRoundTrip() {
        ChatRole.entries.forEach { role ->
            assertEquals(role, ChatRole.fromApiValue(role.apiValue))
        }
    }
}
