package com.source.client.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SilverDataTest {
    @Test
    fun `Silver accepts only its current snapshot format`() {
        assertEquals(1, SilverData.descriptor.formatVersion)
        assertEquals(setOf(1), SilverData.supportedFormatVersions)
        assertEquals("silver-datasets", SilverData.descriptor.canonicalCollection)
        assertEquals(SourceDataAuthority.NODE, SilverData.descriptor.authority)
    }

    @Test
    fun `merge unions observations and deduplicates identical processing`() {
        val evidence = testEvidence()
        val early = testObservation(evidence, createdAtMillis = 90)
        val repeated = testObservation(evidence, createdAtMillis = 100)
        val otherEvidence = testEvidence("source-2", "b".repeat(64))
        val other = testObservation(otherEvidence, createdAtMillis = 80)
        val local = SilverDataset(listOf(evidence), listOf(repeated), 100)
        val remote = SilverDataset(listOf(evidence, otherEvidence), listOf(early, other), 101)

        val merged = SilverData.merge(local, remote)

        assertEquals(setOf(evidence, otherEvidence), merged.evidence.toSet())
        assertEquals(setOf(early, other), merged.observations.toSet())
        assertSame(remote, merged)
    }

    @Test
    fun `new processor versions remain traceable beside old observations`() {
        val evidence = testEvidence()
        val old = testObservation(evidence, processorVersion = "1")
        val fresh = testObservation(evidence, processorVersion = "2")

        val merged = SilverData.merge(
            SilverDataset(listOf(evidence), listOf(old), 100),
            SilverDataset(listOf(evidence), listOf(fresh), 101),
        )

        assertNotEquals(old.id, fresh.id)
        assertEquals(setOf(old, fresh), merged.observations.toSet())
    }

    @Test
    fun `new Bronze revisions retain observations from the old revision`() {
        val oldEvidence = testEvidence("source-1", "a".repeat(64))
        val newEvidence = testEvidence("source-1", "b".repeat(64))
        val old = testObservation(oldEvidence, createdAtMillis = 100)
        val fresh = testObservation(newEvidence, createdAtMillis = 200)

        val merged = SilverData.merge(
            SilverDataset(listOf(oldEvidence), listOf(old), 100),
            SilverDataset(listOf(newEvidence), listOf(fresh), 200),
        )

        assertEquals(setOf(oldEvidence, newEvidence), merged.evidence.toSet())
        assertEquals(setOf(old, fresh), merged.observations.toSet())
    }

    @Test
    fun `merge keeps a deletion marker newer than an observation`() {
        val evidence = testEvidence()
        val observation = testObservation(evidence, createdAtMillis = 100)
        val removed = SilverDataset(modifiedAtMillis = 120, removedSourceIds = mapOf("source-1" to 120))
        val staleRemote = SilverDataset(listOf(evidence), listOf(observation), 100)

        val merged = SilverData.merge(removed, staleRemote)

        assertSame(removed, merged)
        assertEquals(emptyList<SilverEvidence>(), merged.evidence)
        assertEquals(emptyList<SilverObservation>(), merged.observations)
    }

    @Test
    fun `a new observation supersedes an old deletion marker`() {
        val evidence = testEvidence()
        val observation = testObservation(evidence, createdAtMillis = 120)
        val removed = SilverDataset(modifiedAtMillis = 100, removedSourceIds = mapOf("source-1" to 100))
        val reprocessed = SilverDataset(listOf(evidence), listOf(observation), 120)

        assertSame(reprocessed, SilverData.merge(removed, reprocessed))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dataset rejects observations without their Evidence`() {
        SilverData.encode(SilverDataset(observations = listOf(testObservation())))
    }
}
