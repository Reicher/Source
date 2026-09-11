package com.source.client.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SourceAiContractTest {
    @Test
    fun `v1 exposes no system role and reserves vision as a capability`() {
        assertEquals(setOf(SourceAiRole.USER, SourceAiRole.ASSISTANT), SourceAiRole.entries.toSet())
        assertEquals(setOf(SourceAiCapability.TEXT, SourceAiCapability.VISION), SourceAiCapability.entries.toSet())
        assertFalse(SourceAiRole.entries.any { it.name == "SYSTEM" })
        assertEquals("none-v1", PROMPT_POLICY_NONE_V1)
        assertEquals(setOf(SourceAiReasoning.OFF), SourceAiReasoning.entries.toSet())
        assertEquals(setOf(SourceAiWorkload.INTERACTIVE, SourceAiWorkload.BACKGROUND), SourceAiWorkload.entries.toSet())
    }

    @Test
    fun `local streaming uses a finite event buffer`() {
        assertEquals(64, LOCAL_AI_EVENT_BUFFER_CAPACITY)
    }
}
