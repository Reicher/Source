package com.source.client.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverExtractionTest {
    @Test
    fun `entity identity is deterministic across casing and whitespace`() {
        assertEquals(entityId("Project", " Source "), entityId("project", "source"))
    }

    @Test
    fun `chunking is deterministic and observes the UTF-8 byte limit`() {
        val text = ("åäö words and lines\n").repeat(30)
        val first = deterministicTextChunks(text, 80)
        val second = deterministicTextChunks(text, 80)

        assertEquals(first, second)
        assertEquals(text.trim().replace(Regex("\\s+"), " "), first.joinToString(" ").replace(Regex("\\s+"), " "))
        assertTrue(first.all { it.toByteArray(Charsets.UTF_8).size <= 80 })
    }

}
