package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPackingTest {
    @Test fun compactNotesStackBesideATallImage() {
        val placements = packDesktopItems(
            listOf(
                DesktopPackSize(100, 300),
                DesktopPackSize(100, 60),
                DesktopPackSize(100, 60),
                DesktopPackSize(100, 60),
            ),
            maxWidth = 220,
            gap = 10,
        )

        assertEquals(DesktopPackPlacement(0, 0, 100, 300), placements[0])
        assertEquals(listOf(110, 110, 110), placements.drop(1).map { it.x })
        assertEquals(listOf(0, 70, 140), placements.drop(1).map { it.y })
        assertTrue(placements.drop(1).all { it.bottom <= placements.first().bottom })
    }

    @Test fun packedItemsStayWithinBoundsAndNeverOverlap() {
        val gap = 6
        val placements = packDesktopItems(
            listOf(
                DesktopPackSize(120, 260),
                DesktopPackSize(90, 50),
                DesktopPackSize(75, 80),
                DesktopPackSize(150, 60),
                DesktopPackSize(110, 190),
            ),
            maxWidth = 260,
            gap = gap,
        )

        assertTrue(placements.all { it.x >= 0 && it.right <= 260 && it.y >= 0 })
        placements.indices.forEach { first ->
            ((first + 1) until placements.size).forEach { second ->
                val a = placements[first]
                val b = placements[second]
                val overlap = a.x < b.right + gap && a.right + gap > b.x &&
                    a.y < b.bottom + gap && a.bottom + gap > b.y
                assertFalse("Items $first and $second overlap", overlap)
            }
        }
    }
}
