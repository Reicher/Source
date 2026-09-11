package com.source.client.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceNodeApiTest {
    @Test
    fun `AI delta text accepts whitespace tokens`() {
        assertEquals(" \n", requiredAiDeltaText(" \n"))
    }

    @Test
    fun `AI delta text rejects empty and non-string values`() {
        listOf<Any?>(null, "", 1).forEach { value ->
            val failure = runCatching { requiredAiDeltaText(value) }.exceptionOrNull()

            assertTrue(failure is SourceApiException)
            assertEquals("invalid_response", (failure as SourceApiException).code)
        }
    }
}
