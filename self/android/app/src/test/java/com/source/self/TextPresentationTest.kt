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
}
