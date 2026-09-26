package com.source.self

import java.util.Locale

enum class LocalStorageSort(val label: String) {
    NAME("Name"), MODIFIED("Modified"),
}

internal fun sortLocalStorageItems(items: List<BronzeItem>, sort: LocalStorageSort): List<BronzeItem> =
    when (sort) {
        LocalStorageSort.NAME -> items.sortedWith(
            compareBy<BronzeItem> { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.title }
                .thenBy { it.id },
        )
        LocalStorageSort.MODIFIED -> items.sortedWith(
            compareByDescending<BronzeItem> { it.modified }
                .thenBy { it.title.lowercase(Locale.ROOT) }
                .thenBy { it.id },
        )
    }
