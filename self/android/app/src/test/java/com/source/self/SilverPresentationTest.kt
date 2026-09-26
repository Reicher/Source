package com.source.self

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SilverPresentationTest {
    @Test fun unresolvedCandidateIsPresentedWithoutInternalMetadata() {
        val evidence = SilverEvidence(
            "evidence-guid", "bronze-guid", "hash", null, "Alex may be a project name",
        )
        val parsing = SilverObservation(
            "parsing-guid", "text-block", JSONObject().put("text", evidence.excerpt),
            listOf(evidence.id), "parser-internal", "3",
        )
        val candidate = SilverObservation(
            "candidate-guid", "entity-candidate",
            JSONObject().put("ref", "e1").put("label", "Alex").put("type", "person"),
            listOf(evidence.id), "processor-internal", "7", 0.40, "model-internal", "build-internal",
        )
        val knowledge = SilverKnowledge(
            null, listOf(evidence), listOf(parsing, candidate), emptyList(), emptyList(), null,
        )

        val finding = knowledge.unresolvedFindings().single()

        assertEquals("Possible person", finding.predicate)
        assertEquals("Alex", finding.value)
        assertEquals(0.40, finding.confidence)
        assertEquals("Alex may be a project name", finding.sourceExcerpt)
        val visible = listOf(finding.predicate, finding.value, finding.sourceExcerpt).joinToString(" ")
        assertFalse(visible.contains("guid"))
        assertFalse(visible.contains("internal"))
    }

    @Test fun observationsAlreadyRepresentedByClaimsAreNotDuplicated() {
        val observation = SilverObservation(
            "candidate", "entity-candidate",
            JSONObject().put("ref", "e1").put("label", "Alex").put("type", "person"),
            emptyList(), "processor", "1", 0.99,
        )
        val claim = SilverClaim(
            "claim", "entity", "name", "Alex", null, listOf(observation.id), "active",
        )
        val knowledge = SilverKnowledge(
            null, emptyList(), listOf(observation), listOf(SilverEntity("entity")), listOf(claim), null,
        )

        assertTrue(knowledge.unresolvedFindings().isEmpty())
    }

    @Test fun unresolvedRelationshipsUseCandidateLabelsInsteadOfReferences() {
        fun entity(id: String, label: String) = SilverObservation(
            "$id-observation", "entity-candidate",
            JSONObject().put("ref", id).put("label", label).put("type", "person"),
            emptyList(), "processor", "1", 0.40,
        )
        val relationship = SilverObservation(
            "relationship", "relationship-candidate",
            JSONObject().put("subject_ref", "e1").put("predicate", "works_with").put("object_ref", "e2"),
            emptyList(), "processor", "1", 0.45,
        )
        val knowledge = SilverKnowledge(
            null, emptyList(), listOf(entity("e1", "Alex"), entity("e2", "Sam"), relationship),
            emptyList(), emptyList(), null,
        )

        val finding = knowledge.unresolvedFindings().first { it.predicate == "works with" }

        assertEquals("Alex → Sam", finding.value)
    }
}
