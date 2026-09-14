package com.source.client.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverKnowledgeDataTest {
    @Test
    fun `Entity identity is opaque and independent of interpreted properties`() {
        val observation = testObservation()

        val first = SilverEntity.create(listOf(observation.id), observation.producer, 100)
        val second = SilverEntity.create(listOf(observation.id), observation.producer, 100)

        assertNotEquals(first.id, second.id)
        assertTrue(SILVER_ENTITY_ID_PATTERN.matches(first.id))
        assertEquals(listOf(observation.id), first.originObservationIds)
    }

    @Test
    fun `Claim factory implements the shared canonical identity`() {
        val observation = testObservation()
        val claim = SilverClaim.create(
            subjectEntityId = SUBJECT_ID,
            predicate = "status",
            value = SilverScalar.text("active"),
            supportingObservationIds = listOf(observation.id),
            confidence = .8,
            producer = observation.producer,
            createdAtMillis = 100,
        )

        assertEquals("093133d5734db6faa20b22e2208f23f6f2f0ef47fa4ef2b62e883e635653b827", claim.id)
    }

    @Test
    fun `Claim identity ignores lifecycle and time but preserves competing interpretations`() {
        val observation = testObservation()
        val active = claim(observation, SilverScalar.text("active"), SilverClaimState.ACTIVE, 100)
        val retracted = claim(observation, SilverScalar.text("active"), SilverClaimState.RETRACTED, 200)
        val competing = claim(observation, SilverScalar.text("paused"), SilverClaimState.ACTIVE, 100)

        assertEquals(active.id, retracted.id)
        assertNotEquals(active.id, competing.id)
    }

    @Test
    fun `Dataset accepts property and relationship Claims with provenance`() {
        val evidence = testEvidence()
        val observation = testObservation(evidence)
        val subject = entity(SUBJECT_ID, observation)
        val related = entity(OBJECT_ID, observation)
        val property = claim(observation, SilverScalar.text("active"))
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
        val entity = entity(SUBJECT_ID, observation)
        val active = claim(observation, SilverScalar.text("active"), SilverClaimState.ACTIVE, 100)
        val retracted = active.copy(state = SilverClaimState.RETRACTED, createdAtMillis = 200)
        val competing = claim(observation, SilverScalar.text("paused"), SilverClaimState.ACTIVE, 110)
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
        val entity = entity(SUBJECT_ID, observation)
        val unsupported = claim(observation, SilverScalar.boolean(true))

        SilverData.encode(SilverDataset(entities = listOf(entity), claims = listOf(unsupported)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `Claim rejects ambiguous Entity and scalar objects`() {
        val observation = testObservation()

        SilverClaim.create(
            subjectEntityId = SUBJECT_ID,
            predicate = "invalid",
            objectEntityId = OBJECT_ID,
            value = SilverScalar.number(1.0),
            supportingObservationIds = listOf(observation.id),
            producer = observation.producer,
            createdAtMillis = 100,
        )
    }

    private fun entity(id: String, observation: SilverObservation) = SilverEntity(
        id = id,
        originObservationIds = listOf(observation.id),
        createdBy = observation.producer,
        createdAtMillis = 100,
    )

    private fun claim(
        observation: SilverObservation,
        value: SilverScalar,
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
