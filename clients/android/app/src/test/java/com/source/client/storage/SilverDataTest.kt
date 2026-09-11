package com.source.client.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SilverDataTest {
    @Test
    fun `merge keeps the better result for each Bronze source`() {
        val low = result("source-1", "a", 4_000_000_000, 1, 100)
        val high = result("source-1", "a", 9_000_000_000, 1, 90)
        val other = result("source-2", "b", 4_000_000_000, 1, 80)
        val local = SilverDataset(listOf(low, other), 100)
        val remote = SilverDataset(listOf(high), 101)

        val merged = SilverData.merge(local, remote)

        assertEquals(setOf(high, other), merged.results.toSet())
        assertEquals(102, merged.modifiedAtMillis)
    }

    @Test
    fun `merge returns an existing dataset when it already contains all better results`() {
        val high = result("source-1", "a", 9_000_000_000, 1, 100)
        val low = result("source-1", "a", 4_000_000_000, 1, 110)
        val local = SilverDataset(listOf(high), 100)

        assertSame(local, SilverData.merge(local, SilverDataset(listOf(low), 110)))
    }

    @Test
    fun `merge keeps a deletion marker newer than a remote result`() {
        val removed = SilverDataset(
            results = emptyList(),
            modifiedAtMillis = 120,
            removedSourceIds = mapOf("source-1" to 120),
        )
        val staleRemote = SilverDataset(listOf(result("source-1", "a", 9_000_000_000, 1, 100)), 100)

        val merged = SilverData.merge(removed, staleRemote)

        assertSame(removed, merged)
        assertEquals(emptyList<SilverResult>(), merged.results)
        assertEquals(mapOf("source-1" to 120L), merged.removedSourceIds)
    }

    @Test
    fun `merge allows a newly processed result to supersede an old deletion marker`() {
        val removed = SilverDataset(
            results = emptyList(),
            modifiedAtMillis = 100,
            removedSourceIds = mapOf("source-1" to 100),
        )
        val reprocessed = result("source-1", "b", 4_000_000_000, 1, 120)
        val remote = SilverDataset(listOf(reprocessed), 120)

        assertSame(remote, SilverData.merge(removed, remote))
    }

    private fun result(source: String, hashCharacter: String, parameters: Long, version: Int, time: Long) = SilverResult(
        bronzeSourceId = source,
        bronzeContentSha256 = hashCharacter.repeat(64),
        entities = emptyList(),
        claims = emptyList(),
        modelId = "model-$parameters",
        parameterCount = parameters,
        processorVersion = version,
        processedAtMillis = time,
    )
}
