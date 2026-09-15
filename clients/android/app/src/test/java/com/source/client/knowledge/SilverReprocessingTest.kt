package com.source.client.knowledge

import com.source.client.model.AiModelMetadata
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverClaimState
import com.source.client.storage.SilverData
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEvidence
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverJsonString
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverReprocessingTest {
    private val resolver = ConservativeSilverResolver()

    @Test
    fun `repeating one complete generation is idempotent`() {
        val source = source()
        val extracted = generation(source, processorVersion = "1", value = "active")
        val first = replaceSilverGeneration(SilverDataset(), source, extracted, resolver, 100).dataset

        val repeated = replaceSilverGeneration(first, source, extracted, resolver, 200)

        assertSame(first, repeated.dataset)
        assertTrue(repeated.supersededClaimIds.isEmpty())
    }

    @Test
    fun `new processor version supersedes its old Claims and retains their provenance`() {
        val source = source()
        val oldGeneration = generation(source, processorVersion = "1", value = "active")
        val oldDataset = replaceSilverGeneration(SilverDataset(), source, oldGeneration, resolver, 100).dataset
        val oldObservationIds = oldDataset.observations.mapTo(mutableSetOf(), SilverObservation::id)
        val oldStatus = oldDataset.statusClaims().single()

        val replacement = generation(source, processorVersion = "2", value = "paused")
        val commit = replaceSilverGeneration(oldDataset, source, replacement, resolver, 200)

        assertEquals(3, commit.supersededClaimIds.size)
        assertEquals(SilverClaimState.SUPERSEDED, commit.dataset.claims.single { it.id == oldStatus.id }.state)
        assertEquals(
            setOf("paused"),
            commit.dataset.statusClaims().filter { it.state == SilverClaimState.ACTIVE }.mapTo(mutableSetOf()) {
                it.textValue()
            },
        )
        assertTrue(commit.dataset.observations.map(SilverObservation::id).containsAll(oldObservationIds))
        assertTrue(commit.dataset.observations.map(SilverObservation::id).containsAll(oldStatus.supportingObservationIds))
        assertEquals(oldDataset.evidence, commit.dataset.evidence)
    }

    @Test
    fun `new Bronze revision replaces knowledge but preserves old Evidence`() {
        val oldSource = source(hash = "a".repeat(64))
        val oldDataset = replaceSilverGeneration(
            SilverDataset(),
            oldSource,
            generation(oldSource, processorVersion = "1", value = "active"),
            resolver,
            100,
        ).dataset
        val newSource = oldSource.copy(contentSha256 = "b".repeat(64), text = "Source is paused")

        val replaced = replaceSilverGeneration(
            oldDataset,
            newSource,
            generation(newSource, processorVersion = "1", value = "paused"),
            resolver,
            200,
        ).dataset

        assertEquals(setOf("a".repeat(64), "b".repeat(64)), replaced.evidence.mapTo(mutableSetOf()) {
            it.bronzeContentSha256
        })
        assertEquals(setOf(SilverClaimState.ACTIVE, SilverClaimState.SUPERSEDED), replaced.statusClaims().mapTo(
            mutableSetOf(),
            SilverClaim::state,
        ))
    }

    @Test
    fun `another processor can add competing knowledge without replacing the first`() {
        val source = source()
        val first = replaceSilverGeneration(
            SilverDataset(),
            source,
            generation(source, processorId = "extractor-a", processorVersion = "1", value = "active"),
            resolver,
            100,
        ).dataset

        val competing = replaceSilverGeneration(
            first,
            source,
            generation(source, processorId = "extractor-b", processorVersion = "1", value = "paused"),
            resolver,
            200,
        )

        assertTrue(competing.supersededClaimIds.isEmpty())
        assertEquals(
            setOf("active", "paused"),
            competing.dataset.statusClaims().filter { it.state == SilverClaimState.ACTIVE }
                .mapTo(mutableSetOf()) { it.textValue() },
        )
    }

    @Test
    fun `independent Bronze sources retain each others Silver knowledge and provenance`() {
        val conversation = source(id = "conversation:1", hash = "a".repeat(64))
        val conversationGeneration = generation(
            conversation,
            processorVersion = "1",
            value = "conversation-value",
        )
        val afterConversation = replaceSilverGeneration(
            SilverDataset(),
            conversation,
            conversationGeneration,
            resolver,
            100,
        ).dataset
        val conversationEvidenceIds = afterConversation.evidence.mapTo(mutableSetOf(), SilverEvidence::id)
        val conversationObservationIds = afterConversation.observations.mapTo(mutableSetOf(), SilverObservation::id)
        val conversationClaimIds = afterConversation.claims.mapTo(mutableSetOf(), SilverClaim::id)

        val contacts = source(id = "contacts", hash = "b".repeat(64))
        val afterContacts = replaceSilverGeneration(
            afterConversation,
            contacts,
            generation(contacts, processorVersion = "1", value = "contacts-value"),
            resolver,
            200,
        ).dataset

        assertTrue(afterContacts.evidence.map(SilverEvidence::id).containsAll(conversationEvidenceIds))
        assertTrue(afterContacts.observations.map(SilverObservation::id).containsAll(conversationObservationIds))
        assertTrue(afterContacts.claims.map(SilverClaim::id).containsAll(conversationClaimIds))
        assertTrue(afterContacts.claims.filter { it.id in conversationClaimIds }.all {
            it.state == SilverClaimState.ACTIVE
        })
        val contactClaimIds = afterContacts.claims.mapTo(mutableSetOf(), SilverClaim::id) - conversationClaimIds

        val changedContacts = contacts.copy(contentSha256 = "c".repeat(64), text = "Contacts changed")
        val reprocessedContacts = replaceSilverGeneration(
            afterContacts,
            changedContacts,
            generation(changedContacts, processorVersion = "2", value = "updated-contacts-value"),
            resolver,
            300,
        )

        assertEquals(contactClaimIds, reprocessedContacts.supersededClaimIds)
        assertTrue(reprocessedContacts.dataset.evidence.map(SilverEvidence::id).containsAll(conversationEvidenceIds))
        assertTrue(
            reprocessedContacts.dataset.observations.map(SilverObservation::id)
                .containsAll(conversationObservationIds),
        )
        assertTrue(reprocessedContacts.dataset.claims.filter { it.id in conversationClaimIds }.all {
            it.state == SilverClaimState.ACTIVE
        })
        SilverData.version(reprocessedContacts.dataset)
    }

    @Test
    fun `new model replaces Claims from the same processor lineage`() {
        val source = source()
        val first = replaceSilverGeneration(
            SilverDataset(),
            source,
            generation(source, processorVersion = "1", value = "active", modelId = "model-4b"),
            resolver,
            100,
        ).dataset

        val replacement = replaceSilverGeneration(
            first,
            source,
            generation(source, processorVersion = "1", value = "paused", modelId = "model-9b"),
            resolver,
            200,
        )

        assertEquals(3, replacement.supersededClaimIds.size)
        assertEquals(
            setOf("model-4b", "model-9b"),
            replacement.dataset.observations.mapNotNullTo(mutableSetOf()) { it.producer.modelId },
        )
        assertEquals("paused", replacement.dataset.statusClaims().single {
            it.state == SilverClaimState.ACTIVE
        }.textValue())
    }

    @Test
    fun `empty completed replacement explicitly supersedes knowledge no longer produced`() {
        val source = source()
        val first = replaceSilverGeneration(
            SilverDataset(),
            source,
            generation(source, processorVersion = "1", value = "active"),
            resolver,
            100,
        ).dataset

        val empty = replaceSilverGeneration(
            first,
            source,
            generation(source, processorVersion = "2", value = null),
            resolver,
            200,
        )

        assertEquals(first.claims.mapTo(mutableSetOf(), SilverClaim::id), empty.supersededClaimIds)
        assertTrue(empty.dataset.claims.none { it.state == SilverClaimState.ACTIVE })
    }

    @Test
    fun `incomplete generation is rejected before the stored dataset can change`() {
        val source = source()
        val stored = replaceSilverGeneration(
            SilverDataset(),
            source,
            generation(source, processorVersion = "1", value = "active"),
            resolver,
            100,
        ).dataset
        val incomplete = generation(source, processorVersion = "2", value = "paused").let { generation ->
            generation.copy(observations = generation.observations.filterNot {
                it.kind == SILVER_EXTRACTION_COMPLETE_KIND
            })
        }

        val failure = runCatching { replaceSilverGeneration(stored, source, incomplete, resolver, 200) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertTrue(stored.claims.all { it.state == SilverClaimState.ACTIVE })
    }

    private fun generation(
        source: BronzeTextSource,
        processorId: String = SILVER_EXTRACTION_PROCESSOR_ID,
        processorVersion: String,
        value: String?,
        modelId: String = MODEL_ID,
    ): ExtractedSilver {
        val evidence = SilverEvidence.create(source.id, source.contentSha256)
        val producer = SilverProducer.create(processorId, processorVersion, modelId)
        val candidate = value?.let {
            SilverObservation.create(
                kind = SILVER_ATTRIBUTE_CANDIDATE_KIND,
                payload = SilverJsonObject(mapOf(
                    "subject" to SilverJsonObject(mapOf(
                        "name" to SilverJsonString("Source"),
                        "type" to SilverJsonString("project"),
                    )),
                    "predicate" to SilverJsonString("status"),
                    "value" to SilverJsonString(it),
                )),
                evidenceIds = listOf(evidence.id),
                confidence = .9,
                producer = producer,
                createdAtMillis = 50,
            )
        }
        val complete = SilverObservation.create(
            kind = SILVER_EXTRACTION_COMPLETE_KIND,
            payload = SilverJsonObject(emptyMap()),
            evidenceIds = listOf(evidence.id),
            producer = producer,
            createdAtMillis = 50,
        )
        return ExtractedSilver(
            evidence = listOf(evidence),
            observations = listOfNotNull(candidate, complete),
            model = AiModelMetadata(modelId, 4_000_000_000),
        )
    }

    private fun source(id: String = "source-1", hash: String = "a".repeat(64)) = BronzeTextSource(
        id = id,
        name = "notes.txt",
        sourceType = "file",
        contentSha256 = hash,
        text = "Source is active",
    )

    private fun SilverDataset.statusClaims() = claims.filter { it.predicate == "status" }

    private fun SilverClaim.textValue(): String = (value as SilverJsonString).value

    private companion object {
        const val MODEL_ID = "model-4b"
    }
}
