package com.source.client.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.source.client.model.AiModelMetadata

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

    @Test
    fun `runtime state distinguishes install load readiness and inference failures`() {
        val capabilities = SourceAiCapabilities(setOf(SourceAiCapability.TEXT), true, true, 1_024)
        val model = AiModelMetadata("model", 1)

        assertFalse(SourceAiRuntimeState(SourceAiAvailability.MODEL_NOT_INSTALLED).isUsable)
        assertFalse(SourceAiRuntimeState(SourceAiAvailability.MODEL_PRESENT, model, capabilities).isUsable)
        assertFalse(SourceAiRuntimeState(
            SourceAiAvailability.MODEL_LOAD_FAILED,
            model,
            capabilities,
            "model_load_failed",
        ).isUsable)
        assertTrue(SourceAiRuntimeState(SourceAiAvailability.READY, model, capabilities).isUsable)
        assertTrue(SourceAiRuntimeState(
            SourceAiAvailability.INFERENCE_FAILED,
            model,
            capabilities,
            "inference_failed",
        ).isUsable)
    }

    @Test
    fun `request timeouts are bounded by the shared contract`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceAiRequest(conversationId = "conversation", messages = emptyList(), timeoutMillis = 999)
        }
        assertEquals(300_000L, DEFAULT_SOURCE_AI_TIMEOUT_MILLIS)
    }
}
