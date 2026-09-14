package com.source.client.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SilverIdentityTest {
    @Test
    fun `canonical JSON follows the RFC 8785 number and property ordering vector`() {
        val value = SilverJsonObject(linkedMapOf(
            "numbers" to SilverJsonArray(listOf(
                SilverJsonNumber(333333333.33333329),
                SilverJsonNumber(1E30),
                SilverJsonNumber(4.50),
                SilverJsonNumber(2e-3),
                SilverJsonNumber(1e-27),
            )),
            "string" to SilverJsonString("€$\u000f\nA'B\"\\\"/"),
            "literals" to SilverJsonArray(listOf(SilverJsonNull, SilverJsonBoolean(true), SilverJsonBoolean(false))),
        ))

        assertEquals(
            "{\"literals\":[null,true,false],\"numbers\":[333333333.3333333,1e+30,4.5,0.002,1e-27]," +
                "\"string\":\"€$\\u000f\\nA'B\\\"\\\\\\\"/\"}",
            canonicalSilverJson(value).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `Evidence and Observation match independent golden identifiers`() {
        val evidence = testEvidence()
        val observation = testObservation(evidence)

        assertEquals("d0c03ad63473f67347ada7d4272bb1f3c7653f3d091cef9db1d135903019efc8", evidence.id)
        assertEquals("ce1af424ebe3775da4e4081340d3c84cf2addd6c103edfd4efb0d5fa3b738117", observation.id)
    }

    @Test
    fun `Claim identity input matches the shared cross-runtime golden vector`() {
        val observation = testObservation()
        val producer = observation.producer
        val identity = SilverJsonObject(mapOf(
            "confidence" to SilverJsonNumber(.8),
            "object" to SilverJsonObject(mapOf(
                "scalar" to SilverJsonObject(mapOf(
                    "type" to SilverJsonString("text"),
                    "value" to SilverJsonString("active"),
                )),
            )),
            "predicate" to SilverJsonString("status"),
            "producer" to producerIdentity(producer),
            "subjectEntityId" to SilverJsonString("00000000-0000-4000-8000-000000000001"),
            "supportingObservationIds" to SilverJsonArray(listOf(SilverJsonString(observation.id))),
        ))

        assertEquals(
            "bf0ba2f719f966455d34939402f51d36221360856136f7d01aa5f98d45a70bf7",
            silverRecordId(SILVER_CLAIM_ID_PREFIX, identity),
        )
    }

    @Test
    fun `NFC and set-valued Evidence order cannot change Observation identity`() {
        val firstEvidence = testEvidence("café")
        val secondEvidence = testEvidence("source-2", "b".repeat(64))
        val producer = SilverProducer.create("processor", "1")
        val first = SilverObservation.create(
            kind = "résumé",
            payload = SilverJsonObject(linkedMapOf(
                "b" to SilverJsonNumber(1.0),
                "a" to SilverJsonString("café"),
                "zero" to SilverJsonNumber(-0.0),
            )),
            evidenceIds = listOf(secondEvidence.id, firstEvidence.id, secondEvidence.id),
            producer = producer,
            createdAtMillis = 100,
        )
        val second = SilverObservation.create(
            kind = "re\u0301sume\u0301",
            payload = SilverJsonObject(linkedMapOf(
                "a" to SilverJsonString("cafe\u0301"),
                "b" to SilverJsonNumber(1.0),
                "zero" to SilverJsonNumber(0.0),
            )),
            evidenceIds = listOf(firstEvidence.id, secondEvidence.id),
            producer = producer,
            createdAtMillis = 200,
        )

        assertEquals(first.id, second.id)
        assertArrayEquals(first.evidenceIds.toTypedArray(), second.evidenceIds.toTypedArray())
    }

    @Test
    fun `source fragments and producer versions create new identities`() {
        val wholeSource = testEvidence()
        val fragment = SilverEvidence.create(
            "source-1",
            "a".repeat(64),
            SilverJsonObject(mapOf(
                "kind" to SilverJsonString("utf8-text-range"),
                "start" to SilverJsonNumber(0.0),
                "end" to SilverJsonNumber(12.0),
            )),
        )

        assertNotEquals(wholeSource.id, fragment.id)
        assertNotEquals(
            testObservation(wholeSource, processorVersion = "1").id,
            testObservation(wholeSource, processorVersion = "2").id,
        )
        assertNotEquals(
            SilverObservation.create(
                "candidate",
                SilverJsonObject(emptyMap()),
                listOf(wholeSource.id),
                null,
                SilverProducer.create("processor", "1"),
                100,
            ).id,
            SilverObservation.create(
                "candidate",
                SilverJsonObject(emptyMap()),
                listOf(wholeSource.id),
                .8,
                SilverProducer.create("processor", "1"),
                100,
            ).id,
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `properties colliding after NFC normalization are rejected`() {
        canonicalSilverJson(SilverJsonObject(mapOf(
            "café" to SilverJsonBoolean(true),
            "cafe\u0301" to SilverJsonBoolean(false),
        )))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `invalid Unicode is rejected`() {
        canonicalSilverJson(SilverJsonString("\ud800"))
    }
}
