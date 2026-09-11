package com.source.client.ui

import com.source.client.knowledge.entityId
import com.source.client.storage.SilverClaim
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEntity
import com.source.client.storage.SilverResult
import com.source.client.storage.SilverScalarValue
import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeScreenTest {
    @Test
    fun `same deterministic entity aggregates claims and supporting Bronze sources`() {
        val sourceEntity = SilverEntity(entityId("project", "Source"), "Source", "project")
        val first = result(
            "bronze-1",
            sourceEntity,
            SilverClaim(sourceEntity.id, "status", value = SilverScalarValue.Text("active"), confidence = .8,
                bronzeSourceId = "bronze-1", evidenceExcerpt = "Source is active"),
        )
        val second = result(
            "bronze-2",
            sourceEntity,
            SilverClaim(sourceEntity.id, "status", value = SilverScalarValue.Text("active"), confidence = .9,
                bronzeSourceId = "bronze-2", evidenceExcerpt = "The Source project is active"),
        )
        val library = LibraryUiState(
            items = listOf(
                item("bronze-1", "one.txt"),
                item("bronze-2", "two.txt"),
            ),
        )

        val entity = buildKnowledgeUiState(SilverDataset(listOf(first, second), 2), library).entities.single()

        assertEquals("Source", entity.name)
        assertEquals(1, entity.claims.size)
        assertEquals(.9, entity.claims.single().confidence, 0.0)
        assertEquals(setOf("one.txt", "two.txt"), entity.claims.single().sources.map { it.name }.toSet())
    }

    private fun result(source: String, entity: SilverEntity, claim: SilverClaim) = SilverResult(
        bronzeSourceId = source,
        bronzeContentSha256 = source.last().toString().repeat(64),
        entities = listOf(entity),
        claims = listOf(claim),
        modelId = "model",
        parameterCount = 4_000_000_000,
        processorVersion = 1,
        processedAtMillis = 1,
    )

    private fun item(id: String, name: String) = LibraryUiItem(
        id, name, "file", "text/plain", 1, 1,
        syncState = LibrarySyncState.LOCAL_AND_SYNCED,
        localAvailable = true,
        nodeAvailable = true,
        canRemoveFromDevice = true,
        canDeleteFromSource = true,
        previewKind = LibraryPreviewKind.TEXT,
    )
}
