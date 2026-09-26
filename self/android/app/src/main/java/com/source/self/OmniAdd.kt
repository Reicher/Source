package com.source.self

internal class OmniAddProgress {
    private var noteAdded = false
    private val addedAttachmentIndexes = mutableSetOf<Int>()

    fun markNoteAdded() {
        noteAdded = true
    }

    fun markAttachmentAdded(index: Int) {
        addedAttachmentIndexes += index
    }

    fun remainingText(submittedText: String): String = if (noteAdded) "" else submittedText

    fun <T> addedAttachments(submitted: List<T>): List<T> = submitted.filterIndexed { index, _ ->
        index in addedAttachmentIndexes
    }

    fun <T> remainingAttachments(submitted: List<T>): List<T> = submitted.filterIndexed { index, _ ->
        index !in addedAttachmentIndexes
    }
}
