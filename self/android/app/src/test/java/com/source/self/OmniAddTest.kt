package com.source.self

import org.junit.Assert.assertEquals
import org.junit.Test

class OmniAddTest {
    @Test fun partialSuccessLeavesOnlyUnprocessedInputPending() {
        val progress = OmniAddProgress()
        val attachments = listOf("first", "failed", "later")

        progress.markNoteAdded()
        progress.markAttachmentAdded(0)

        assertEquals("", progress.remainingText("note"))
        assertEquals(listOf("first"), progress.addedAttachments(attachments))
        assertEquals(listOf("failed", "later"), progress.remainingAttachments(attachments))
    }

    @Test fun failureBeforeWritingPreservesTheWholeSubmission() {
        val progress = OmniAddProgress()
        val attachments = listOf("first", "second")

        assertEquals("note", progress.remainingText("note"))
        assertEquals(emptyList<String>(), progress.addedAttachments(attachments))
        assertEquals(attachments, progress.remainingAttachments(attachments))
    }
}
