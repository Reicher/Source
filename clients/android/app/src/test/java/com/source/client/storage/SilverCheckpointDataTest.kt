package com.source.client.storage

import org.junit.Test

class SilverCheckpointDataTest {
    @Test(expected = IllegalArgumentException::class)
    fun `checkpoint rejects duplicate completed batch indexes`() {
        val sourceId = "source-1"
        val sourceHash = "a".repeat(64)
        SilverRefinementCheckpoint(
            bronzeSourceId = sourceId,
            bronzeContentSha256 = sourceHash,
            processorVersion = 2,
            totalBatches = 2,
            completedBatches = listOf(
                SilverBatchCheckpoint(0, "b".repeat(64), result(sourceId, sourceHash, 100)),
                SilverBatchCheckpoint(0, "b".repeat(64), result(sourceId, sourceHash, 101)),
            ),
        )
    }

    private fun result(sourceId: String, sourceHash: String, processedAt: Long) = SilverResult(
        bronzeSourceId = sourceId,
        bronzeContentSha256 = sourceHash,
        entities = emptyList(),
        claims = emptyList(),
        modelId = "model-4b",
        parameterCount = 4_000_000_000,
        processorVersion = 2,
        processedAtMillis = processedAt,
    )
}
