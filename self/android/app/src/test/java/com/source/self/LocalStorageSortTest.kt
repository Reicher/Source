package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalStorageSortTest {
    private fun item(id: String, title: String, modified: Long) = BronzeItem(
        id, 1, "hash-$id", false, title, "text/plain", 1, 1, modified,
    )

    @Test fun nameSortIsTheDefaultFriendlyAscendingOrder() {
        val items = listOf(
            item("z", "zebra.txt", 30),
            item("a", "Alpha.txt", 20),
            item("b", "beta.txt", 10),
        )

        assertEquals(
            listOf("Alpha.txt", "beta.txt", "zebra.txt"),
            sortLocalStorageItems(items, LocalStorageSort.NAME).map { it.title },
        )
    }

    @Test fun modifiedSortShowsNewestFilesFirstAndUsesNameForTies() {
        val items = listOf(
            item("b", "beta.txt", 20),
            item("o", "old.txt", 10),
            item("a", "Alpha.txt", 20),
        )

        assertEquals(
            listOf("Alpha.txt", "beta.txt", "old.txt"),
            sortLocalStorageItems(items, LocalStorageSort.MODIFIED).map { it.title },
        )
    }
}
