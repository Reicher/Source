package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionPresentationTest {
    @Test fun presentsConnectionAgesAtUsefulGranularity() {
        assertEquals("less than a minute", connectionDurationPresentation(30_000))
        assertEquals("1 minute", connectionDurationPresentation(60_000))
        assertEquals("42 minutes", connectionDurationPresentation(42 * 60_000L))
        assertEquals("2 hours 5 min", connectionDurationPresentation((2 * 60 + 5) * 60_000L))
        assertEquals("1 day 3 h", connectionDurationPresentation(27 * 60 * 60_000L))
    }
}
