package com.source.client.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverKnowledgeDataTest {
    @Test
    fun `Entity identity is opaque and independent of interpreted properties`() {
        val first = SilverEntity.create()
        val second = SilverEntity.create()

        assertNotEquals(first.id, second.id)
        assertTrue(SILVER_ENTITY_ID_PATTERN.matches(first.id))
    }

    @Test
    fun `Claim factory implements the shared canonical identity`() {
        val observation = testObservation()
        val claim = SilverClaim.create(
            subjectEntityId = SUBJECT_ID,
            predicate = "status",
            value = SilverJsonString("active"),
            supportingObservationIds = listOf(observation.id),
            confidence = .8,
            producer = observation.producer,
            createdAtMillis = 100,
        )

        assertEquals("1c8c39d829a547130df033b7447075208330399e9af0afe256644dc652d81cfa", claim.id)
    }

    @Test
    fun `Claim identity ignores lifecycle and time but preserves competing interpretations`() {
        val observation = testObservation()
        val active = claim(observation, SilverJsonString("active"), SilverClaimState.ACTIVE, 100)
        val retracted = claim(observation, SilverJsonString("active"), SilverClaimState.RETRACTED, 200)
        val competing = claim(observation, SilverJsonString("paused"), SilverClaimState.ACTIVE, 100)

        assertEquals(active.id, retracted.id)
        assertNotEquals(active.id, competing.id)
    }

    @Test
    fun `Dataset accepts property and relationship Claims with provenance`() {
        val evidence = testEvidence()
        val observation = testObservation(evidence)
        val subject = entity(SUBJECT_ID)
        val related = entity(OBJECT_ID)
        val property = claim(observation, SilverJsonString("active"))
        val relationship = SilverClaim.create(
            subjectEntityId = subject.id,
            predicate = "based-in",
            objectEntityId = related.id,
            supportingObservationIds = listOf(observation.id),
            confidence = .9,
            producer = observation.producer,
            createdAtMillis = 101,
        )
        val dataset = SilverDataset(
            evidence = listOf(evidence),
            observations = listOf(observation),
            entities = listOf(subject, related),
            claims = listOf(property, relationship),
            modifiedAtMillis = 102,
        )

        SilverData.version(dataset)
        assertEquals(setOf(property, relationship), dataset.claims.toSet())
    }

    @Test
    fun `Merge keeps competing Claims and does not resurrect retracted state`() {
        val evidence = testEvidence()
        val observation = testObservation(evidence)
        val entity = entity(SUBJECT_ID)
        val active = claim(observation, SilverJsonString("active"), SilverClaimState.ACTIVE, 100)
        val retracted = active.copy(state = SilverClaimState.RETRACTED, createdAtMillis = 200)
        val competing = claim(observation, SilverJsonString("paused"), SilverClaimState.ACTIVE, 110)
        val base = SilverDataset(
            evidence = listOf(evidence),
            observations = listOf(observation),
            entities = listOf(entity),
            claims = listOf(active),
            modifiedAtMillis = 100,
        )
        val remote = base.copy(claims = listOf(retracted, competing), modifiedAtMillis = 200)

        val merged = SilverData.merge(base, remote)

        assertEquals(
            setOf(retracted.copy(createdAtMillis = active.createdAtMillis), competing),
            merged.claims.toSet(),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Dataset rejects Claims without stored supporting Observations`() {
        val observation = testObservation()
        val entity = entity(SUBJECT_ID)
        val unsupported = claim(observation, SilverJsonBoolean(true))

        SilverData.encode(SilverDataset(entities = listOf(entity), claims = listOf(unsupported)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Claim rejects ambiguous Entity and scalar objects`() {
        val observation = testObservation()

        SilverClaim.create(
            subjectEntityId = SUBJECT_ID,
            predicate = "invalid",
            objectEntityId = OBJECT_ID,
            value = SilverJsonNumber(1.0),
            supportingObservationIds = listOf(observation.id),
            producer = observation.producer,
            createdAtMillis = 100,
        )
    }

    @Test
    fun `Claim rejects non-scalar JSON values`() {
        val observation = testObservation()
        val invalidValues = listOf(
            SilverJsonObject(emptyMap()),
            SilverJsonArray(emptyList()),
            SilverJsonNull,
        )

        invalidValues.forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                SilverClaim.create(
                    subjectEntityId = SUBJECT_ID,
                    predicate = "invalid",
                    value = value,
                    supportingObservationIds = listOf(observation.id),
                    producer = observation.producer,
                    createdAtMillis = 100,
                )
            }
        }
    }

    private fun entity(id: String) = SilverEntity(id)

    private fun claim(
        observation: SilverObservation,
        value: SilverJsonValue,
        state: SilverClaimState = SilverClaimState.ACTIVE,
        createdAtMillis: Long = 100,
    ) = SilverClaim.create(
        subjectEntityId = SUBJECT_ID,
        predicate = "status",
        value = value,
        supportingObservationIds = listOf(observation.id),
        confidence = .8,
        producer = observation.producer,
        state = state,
        createdAtMillis = createdAtMillis,
    )

    private companion object {
        const val SUBJECT_ID = "00000000-0000-4000-8000-000000000001"
        const val OBJECT_ID = "00000000-0000-4000-8000-000000000002"
    }
}
