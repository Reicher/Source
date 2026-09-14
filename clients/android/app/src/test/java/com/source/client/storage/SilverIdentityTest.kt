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

        assertEquals("1002b34a04fa56b0f2eb4da494a67006165ac901045fe9aeb183603c348f28ef", evidence.id)
        assertEquals("6ab9dcd554dd8a3a54597903197a027ff8b17a8c983415d0da8eede21f04b4ed", observation.id)
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
            "093133d5734db6faa20b22e2208f23f6f2f0ef47fa4ef2b62e883e635653b827",
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
