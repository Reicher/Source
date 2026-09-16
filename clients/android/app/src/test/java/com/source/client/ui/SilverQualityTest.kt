package com.source.client.ui

import com.source.client.knowledge.BronzeTextSource
import com.source.client.knowledge.SILVER_EXTRACTION_COMPLETE_KIND
import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
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
    fun `removing a source drops a cross-source Observation without tombstoning other sources`() {
        val removedEvidence = testEvidence("removed-source", "a".repeat(64))
        val otherEvidence = testEvidence("other-source", "b".repeat(64))
        val crossSourceObservation = SilverObservation.create(
            kind = "relationship-candidate",
            payload = SilverJsonObject(emptyMap()),
            evidenceIds = listOf(removedEvidence.id, otherEvidence.id),
            producer = SilverProducer.create("processor", "1"),
            createdAtMillis = 100,
        )
        val entity = SilverEntity("00000000-0000-4000-8000-000000000001")
        val claim = SilverClaim.create(
            subjectEntityId = entity.id,
            predicate = "status",
            value = SilverJsonString("active"),
            supportingObservationIds = listOf(crossSourceObservation.id),
            producer = crossSourceObservation.producer,
            createdAtMillis = 100,
        )
        val dataset = SilverDataset(
            evidence = listOf(removedEvidence, otherEvidence),
            observations = listOf(crossSourceObservation),
            entities = listOf(entity),
            claims = listOf(claim),
            modifiedAtMillis = 100,
        )

        val updated = removeSilverSources(dataset, setOf("removed-source"), 101)

        assertTrue(updated.evidence.isEmpty())
        assertTrue(updated.observations.isEmpty())
        assertTrue(updated.entities.isEmpty())
        assertTrue(updated.claims.isEmpty())
        assertEquals(mapOf("removed-source" to 101L), updated.removedSourceIds)
        SilverData.version(updated)
    }

    @Test
    fun `removing one source preserves an Entity still referenced by another source`() {
        val originEvidence = testEvidence("source-a", "a".repeat(64))
        val retainedEvidence = testEvidence("source-b", "b".repeat(64))
        val originObservation = testObservation(originEvidence, createdAtMillis = 100)
        val retainedObservation = testObservation(retainedEvidence, createdAtMillis = 110)
        val retainedComplete = testObservation(
            retainedEvidence,
            createdAtMillis = 110,
            kind = SILVER_EXTRACTION_COMPLETE_KIND,
        )
        val entity = SilverEntity("00000000-0000-4000-8000-000000000001")
        val retainedClaim = SilverClaim.create(
            subjectEntityId = entity.id,
            predicate = "status",
            value = SilverJsonString("active"),
            supportingObservationIds = listOf(retainedObservation.id),
            producer = retainedObservation.producer,
            createdAtMillis = 110,
        )
        val dataset = SilverDataset(
            evidence = listOf(originEvidence, retainedEvidence),
            observations = listOf(originObservation, retainedObservation, retainedComplete),
            entities = listOf(entity),
            claims = listOf(retainedClaim),
            modifiedAtMillis = 110,
        )

        val updated = removeSilverSources(dataset, setOf("source-a"), 120)

        assertEquals(mapOf("source-a" to 120L), updated.removedSourceIds)
        assertEquals(listOf(retainedEvidence), updated.evidence)
        assertEquals(setOf(retainedObservation, retainedComplete), updated.observations.toSet())
        assertEquals(listOf(entity), updated.entities)
        assertEquals(listOf(retainedClaim), updated.claims)
        assertEquals(updated, SilverData.merge(updated, dataset))
        assertFalse(
            needsSilverRefinement(
                updated,
                BronzeTextSource("source-b", "retained.txt", "file", "b".repeat(64), "retained"),
                AiModelMetadata("model-4b", 4_000_000_000),
                "2",
            ),
        )
        SilverData.version(updated)
    }
}
