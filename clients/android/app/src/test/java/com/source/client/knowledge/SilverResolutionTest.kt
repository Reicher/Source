package com.source.client.knowledge

import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverClaimState
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverJsonNumber
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import com.source.client.storage.testEvidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverResolutionTest {
    private val resolver = ConservativeSilverResolver()

    @Test
    fun `new relationship mentions create opaque Entities and evidence-backed Claims`() {
        val observation = relationshipObservation()
        val dataset = dataset(observation)

        val result = resolver.resolve(dataset, listOf(observation), 200)
        val resolved = dataset.with(result)

        assertEquals(2, result.entities.size)
        assertEquals(5, result.claims.size)
        val relationship = result.claims.single { it.predicate == "based-in" }
        assertTrue(relationship.objectEntityId != null)
        assertEquals(resolver.producer, relationship.producer)
        assertTrue(relationship.supportingObservationIds.contains(observation.id))
        assertEquals(listOf(observation.id), relationship.supportingObservationIds)
        assertNull(relationship.confidence)
        SilverData.version(resolved)
    }

    @Test
    fun `unique exact name and type match reuses the existing Entity`() {
        val first = attributeObservation("status", SilverJsonString("active"))
        val firstDataset = dataset(first)
        val initial = resolver.resolve(firstDataset, listOf(first), 200)
        val stored = firstDataset.with(initial)
        val second = attributeObservation("purpose", SilverJsonString("private knowledge"))
        val input = stored.copy(observations = stored.observations + second)

        val result = resolver.resolve(input, listOf(second), 300)

        assertTrue(result.entities.isEmpty())
        assertEquals(initial.entities.single().id, result.claims.single { it.predicate == "purpose" }.subjectEntityId)
        assertTrue(result.claims.all { it.supportingObservationIds == listOf(second.id) })
    }

    @Test
    fun `same name with a conflicting type remains unresolved`() {
        val established = attributeObservation("status", SilverJsonString("active"), type = "project")
        val base = dataset(established)
        val stored = base.with(resolver.resolve(base, listOf(established), 200))
        val uncertain = attributeObservation("founded", SilverJsonNumber(2025.0), type = "organization")
        val input = stored.copy(observations = stored.observations + uncertain)

        val result = resolver.resolve(input, listOf(uncertain), 300)

        assertTrue(result.entities.isEmpty())
        assertTrue(result.claims.isEmpty())
    }

    @Test
    fun `multiple exact matches remain unresolved`() {
        val inputObservation = attributeObservation("status", SilverJsonString("active"))
        val producer = resolver.producer
        val entities = listOf(
            existingEntity("00000000-0000-4000-8000-000000000001"),
            existingEntity("00000000-0000-4000-8000-000000000002"),
        )
        val claims = entities.flatMap { entity -> metadataClaims(entity, inputObservation, producer) }
        val dataset = dataset(inputObservation).copy(entities = entities, claims = claims)

        val result = resolver.resolve(dataset, listOf(inputObservation), 300)

        assertTrue(result.entities.isEmpty())
        assertTrue(result.claims.isEmpty())
    }

    @Test
    fun `repeating the same resolution is idempotent`() {
        val observation = attributeObservation("status", SilverJsonString("active"))
        val base = dataset(observation)
        val first = resolver.resolve(base, listOf(observation), 200)
        val stored = base.with(first)

        val repeated = resolver.resolve(stored, listOf(observation), 300)

        assertTrue(repeated.entities.isEmpty())
        assertEquals(first.claims.map(SilverClaim::id).toSet(), repeated.claims.map(SilverClaim::id).toSet())
    }

    @Test
    fun `a corrected later resolution retains the old Entity and provenance`() {
        val observation = attributeObservation("status", SilverJsonString("active"))
        val base = dataset(observation)
        val initial = resolver.resolve(base, listOf(observation), 200)
        val stored = base.with(initial).let { dataset ->
            dataset.copy(claims = dataset.claims.map { claim ->
                if (claim.predicate in setOf(SILVER_NAME_PREDICATE, SILVER_ENTITY_TYPE_PREDICATE)) {
                    claim.copy(state = SilverClaimState.RETRACTED)
                } else {
                    claim
                }
            })
        }
        val correctedInput = attributeObservation("purpose", SilverJsonString("private knowledge"))
        val input = stored.copy(observations = stored.observations + correctedInput)
        val newerResolver = ConservativeSilverResolver(
            SilverProducer.create(SILVER_RESOLUTION_PROCESSOR_ID, "2"),
        )

        val corrected = newerResolver.resolve(input, listOf(correctedInput), 300)
        val combined = input.with(corrected)

        assertEquals(1, corrected.entities.size)
        assertTrue(corrected.entities.single().id != initial.entities.single().id)
        assertTrue(combined.entities.contains(initial.entities.single()))
        assertTrue(corrected.claims.all { it.producer.processorVersion == "2" })
    }

    @Test
    fun `malformed candidate produces no resolved knowledge`() {
        val evidence = testEvidence()
        val observation = SilverObservation.create(
            kind = SILVER_ATTRIBUTE_CANDIDATE_KIND,
            payload = SilverJsonObject(emptyMap()),
            evidenceIds = listOf(evidence.id),
            producer = extractionProducer,
            createdAtMillis = 100,
        )

        val result = resolver.resolve(SilverDataset(listOf(evidence), listOf(observation)), listOf(observation), 200)

        assertTrue(result.entities.isEmpty())
        assertTrue(result.claims.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `resolver rejects Observation input outside the supplied Silver state`() {
        resolver.resolve(SilverDataset(), listOf(attributeObservation("status", SilverJsonString("active"))), 200)
    }

    private fun dataset(observation: SilverObservation): SilverDataset {
        val evidence = testEvidence()
        return SilverDataset(evidence = listOf(evidence), observations = listOf(observation), modifiedAtMillis = 100)
    }

    private fun SilverDataset.with(result: SilverResolutionResult) = copy(
        entities = (entities + result.entities).associateBy(SilverEntity::id).values.toList(),
        claims = (claims + result.claims).associateBy(SilverClaim::id).values.toList(),
        modifiedAtMillis = modifiedAtMillis + 1,
    )

    private fun attributeObservation(
        predicate: String,
        value: com.source.client.storage.SilverJsonValue,
        type: String = "project",
    ): SilverObservation {
        val evidence = testEvidence()
        return SilverObservation.create(
            kind = SILVER_ATTRIBUTE_CANDIDATE_KIND,
            payload = SilverJsonObject(mapOf(
                "subject" to mention("Source", type),
                "predicate" to SilverJsonString(predicate),
                "value" to value,
            )),
            evidenceIds = listOf(evidence.id),
            confidence = .9,
            producer = extractionProducer,
            createdAtMillis = 100,
        )
    }

    private fun relationshipObservation(): SilverObservation {
        val evidence = testEvidence()
        return SilverObservation.create(
            kind = SILVER_RELATIONSHIP_CANDIDATE_KIND,
            payload = SilverJsonObject(mapOf(
                "subject" to mention("Source", "project"),
                "predicate" to SilverJsonString("based-in"),
                "object" to mention("Berlin", "place"),
            )),
            evidenceIds = listOf(evidence.id),
            confidence = .9,
            producer = extractionProducer,
            createdAtMillis = 100,
        )
    }

    private fun existingEntity(id: String) = SilverEntity(id)

    private fun metadataClaims(
        entity: SilverEntity,
        observation: SilverObservation,
        producer: SilverProducer,
    ) = listOf(
        SilverClaim.create(
            entity.id,
            SILVER_NAME_PREDICATE,
            value = SilverJsonString("Source"),
            supportingObservationIds = listOf(observation.id),
            producer = producer,
            createdAtMillis = 100,
        ),
        SilverClaim.create(
            entity.id,
            SILVER_ENTITY_TYPE_PREDICATE,
            value = SilverJsonString("project"),
            supportingObservationIds = listOf(observation.id),
            producer = producer,
            createdAtMillis = 100,
        ),
    )

    private fun mention(name: String, type: String) = SilverJsonObject(mapOf(
        "name" to SilverJsonString(name),
        "type" to SilverJsonString(type),
    ))

    private companion object {
        val extractionProducer = SilverProducer.create(SILVER_EXTRACTION_PROCESSOR_ID, "3", "model-4b")
    }
}
