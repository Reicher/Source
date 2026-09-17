package com.source.client.ui

import com.source.client.knowledge.ConservativeSilverResolver
import com.source.client.knowledge.SILVER_ATTRIBUTE_CANDIDATE_KIND
import com.source.client.knowledge.SILVER_RELATIONSHIP_CANDIDATE_KIND
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverClaimState
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverEvidence
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
        assertEquals(1, source.observations.size)
        assertEquals(
            SilverInspectorObservationStatus.RESOLVED,
            source.observations.single { it.kind == SILVER_ATTRIBUTE_CANDIDATE_KIND }.status,
        )
        assertEquals(1, source.entities.size)
        assertEquals("Source", source.entities.single().name)
        assertEquals("project", source.entities.single().type)
        val status = source.claims.single { it.predicate == "status" }
        assertEquals("Source", status.subjectName)
        assertEquals("“active”", status.objectDisplay)
        assertEquals(listOf(observation.id), status.supportingObservationIds)
        assertEquals("source.android.silver-resolution", status.producer.processorId)
        assertFalse(source.claims.any { it.predicate == "name" || it.predicate == "entity-type" })
    }

    @Test
    fun `observations supported by superseded Claims do not need review`() {
        val observation = candidate("status", "active")
        val base = dataset(listOf(observation))
        val resolved = base.with(resolver.resolve(base, listOf(observation), 200))
        val historical = resolved.copy(
            claims = resolved.claims.map { it.copy(state = SilverClaimState.SUPERSEDED) },
        )

        val source = buildKnowledgeUiState(historical, library()).sources.single()

        assertEquals(SilverInspectorObservationStatus.RESOLVED, source.observations.single().status)
        assertEquals(0, source.unresolvedCount)
        assertTrue(source.claims.isEmpty())
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
    fun `duplicate resolved facts are collapsed for presentation`() {
        val observations = listOf(
            candidate("status", "active"),
            candidate("status", "active", confidence = .8),
        )
        val base = dataset(observations)
        val silver = base.with(resolver.resolve(base, observations, 200))

        val source = buildKnowledgeUiState(silver, library()).sources.single()

        assertEquals(1, source.claims.size)
        assertEquals("status", source.claims.single().predicate)
        assertEquals(2, source.claims.single().supportingObservationIds.size)
        assertEquals(.9, source.claims.single().confidence)
    }

    @Test
    fun `technical labels are made readable`() {
        assertEquals("Entity type", "entity-type".displayLabel())
        assertEquals("Lives in", "lives_in".displayLabel())
        assertEquals("Conversation", "conversation".displayLabel())
    }

    @Test
    fun `entity types select recognizable icons for entities and relationship targets`() {
        val observation = relationshipCandidate()
        val base = dataset(listOf(observation))
        val silver = base.with(resolver.resolve(base, listOf(observation), 200))

        val source = buildKnowledgeUiState(silver, library()).sources.single()
        val entitiesByName = source.entities.associateBy(SilverInspectorEntityUi::name)
        val fact = source.claims.single()

        assertEquals(SilverInspectorEntityIcon.GENERIC, entitiesByName.getValue("Robin").icon)
        assertEquals(SilverInspectorEntityIcon.LOCATION, entitiesByName.getValue("Cozy place").icon)
        assertEquals(SilverInspectorEntityIcon.LOCATION, fact.objectEntityIcon)
    }

    @Test
    fun `entity detail contains only claims about the selected entity`() {
        val observation = relationshipCandidate()
        val base = dataset(listOf(observation))
        val silver = base.with(resolver.resolve(base, listOf(observation), 200))

        val source = buildKnowledgeUiState(silver, library()).sources.single()
        val entitiesByName = source.entities.associateBy(SilverInspectorEntityUi::name)
        val robin = entitiesByName.getValue("Robin")
        val place = entitiesByName.getValue("Cozy place")

        assertEquals(listOf("rests-at"), source.claimsFor(robin.id).map(SilverInspectorClaimUi::predicate))
        assertTrue(source.claimsFor(place.id).isEmpty())
    }

    @Test
    fun `entity detail claims remain attributable to their Bronze source`() {
        val firstEvidence = testEvidence()
        val secondEvidence = testEvidence("source-2", "b".repeat(64))
        val observations = listOf(
            candidate("status", "active", evidence = firstEvidence),
            candidate("purpose", "archive", evidence = secondEvidence),
        )
        val base = SilverDataset(
            evidence = listOf(firstEvidence, secondEvidence),
            observations = observations,
            modifiedAtMillis = 100,
        )
        val silver = base.with(resolver.resolve(base, observations, 200))

        val sources = buildKnowledgeUiState(silver, library()).sources.associateBy(SilverInspectorSourceUi::id)
        val firstSource = sources.getValue("source-1")
        val secondSource = sources.getValue("source-2")

        assertEquals(listOf("status"), firstSource.claimsFor(firstSource.entities.single().id).map { it.predicate })
        assertEquals(listOf("purpose"), secondSource.claimsFor(secondSource.entities.single().id).map { it.predicate })
    }

    @Test
    fun `global Silver entities combine active Claims across Bronze sources`() {
        val firstEvidence = testEvidence("source-1", "a".repeat(64))
        val secondEvidence = testEvidence("source-2", "b".repeat(64))
        val observations = listOf(
            candidate("status", "active", evidence = firstEvidence),
            candidate("purpose", "private knowledge", evidence = secondEvidence),
        )
        val base = SilverDataset(
            evidence = listOf(firstEvidence, secondEvidence),
            observations = observations,
            modifiedAtMillis = 100,
        )
        val silver = base.with(resolver.resolve(base, observations, 200))

        val entity = buildKnowledgeUiState(silver, library()).entities.single()

        assertEquals("Source", entity.name)
        assertEquals("project", entity.type)
        assertTrue(entity.claims.any { it.predicate == "status" && it.objectDisplay == "“active”" })
        assertTrue(entity.claims.any { it.predicate == "purpose" && it.objectDisplay == "“private knowledge”" })
    }

    @Test
    fun `global entity Claims present scalar values and related entities but omit inactive Claims`() {
        val observations = listOf(
            attributeCandidate("Josefin Broström", "FirstName", "Josefin"),
            attributeCandidate("Josefin Broström", "Email", "josefin@example.com"),
            attributeCandidate("Josefin Broström", "Phone", "+46 70 123 45 67"),
            relationshipCandidate("Josefin Broström", "Brother", "Martin Broström"),
        )
        val base = dataset(observations)
        val resolved = base.with(resolver.resolve(base, observations, 200))
        val silver = resolved.copy(claims = resolved.claims.map { claim ->
            when (claim.predicate) {
                "Email" -> claim.copy(state = SilverClaimState.SUPERSEDED)
                "Phone" -> claim.copy(state = SilverClaimState.RETRACTED)
                else -> claim
            }
        })

        val entities = buildKnowledgeUiState(silver, library()).entities.associateBy(SilverBrowserEntityUi::name)
        val josefin = entities.getValue("Josefin Broström")
        val martin = entities.getValue("Martin Broström")
        val firstName = josefin.claims.single { it.predicate == "FirstName" }
        val brother = josefin.claims.single { it.predicate == "Brother" }

        assertEquals("“Josefin”", firstName.objectDisplay)
        assertEquals(null, firstName.objectEntityId)
        assertEquals("Martin Broström", brother.objectDisplay)
        assertEquals(martin.id, brother.objectEntityId)
        assertEquals(2, josefin.activeClaimCount)
        assertFalse(josefin.claims.any { it.predicate == "Email" || it.predicate == "Phone" })
    }

    @Test
    fun `global Silver search filters display names case insensitively`() {
        val observation = relationshipCandidate()
        val base = dataset(listOf(observation))
        val entities = buildKnowledgeUiState(
            base.with(resolver.resolve(base, listOf(observation), 200)),
            library(),
        ).entities

        assertEquals(listOf("Robin"), filterSilverEntities(entities, "  ROB  ").map { it.name })
        assertTrue(filterSilverEntities(entities, "missing").isEmpty())
        assertEquals(entities, filterSilverEntities(entities, " "))
    }

    @Test
    fun `common entity types have stable presentation icons`() {
        assertEquals(SilverInspectorEntityIcon.PERSON, entityIcon("person"))
        assertEquals(SilverInspectorEntityIcon.LOCATION, entityIcon("city"))
        assertEquals(SilverInspectorEntityIcon.ORGANIZATION, entityIcon("company"))
        assertEquals(SilverInspectorEntityIcon.EVENT, entityIcon("conference"))
        assertEquals(SilverInspectorEntityIcon.DOCUMENT, entityIcon("article"))
        assertEquals(SilverInspectorEntityIcon.GENERIC, entityIcon("idea"))
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
        entities = (entities + resolution.entities).associateBy(SilverEntity::id).values.toList(),
        claims = (claims + resolution.claims).associateBy(SilverClaim::id).values.toList(),
        modifiedAtMillis = modifiedAtMillis + 1,
    )

    private fun candidate(
        predicate: String,
        value: String,
        confidence: Double = .9,
        evidence: SilverEvidence = testEvidence(),
    ): SilverObservation = attributeCandidate("Source", predicate, value, "project", confidence, evidence)

    private fun attributeCandidate(
        subjectName: String,
        predicate: String,
        value: String,
        subjectType: String = "person",
        confidence: Double = .9,
        evidence: SilverEvidence = testEvidence(),
    ): SilverObservation = SilverObservation.create(
            kind = SILVER_ATTRIBUTE_CANDIDATE_KIND,
            payload = SilverJsonObject(mapOf(
                "subject" to SilverJsonObject(mapOf(
                    "name" to SilverJsonString(subjectName),
                    "type" to SilverJsonString(subjectType),
                )),
                "predicate" to SilverJsonString(predicate),
                "value" to SilverJsonString(value),
            )),
            evidenceIds = listOf(evidence.id),
            confidence = confidence,
            producer = SilverProducer.create("source.android.silver-extraction", "3", "model-4b"),
            createdAtMillis = 100,
        )

    private fun relationshipCandidate(): SilverObservation = relationshipCandidate("Robin", "rests-at", "Cozy place", "cat", "place")

    private fun relationshipCandidate(
        subjectName: String,
        predicate: String,
        objectName: String,
        subjectType: String = "person",
        objectType: String = "person",
        evidence: SilverEvidence = testEvidence(),
    ): SilverObservation = SilverObservation.create(
            kind = SILVER_RELATIONSHIP_CANDIDATE_KIND,
            payload = SilverJsonObject(mapOf(
                "subject" to SilverJsonObject(mapOf(
                    "name" to SilverJsonString(subjectName),
                    "type" to SilverJsonString(subjectType),
                )),
                "predicate" to SilverJsonString(predicate),
                "object" to SilverJsonObject(mapOf(
                    "name" to SilverJsonString(objectName),
                    "type" to SilverJsonString(objectType),
                )),
            )),
            evidenceIds = listOf(evidence.id),
            confidence = .9,
            producer = SilverProducer.create("source.android.silver-extraction", "3", "model-4b"),
            createdAtMillis = 100,
        )

    private fun library() = LibraryUiState(items = listOf(LibraryUiItem(
        id = "source-1",
        filename = "notes.txt",
        sourceType = "file",
        mimeType = "text/plain",
        byteCount = 120,
        createdAtMillis = 90,
        bronzeContentSha256 = "a".repeat(64),
        bronzeStatus = BronzeNodeStatus.NOT_ON_NODE,
        localAvailable = true,
        canRemoveFromDevice = false,
        canDeleteFromSource = true,
        previewKind = LibraryPreviewKind.TEXT,
    )))
}
