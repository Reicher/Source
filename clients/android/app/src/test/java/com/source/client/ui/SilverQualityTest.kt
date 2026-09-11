package com.source.client.ui

import com.source.client.knowledge.BronzeTextSource
import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverQualityTest {
    @Test
    fun `new Bronze and better processors or models trigger replacement`() {
        val existing = result("a", 4_000_000_000, 1)
        val sameSource = BronzeTextSource("source", "file", "file", "a".repeat(64), "text")
        val changedSource = sameSource.copy(contentSha256 = "b".repeat(64))

        assertFalse(needsSilverRefinement(existing, sameSource, AiModelMetadata("4b", 4_000_000_000), 1))
        assertTrue(needsSilverRefinement(existing, changedSource, AiModelMetadata("4b", 4_000_000_000), 1))
        assertTrue(needsSilverRefinement(existing, sameSource, AiModelMetadata("9b", 9_000_000_000), 1))
        assertTrue(needsSilverRefinement(existing, sameSource, AiModelMetadata("4b", 4_000_000_000), 2))
        assertTrue(shouldReplaceSilver(existing, result("a", 9_000_000_000, 1)))
        assertFalse(shouldReplaceSilver(existing, result("a", 3_000_000_000, 1)))
    }

    private fun result(hash: String, parameters: Long, version: Int) = SilverResult(
        bronzeSourceId = "source",
        bronzeContentSha256 = hash.repeat(64),
        entities = emptyList(),
        claims = emptyList(),
        modelId = "model",
        parameterCount = parameters,
        processorVersion = version,
        processedAtMillis = 1,
    )
}
