package com.source.client.ui

import com.source.client.model.ChatConversation
import com.source.client.model.ChatConversations
import com.source.client.model.ChatMessage
import com.source.client.knowledge.SILVER_EXTRACTION_COMPLETE_KIND
import com.source.client.protocol.SilverRefinementJob
import com.source.client.storage.SilverDataset
import com.source.client.storage.SilverEvidence
import com.source.client.storage.SilverJsonObject
import com.source.client.storage.SilverObservation
import com.source.client.storage.SilverProducer
import com.source.client.storage.LibraryItem
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.UUID

class LibraryScreenTest {
    @Test
    fun `file sizes use compact binary units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("10 MB", formatBytes(10 * 1024 * 1024))
    }

    @Test
    fun `conversation filename is stable and human readable`() {
        val createdAt = Instant.parse("2026-09-11T10:32:00Z").toEpochMilli()

        assertEquals("conversation-2026-09-11-1032.json", conversationFilename(createdAt))
    }

    @Test
    fun `persisted conversations are composed with files newest first`() {
        val file = LibraryItem(
            id = UUID.randomUUID().toString(),
            name = "archive.pdf",
            mimeType = "application/pdf",
            byteCount = 100,
            createdAtMillis = 200,
            contentSha256 = "a".repeat(64),
        )
        val olderConversation = ChatConversation(
            id = UUID.randomUUID().toString(),
            createdAtMillis = 100,
            messages = listOf(ChatMessage.user("Old")),
        )
        val newerConversation = ChatConversation(
            id = UUID.randomUUID().toString(),
            createdAtMillis = 300,
            messages = listOf(ChatMessage.user("New"), ChatMessage.assistant("Reply")),
        )
        val raw = LibraryUiState(
            listOf(
                LibraryUiItem(
                    id = file.id,
                    filename = file.name,
                    sourceType = file.sourceType,
                    mimeType = file.mimeType,
                    byteCount = file.byteCount,
                    createdAtMillis = file.createdAtMillis,
                    bronzeContentSha256 = file.contentSha256,
                    bronzeStatus = BronzeNodeStatus.STORED,
                    localAvailable = true,
                    canRemoveFromDevice = true,
                    canDeleteFromSource = true,
                    previewKind = null,
                ),
            ),
        )

        val presented = withConversationLibraryItems(
            raw,
            ChatConversations(listOf(olderConversation, newerConversation), newerConversation.id),
            conversationByteCount = { 123L },
        )

        assertEquals(3, presented.items.size)
        assertEquals("conversation-1970-01-01-0000.json", presented.items[0].filename)
        assertEquals("archive.pdf", presented.items[1].filename)
        assertEquals(123L, presented.items[2].byteCount)
        assertEquals(BronzeNodeStatus.NOT_ON_NODE, presented.items[2].bronzeStatus)
        assertEquals(true, presented.items[2].canDeleteFromSource)
    }

    @Test
    fun `preview support is limited to simple local formats`() {
        assertEquals(LibraryPreviewKind.TEXT, previewKind("text/plain", "notes.txt"))
        assertEquals(LibraryPreviewKind.TEXT, previewKind("application/octet-stream", "data.json"))
        assertEquals(null, previewKind("image/jpeg", "photo.jpg"))
        assertEquals(null, previewKind("application/pdf", "document.pdf"))
    }

    @Test
    fun `Silver backup failure does not replace successful Bronze sync state`() {
        val item = LibraryUiItem(
            id = "source-1",
            filename = "notes.txt",
            sourceType = "file",
            mimeType = "text/plain",
            byteCount = 100,
            createdAtMillis = 100,
            bronzeContentSha256 = "a".repeat(64),
            bronzeStatus = BronzeNodeStatus.STORED,
            localAvailable = true,
            canRemoveFromDevice = true,
            canDeleteFromSource = true,
            previewKind = LibraryPreviewKind.TEXT,
        )

        val presented = withSilverState(
            LibraryUiState(listOf(item)),
            SilverUiState(syncErrors = setOf(item.id)),
        ).items.single()

        assertEquals(BronzeNodeStatus.STORED, presented.bronzeStatus)
        assertEquals(KnowledgeStatus.ERROR, presented.knowledgeStatus)
    }

    @Test
    fun `Silver batch progress is presented independently from the sync icon`() {
        val item = LibraryUiItem(
            id = "source-1",
            filename = "contacts.csv",
            sourceType = "file",
            mimeType = "text/csv",
            byteCount = 60_000,
            createdAtMillis = 100,
            bronzeContentSha256 = "b".repeat(64),
            bronzeStatus = BronzeNodeStatus.STORED,
            localAvailable = true,
            canRemoveFromDevice = true,
            canDeleteFromSource = true,
            previewKind = LibraryPreviewKind.TEXT,
        )

        val presented = withSilverState(
            LibraryUiState(listOf(item)),
            SilverUiState(
                jobs = mapOf(item.id to SilverRefinementJob(
                    id = "00000000-0000-4000-8000-000000000001",
                    sourceId = item.id,
                    sourceContentSha256 = item.bronzeContentSha256,
                    state = "running",
                    completedBatches = 7,
                    totalBatches = 25,
                )),
            ),
        )

        assertEquals(BronzeNodeStatus.STORED, presented.items.single().bronzeStatus)
        assertEquals(KnowledgeStatus.PROCESSING, presented.items.single().knowledgeStatus)
        assertEquals(SilverBatchProgress(7, 25), presented.items.single().knowledgeProgress)
    }

    @Test
    fun `Silver is current only for the current Bronze revision`() {
        val item = LibraryUiItem(
            id = "source-1",
            filename = "notes.txt",
            sourceType = "file",
            mimeType = "text/plain",
            byteCount = 100,
            createdAtMillis = 100,
            bronzeContentSha256 = "b".repeat(64),
            bronzeStatus = BronzeNodeStatus.STORED,
            localAvailable = true,
            canRemoveFromDevice = true,
            canDeleteFromSource = true,
            previewKind = LibraryPreviewKind.TEXT,
        )
        fun dataset(contentSha256: String): SilverDataset {
            val evidence = SilverEvidence.create(item.id, contentSha256)
            val complete = SilverObservation.create(
                kind = SILVER_EXTRACTION_COMPLETE_KIND,
                payload = SilverJsonObject(emptyMap()),
                evidenceIds = listOf(evidence.id),
                producer = SilverProducer.create("source.node.silver-extraction", "1"),
                createdAtMillis = 100,
            )
            return SilverDataset(evidence = listOf(evidence), observations = listOf(complete))
        }

        val stale = withSilverState(LibraryUiState(listOf(item)), SilverUiState(dataset("a".repeat(64))))
        val current = withSilverState(LibraryUiState(listOf(item)), SilverUiState(dataset(item.bronzeContentSha256)))

        assertEquals(KnowledgeStatus.WAITING, stale.items.single().knowledgeStatus)
        assertEquals(KnowledgeStatus.CURRENT, current.items.single().knowledgeStatus)
    }
}
