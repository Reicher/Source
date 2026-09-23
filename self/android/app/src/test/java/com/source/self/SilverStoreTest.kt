package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Test

class SilverStoreTest {
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
}
