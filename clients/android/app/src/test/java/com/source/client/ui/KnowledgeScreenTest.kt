package com.source.client.ui

import com.source.client.knowledge.ConservativeSilverResolver
import com.source.client.knowledge.SILVER_ATTRIBUTE_CANDIDATE_KIND
import com.source.client.knowledge.SILVER_RESOLUTION_KIND
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import com.source.client.storage.testEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeScreenTest {
    private val resolver = ConservativeSilverResolver()

    @Test
    fun `unresolved observations remain visible in the source trace`() {
        val observation = candidate("status", "active")
        val silver = dataset(listOf(observation))

        val source = buildKnowledgeUiState(silver, library()).sources.single()

        assertEquals("notes.txt", source.name)
        assertEquals(1, source.evidence.size)
        assertEquals(SilverInspectorObservationStatus.UNRESOLVED, source.observations.single().status)
        assertEquals("source.android.silver-extraction", source.observations.single().producer.processorId)
        assertEquals("model-4b", source.observations.single().producer.modelId)
        assertTrue(source.entities.isEmpty())
        assertTrue(source.claims.isEmpty())
        assertEquals(1, source.unresolvedCount)
    }

    @Test
    fun `resolved source exposes the full Bronze to Claim provenance chain`() {
        val observation = candidate("status", "active")
        val base = dataset(listOf(observation))
        val resolution = resolver.resolve(base, listOf(observation), 200)
        val silver = base.with(resolution)

        val source = buildKnowledgeUiState(silver, library()).sources.single()

        assertTrue(source.canOpenSource)
        assertEquals(testEvidence().bronzeContentSha256, source.evidence.single().contentSha256)
        assertEquals(2, source.observations.size)
        assertEquals(
            SilverInspectorObservationStatus.RESOLVED,
            source.observations.single { it.kind == SILVER_ATTRIBUTE_CANDIDATE_KIND }.status,
        )
        val decision = source.observations.single { it.kind == SILVER_RESOLUTION_KIND }
        assertEquals("source.android.silver-resolution", decision.producer.processorId)
        assertEquals("1", decision.producer.processorVersion)
        assertEquals(1, source.entities.size)
        assertEquals("Source", source.entities.single().name)
        assertEquals("project", source.entities.single().type)
        val status = source.claims.single { it.predicate == "status" }
        assertEquals("Source", status.subjectName)
        assertEquals("“active”", status.objectDisplay)
        assertTrue(status.supportingObservationIds.contains(observation.id))
        assertTrue(status.supportingObservationIds.contains(decision.id))
    }

    @Test
    fun `competing active Claim values are distinguished`() {
        val observations = listOf(candidate("status", "active"), candidate("status", "paused"))
        val base = dataset(observations)
        val silver = base.with(resolver.resolve(base, observations, 200))

        val source = buildKnowledgeUiState(silver, library()).sources.single()
        val statusClaims = source.claims.filter { it.predicate == "status" }

        assertEquals(2, statusClaims.size)
        assertTrue(statusClaims.all { it.competing })
        assertEquals(2, source.competingCount)
        assertFalse(source.claims.filter { it.predicate == "name" }.any { it.competing })
    }

    @Test
    fun `a Bronze source can be selected before Silver processing starts`() {
        val source = buildKnowledgeUiState(SilverDataset(), library()).sources.single()

        assertEquals("source-1", source.id)
        assertTrue(source.evidence.isEmpty())
        assertTrue(source.observations.isEmpty())
    }

    @Test
    fun `arbitrary observation payloads remain inspectable`() {
        val evidence = testEvidence()
        val observation = SilverObservation.create(
            kind = "raw-text",
            payload = SilverJsonString("line one\n\"line two\""),
            evidenceIds = listOf(evidence.id),
            producer = SilverProducer.create("source.android.raw", "1"),
            createdAtMillis = 100,
        )

        val source = buildKnowledgeUiState(dataset(listOf(observation)), library()).sources.single()

        assertEquals("\"line one\\n\\\"line two\\\"\"", source.observations.single().payload)
        assertEquals(SilverInspectorObservationStatus.TRACE, source.observations.single().status)
    }

    private fun dataset(observations: List<SilverObservation>) = SilverDataset(
        evidence = listOf(testEvidence()),
        observations = observations,
        modifiedAtMillis = 100,
    )

    private fun SilverDataset.with(resolution: com.source.client.knowledge.SilverResolutionResult) = copy(
        observations = (observations + resolution.observations).associateBy(SilverObservation::id).values.toList(),
        entities = (entities + resolution.entities).associateBy(SilverEntity::id).values.toList(),
        claims = (claims + resolution.claims).associateBy(SilverClaim::id).values.toList(),
        modifiedAtMillis = modifiedAtMillis + 1,
    )

    private fun candidate(predicate: String, value: String): SilverObservation {
        val evidence = testEvidence()
        return SilverObservation.create(
            kind = SILVER_ATTRIBUTE_CANDIDATE_KIND,
            payload = SilverJsonObject(mapOf(
                "subject" to SilverJsonObject(mapOf(
                    "name" to SilverJsonString("Source"),
                    "type" to SilverJsonString("project"),
                )),
                "predicate" to SilverJsonString(predicate),
                "value" to SilverJsonString(value),
            )),
            evidenceIds = listOf(evidence.id),
            confidence = .9,
            producer = SilverProducer.create("source.android.silver-extraction", "3", "model-4b"),
            createdAtMillis = 100,
        )
    }

    private fun library() = LibraryUiState(items = listOf(LibraryUiItem(
        id = "source-1",
        filename = "notes.txt",
        sourceType = "file",
        mimeType = "text/plain",
        byteCount = 120,
        createdAtMillis = 90,
        syncState = LibrarySyncState.LOCAL_ONLY,
        localAvailable = true,
        nodeAvailable = false,
        canRemoveFromDevice = false,
        canDeleteFromSource = true,
        previewKind = LibraryPreviewKind.TEXT,
    )))
}
