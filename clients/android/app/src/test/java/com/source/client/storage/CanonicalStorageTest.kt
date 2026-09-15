package com.source.client.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class CanonicalStorageTest {
    @Test
    fun `revision identity matches the Node contract vector`() {
        val revision = StorageRevision(
            revisionId = "",
            objectKey = StorageObjectKey(
                profileId = "11111111-1111-4111-8111-111111111111",
                collection = "conversations",
                objectId = "22222222-2222-4222-8222-222222222222",
            ),
            kind = "content",
            parentRevisionIds = listOf("a".repeat(64)),
            payload = StoragePayloadDescriptor(
                format = "source-client-conversation",
                formatVersion = 3,
                byteCount = 123,
                plaintextSha256 = "b".repeat(64),
            ),
            createdAtMillis = 100,
        )

        assertEquals(
            "c83cd3b151d4489784da0152c8ea27409a050e2ed0518c8a24a5ad95fa9625ec",
            storageRevisionId(revision),
        )
        assertEquals(storageRevisionId(revision), storageRevisionId(revision.copy(createdAtMillis = 999)))
    }
}
