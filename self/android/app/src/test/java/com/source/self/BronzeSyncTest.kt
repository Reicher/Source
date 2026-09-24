package com.source.self

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BronzeSyncTest {
    private fun item(revision: Long, acknowledged: Long) = BronzeItem(
        "id", revision, "hash", false, "note.txt", "text/plain", 4, 1, 1, acknowledged,
    )

    @Test fun backgroundWorkIsNeededOnlyForDurablePendingBronze() {
        assertFalse(hasPendingBronze(emptyList()))
        assertFalse(hasPendingBronze(listOf(item(2, 2))))
        assertTrue(hasPendingBronze(listOf(item(2, 1))))
    }
}
