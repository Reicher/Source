package com.source.client.ui

import com.source.client.storage.SilverDataset
import com.source.client.storage.testEvidence
import com.source.client.storage.testObservation
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeScreenTest {
    @Test
    fun `unresolved observations do not become global knowledge`() {
        val evidence = testEvidence()
        val silver = SilverDataset(listOf(evidence), listOf(testObservation(evidence)), 100)

        assertEquals(KnowledgeUiState(), buildKnowledgeUiState(silver, LibraryUiState()))
    }
}
