package com.source.client.ui

import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.sha256Hex
import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverBatchCheckpoint
import com.source.client.storage.SilverRefinementCheckpoint
import com.source.client.storage.SilverResult
import org.junit.Assert.assertEquals
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

    @Test
    fun `checkpoint is reusable only for the same deterministic work`() {
        val source = BronzeTextSource("source", "file", "file", "a".repeat(64), "first second")
        val chunks = listOf("first", "second")
        val model = AiModelMetadata("model", 4_000_000_000)
        val batchResult = result("a", model.parameterCount, 2)
        val checkpoint = SilverRefinementCheckpoint(
            bronzeSourceId = source.id,
            bronzeContentSha256 = source.contentSha256,
            processorVersion = 2,
            totalBatches = chunks.size,
            completedBatches = listOf(SilverBatchCheckpoint(0, sha256Hex(chunks[0]), batchResult)),
        )

        assertTrue(isReusableCheckpoint(checkpoint, source, chunks, model, 2))
        assertFalse(isReusableCheckpoint(checkpoint, source, listOf("changed", "second"), model, 2))
        assertFalse(isReusableCheckpoint(checkpoint, source, chunks, AiModelMetadata("better", 9_000_000_000), 2))
        assertFalse(isReusableCheckpoint(checkpoint, source.copy(contentSha256 = "b".repeat(64)), chunks, model, 2))
        assertFalse(isReusableCheckpoint(checkpoint, source, chunks, model, 3))
    }

    @Test
    fun `resume selects the first unfinished batch`() {
        assertEquals(1, firstUnfinishedBatch(5, setOf(0, 2, 3)))
        assertEquals(null, firstUnfinishedBatch(3, setOf(0, 1, 2)))
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
