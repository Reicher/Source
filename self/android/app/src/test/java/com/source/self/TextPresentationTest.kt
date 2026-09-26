package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Test

class TextPresentationTest {
    @Test fun excerptUsesAtMostFifteenWords() {
        assertEquals(
            "one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen…",
            excerptWords("one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen"),
        )
    }

    @Test fun excerptNormalizesWhitespaceAndDoesNotAddFalseEllipsis() {
        assertEquals("one two three", excerptWords("  one\n two\tthree  "))
        assertEquals("", excerptWords(" \n "))
    }

    @Test fun imageSamplingNeverDecodesLargerThanTheDisplayTarget() {
        assertEquals(1, imageSampleSize(1080, 2340, 2340))
        assertEquals(2, imageSampleSize(4097, 3000, 4096))
        assertEquals(4, imageSampleSize(8000, 6000, 2340))
    }

    @Test fun wideImagesAreTopAlignedAndFillTheViewportWidth() {
        val layout = topCenteredImageLayout(4000, 2000, 1000, 1500)

        assertEquals(0.25f, layout.scale, 0.001f)
        assertEquals(0f, layout.left, 0.001f)
        assertEquals(0f, layout.top, 0.001f)
        assertEquals(1000f, layout.right, 0.001f)
        assertEquals(500f, layout.bottom, 0.001f)
    }

    @Test fun narrowImagesRemainHorizontallyCenteredWhileTopAligned() {
        val layout = topCenteredImageLayout(1000, 4000, 1000, 1500)

        assertEquals(0.375f, layout.scale, 0.001f)
        assertEquals(312.5f, layout.left, 0.001f)
        assertEquals(0f, layout.top, 0.001f)
        assertEquals(687.5f, layout.right, 0.001f)
        assertEquals(1500f, layout.bottom, 0.001f)
    }
}
