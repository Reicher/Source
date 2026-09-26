package com.source.self

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePresentationRefreshTest {
    @Test fun repeatedSuccessfulHeartbeatDoesNotRequireAUiRebuild() {
        assertFalse(sourcePresentationChanged(true, null, true, null))
    }

    @Test fun connectionOrErrorChangesRequireAUiRebuild() {
        assertTrue(sourcePresentationChanged(false, null, true, null))
        assertTrue(sourcePresentationChanged(true, null, true, "Sync failed"))
    }
}
