package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverStoreTest {
    @Test fun statusRefreshesJobsWithoutRequestingUnchangedSilver() {
        val snapshot = SilverSnapshot(
            7, emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList(),
            SourceJobs(11, emptyList(), emptyList()),
        )
        val nextJob = SourceJob("job", "sync", "note.txt", "to_source", "queued", 1, null)
        val jobs = SourceJobs(12, listOf(nextJob), emptyList())
        val status = SourceStatus("person", 7, 12, jobs, emptyList())

        assertFalse(snapshot.needsSilverSnapshot(status))
        assertEquals(jobs, snapshot.withStatus(status).jobs)
        assertTrue(snapshot.needsSilverSnapshot(SourceStatus("person", 8, 12, jobs, emptyList())))
        assertTrue(snapshot.needsSilverSnapshot(SourceStatus("person")))
    }

    @Test fun lightweightStatusRoundTripsForRestartPersistence() {
        val queued = SourceJob("queued", "sync", "note.txt", "to_source", "queued", 1, null)
        val completed = SourceJob("done", "silver_extraction", "photo.jpg", null, "completed", 2, 3)
        val status = SourceStatus(
            "person", 7, 12, SourceJobs(12, listOf(queued), listOf(completed), 1, 9),
            listOf(SilverProcessing("bronze", "processing", 2, 4)),
        )

        val restored = sourceStatusFromPersistenceJson(status.persistenceJson())

        assertEquals(status.copy(personId = ""), restored)
    }

    @Test fun sourceKnowledgeAndEntityProvenanceRemainNavigable() {
        val entity = SilverEntity("entity-1")
        val evidence = listOf(
            SilverEvidence("evidence-1", "bronze-1", "hash-1", null, "Ada Lovelace wrote this"),
            SilverEvidence("evidence-2", "bronze-2", "hash-2", null, "Ada Lovelace reviewed this"),
        )
        val observations = listOf(
            SilverObservation("observation-1", "entity-candidate", "Ada Lovelace", listOf("evidence-1"), "source.silver.semantic-model", "1"),
            SilverObservation("observation-2", "entity-candidate", "Ada Lovelace", listOf("evidence-2"), "source.silver.semantic-model", "1"),
        )
        val claims = listOf(
            SilverClaim("claim-1", entity.id, "name", "Ada Lovelace", null, listOf("observation-1"), "active"),
            SilverClaim("claim-2", entity.id, "name", "Ada Lovelace", null, listOf("observation-2"), "active"),
        )
        val sources = listOf(
            SilverSource("bronze-1", "hash-1", "first.txt", "text/plain", listOf("evidence-1"), listOf("observation-1"), listOf(entity.id), listOf("claim-1")),
            SilverSource("bronze-2", "hash-2", "second.txt", "text/plain", listOf("evidence-2"), listOf("observation-2"), listOf(entity.id), listOf("claim-2")),
        )
        val snapshot = SilverSnapshot(1, sources, evidence, observations, listOf(entity), claims, emptyList())

        assertEquals(listOf("observation-1"), snapshot.forBronze("bronze-1").observations.map { it.id })
        assertEquals("Ada Lovelace", snapshot.label(entity))
        assertEquals(listOf("bronze-1", "bronze-2"), snapshot.supportingBronze(entity.id))
    }

    @Test fun relationshipDescriptionsPreserveDirectionFromEitherEntity() {
        val martin = SilverEntity("martin")
        val josefin = SilverEntity("josefin")
        val relationship = SilverClaim(
            "relationship", martin.id, "sibling_of", null, josefin.id,
            emptyList(), "active",
        )
        val snapshot = SilverSnapshot(
            1, emptyList(), emptyList(), emptyList(), listOf(martin, josefin),
            listOf(
                SilverClaim("martin-name", martin.id, "name", "Martin", null, emptyList(), "active"),
                SilverClaim("josefin-name", josefin.id, "name", "Josefin", null, emptyList(), "active"),
                relationship,
            ),
            emptyList(),
        )

        assertEquals(listOf(relationship), snapshot.claimsFor(martin.id).filter { it.objectEntityId != null })
        assertEquals(listOf(relationship), snapshot.claimsFor(josefin.id).filter { it.objectEntityId != null })
        assertEquals("Martin — sibling of → Josefin", snapshot.describe(relationship))
    }

    @Test fun entityClaimsAreGroupedAndSortedByPrimaryObservationConfidence() {
        val robin = SilverEntity("robin")
        val observations = listOf(
            SilverObservation("name-low", "entity-candidate", "Robin", emptyList(), "model", "1", 0.84),
            SilverObservation("name-high", "entity-candidate", "Robin", emptyList(), "model", "1", 0.96),
            SilverObservation("role", "attribute-candidate", "Engineer", emptyList(), "model", "1", null),
            SilverObservation("secondary", "entity-candidate", "Robin", emptyList(), "model", "1", 0.99),
        )
        val claims = listOf(
            SilverClaim("name-1", robin.id, "name", "Robin", null, listOf("name-low"), "active"),
            SilverClaim("role", robin.id, "role", "Engineer", null, listOf("role", "secondary"), "active"),
            SilverClaim("name-2", robin.id, "name", "Robin", null, listOf("name-high"), "active"),
        )
        val snapshot = SilverSnapshot(
            1, emptyList(), emptyList(), observations, listOf(robin), claims, emptyList(),
        )

        val groups = snapshot.claimGroupsFor(robin.id)

        assertEquals(listOf("name", "role"), groups.map { it.predicate })
        assertEquals("Robin", groups.first().value)
        assertEquals(0.96, groups.first().confidence)
        assertEquals(listOf("name-2", "name-1"), groups.first().claims.map { it.id })
        assertEquals(null, groups.last().confidence)
    }

    @Test fun incomingRelationshipClaimKeepsItsDirectionInTheValueColumn() {
        val martin = SilverEntity("martin")
        val josefin = SilverEntity("josefin")
        val relationship = SilverClaim(
            "relationship", martin.id, "sibling_of", null, josefin.id,
            emptyList(), "active",
        )
        val snapshot = SilverSnapshot(
            1, emptyList(), emptyList(), emptyList(), listOf(martin, josefin),
            listOf(
                SilverClaim("martin-name", martin.id, "name", "Martin", null, emptyList(), "active"),
                SilverClaim("josefin-name", josefin.id, "name", "Josefin", null, emptyList(), "active"),
                relationship,
            ),
            emptyList(),
        )

        assertEquals(
            "Martin → Josefin",
            snapshot.claimGroupsFor(josefin.id).first { it.predicate == "sibling_of" }.value,
        )
    }
}
