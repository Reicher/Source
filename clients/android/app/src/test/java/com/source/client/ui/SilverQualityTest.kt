package com.source.client.ui

import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.SILVER_EXTRACTION_COMPLETE_KIND
import com.source.client.knowledge.sha256Hex
import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverBatchCheckpoint
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import com.source.client.storage.SilverRefinementCheckpoint
import com.source.client.storage.testBatchResult
import com.source.client.storage.testEvidence
import com.source.client.storage.testObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverQualityTest {
    @Test
    fun `source revision processor version and model identity select new work`() {
        val evidence = testEvidence("source", "a".repeat(64))
        val observation = testObservation(
            evidence,
            processorVersion = "2",
            modelId = "model-4b",
            kind = SILVER_EXTRACTION_COMPLETE_KIND,
        )
        val dataset = SilverDataset(listOf(evidence), listOf(observation), 100)
        val sameSource = BronzeTextSource("source", "file", "file", "a".repeat(64), "text")
        val changedSource = sameSource.copy(contentSha256 = "b".repeat(64))

        assertFalse(needsSilverRefinement(dataset, sameSource, AiModelMetadata("model-4b", 4_000_000_000), "2"))
        assertTrue(needsSilverRefinement(dataset, changedSource, AiModelMetadata("model-4b", 4_000_000_000), "2"))
        assertTrue(needsSilverRefinement(dataset, sameSource, AiModelMetadata("model-4b", 4_000_000_000), "3"))
        assertTrue(needsSilverRefinement(dataset, sameSource, AiModelMetadata("model-9b", 9_000_000_000), "2"))
    }

    @Test
    fun `checkpoint is reusable only for the same deterministic work`() {
        val source = BronzeTextSource("source-1", "file", "file", "a".repeat(64), "first second")
        val chunks = listOf("first", "second")
        val model = AiModelMetadata("model-4b", 4_000_000_000)
        val checkpoint = SilverRefinementCheckpoint(
            bronzeSourceId = source.id,
            bronzeContentSha256 = source.contentSha256,
            processorVersion = "2",
            totalBatches = chunks.size,
            completedBatches = listOf(
                SilverBatchCheckpoint(0, sha256Hex(chunks[0]), testBatchResult()),
            ),
        )

        assertTrue(isReusableCheckpoint(checkpoint, source, chunks, model, "2"))
        assertFalse(isReusableCheckpoint(checkpoint, source, listOf("changed", "second"), model, "2"))
        assertFalse(isReusableCheckpoint(checkpoint, source, chunks, AiModelMetadata("model-9b", 9_000_000_000), "2"))
        assertFalse(isReusableCheckpoint(checkpoint, source.copy(contentSha256 = "b".repeat(64)), chunks, model, "2"))
        assertFalse(isReusableCheckpoint(checkpoint, source, chunks, model, "3"))
    }

    @Test
    fun `resume selects the first unfinished batch`() {
        assertEquals(1, firstUnfinishedBatch(5, setOf(0, 2, 3)))
        assertEquals(null, firstUnfinishedBatch(3, setOf(0, 1, 2)))
    }

    @Test
    fun `removing a source prunes Evidence orphaned by cross-source Observations`() {
        val removedEvidence = testEvidence("removed-source", "a".repeat(64))
        val otherEvidence = testEvidence("other-source", "b".repeat(64))
        val crossSourceObservation = SilverObservation.create(
            kind = "relationship-candidate",
            payload = SilverJsonObject(emptyMap()),
            evidenceIds = listOf(removedEvidence.id, otherEvidence.id),
            producer = SilverProducer.create("processor", "1"),
            createdAtMillis = 100,
        )
        val dataset = SilverDataset(
            evidence = listOf(removedEvidence, otherEvidence),
            observations = listOf(crossSourceObservation),
            modifiedAtMillis = 100,
        )

        val updated = removeSilverSources(dataset, setOf("removed-source"), 101)

        assertTrue(updated.evidence.isEmpty())
        assertTrue(updated.observations.isEmpty())
        assertEquals(mapOf("removed-source" to 101L), updated.removedSourceIds)
        SilverData.version(updated)
    }
}
