package com.source.client.storage

import org.junit.Test

class SilverCheckpointDataTest {
    @Test(expected = IllegalArgumentException::class)
    fun `checkpoint rejects duplicate completed batch indexes`() {
        SilverRefinementCheckpoint(
            bronzeSourceId = "source-1",
            bronzeContentSha256 = "a".repeat(64),
            processorVersion = "2",
            totalBatches = 2,
            completedBatches = listOf(
                SilverBatchCheckpoint(0, "b".repeat(64), testBatchResult()),
                SilverBatchCheckpoint(0, "b".repeat(64), testBatchResult()),
            ),
        )
    }
}
