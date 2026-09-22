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
}
