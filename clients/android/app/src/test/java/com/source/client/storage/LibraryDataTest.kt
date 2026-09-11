package com.source.client.storage

import java.util.UUID
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryDataTest {
    @Test
    fun `manifest version includes items and tombstones`() {
        val now = 1_700_000_000_000
        val item = LibraryItem(
            UUID.randomUUID().toString(), "notes.txt", mimeType = "text/plain",
            byteCount = 42, createdAtMillis = now, contentSha256 = "a".repeat(64),
        )
        val tombstone = LibraryTombstone(
            UUID.randomUUID().toString(), "b".repeat(64), now + 1,
        )
        val manifest = LibraryManifest(listOf(item), listOf(tombstone), now + 1)

        val version = LibraryData.version(manifest)
        assertTrue(version.contentIdentity.contains(item.id))
        assertTrue(version.contentIdentity.contains(tombstone.itemId))
    }

    @Test
    fun `manifest rejects duplicate content identities`() {
        val first = LibraryItem(
            UUID.randomUUID().toString(), "a", mimeType = "application/octet-stream",
            byteCount = 1, createdAtMillis = 1, contentSha256 = "c".repeat(64),
        )
        val second = first.copy(id = UUID.randomUUID().toString(), name = "b")
        assertTrue(runCatching {
            LibraryData.version(LibraryManifest(listOf(first, second), modifiedAtMillis = 1))
        }.isFailure)
    }

    @Test
    fun `acknowledged node copy changes manifest identity`() {
        val item = LibraryItem(
            UUID.randomUUID().toString(), "notes.txt", mimeType = "text/plain",
            byteCount = 1, createdAtMillis = 1, contentSha256 = "d".repeat(64),
        )
        assertNotEquals(
            LibraryData.version(LibraryManifest(listOf(item), modifiedAtMillis = 1)).contentIdentity,
            LibraryData.version(LibraryManifest(listOf(item.copy(nodeStored = true)), modifiedAtMillis = 2))
                .contentIdentity,
        )
    }
}
