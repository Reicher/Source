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
}
