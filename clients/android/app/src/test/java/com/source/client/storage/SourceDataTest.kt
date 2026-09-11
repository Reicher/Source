package com.source.client.storage

import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Test

class SourceDataTest {
    @Test
    fun `newer remote conversation replaces local state`() {
        val local = listOf(ChatMessage(role = ChatRole.USER, content = "local", createdAtMillis = 100))
        val remote = listOf(ChatMessage(role = ChatRole.USER, content = "remote", createdAtMillis = 200))

        assertEquals(SourceDataResolution.USE_REMOTE, resolveSourceData(ChatData.version(local), ChatData.version(remote)))
    }

    @Test
    fun `matching message identities need no upload`() {
        val message = ChatMessage(role = ChatRole.USER, content = "same", createdAtMillis = 100)

        assertEquals(
            SourceDataResolution.MATCH,
            resolveSourceData(ChatData.version(listOf(message)), ChatData.version(listOf(message))),
        )
    }

    @Test
    fun `local state wins when remote state is not newer or identical`() {
        val local = listOf(ChatMessage(role = ChatRole.USER, content = "local", createdAtMillis = 200))
        val remote = listOf(ChatMessage(role = ChatRole.USER, content = "remote", createdAtMillis = 100))

        assertEquals(SourceDataResolution.KEEP_LOCAL, resolveSourceData(ChatData.version(local), ChatData.version(remote)))
    }

    @Test
    fun `chat uses the compatible generic storage descriptor`() {
        assertEquals("source-client", ChatData.descriptor.remoteAppId)
        assertEquals("conversation", ChatData.descriptor.id)
        assertEquals("source-client-conversation", ChatData.descriptor.snapshotFormat)
        assertEquals(1, ChatData.descriptor.formatVersion)
    }
}
