package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniSearchTest {
    @Test fun relevanceRanksBeforeTier() {
        val results = searchOmniBox("Josefin", listOf(
            OmniSearchCandidate(OmniResultTier.GOLD, "gold", "Notes about Josefin"),
            OmniSearchCandidate(OmniResultTier.SILVER, "silver", "Josefin Broström"),
            OmniSearchCandidate(OmniResultTier.BRONZE, "bronze", "Josefin"),
        ))

        assertEquals(listOf("bronze", "silver", "gold"), results.map { it.id })
    }

    @Test fun tierBreaksEqualRelevanceTies() {
        val results = searchOmniBox("Josefin", listOf(
            OmniSearchCandidate(OmniResultTier.BRONZE, "bronze", "Josefin note"),
            OmniSearchCandidate(OmniResultTier.GOLD, "gold", "Josefin file"),
            OmniSearchCandidate(OmniResultTier.SILVER, "silver", "Josefin item"),
        ))

        assertEquals(listOf("gold", "silver", "bronze"), results.map { it.id })
    }

    @Test fun searchesAdditionalEntityTermsAndNormalizesAccents() {
        val results = searchOmniBox("brostrom", listOf(
            OmniSearchCandidate(
                OmniResultTier.SILVER,
                "josefin",
                "Josefin Broström",
                listOf("Josefin Broström", "designer"),
            ),
        ))

        assertEquals("josefin", results.single().id)
    }

    @Test fun emptyQueryOrMissingTierProducesNoSyntheticResults() {
        assertTrue(searchOmniBox("", listOf(
            OmniSearchCandidate(OmniResultTier.SILVER, "silver", "Josefin"),
        )).isEmpty())
        assertTrue(searchOmniBox("Josefin", emptyList()).isEmpty())
    }

    @Test fun silverCandidatesIndexClaimsAndBothRelationshipEndpoints() {
        val martin = SilverEntity("martin")
        val josefin = SilverEntity("josefin")
        val snapshot = SilverSnapshot(
            1,
            emptyList(),
            emptyList(),
            emptyList(),
            listOf(martin, josefin),
            listOf(
                SilverClaim("martin-name", martin.id, "name", "Martin", null, emptyList(), "active"),
                SilverClaim("josefin-name", josefin.id, "name", "Josefin", null, emptyList(), "active"),
                SilverClaim("role", josefin.id, "role", "Designer", null, emptyList(), "active"),
                SilverClaim("relation", martin.id, "works_with", null, josefin.id, emptyList(), "active"),
            ),
            emptyList(),
        )
        val candidates = buildSilverOmniCandidates(snapshot)

        assertEquals("josefin", searchOmniBox("designer", candidates).single().id)
        assertEquals(
            setOf("martin", "josefin"),
            searchOmniBox("Martin works with Josefin", candidates).map { it.id }.toSet(),
        )
    }

    @Test fun unnamedSilverCandidateDoesNotExposeItsId() {
        val snapshot = SilverSnapshot(
            1, emptyList(), emptyList(), emptyList(),
            listOf(SilverEntity("75231476-5e20-4a02-8b16-deadbeefcafe")),
            emptyList(), emptyList(),
        )

        assertEquals("Unnamed entity", buildSilverOmniCandidates(snapshot).single().title)
    }
}
