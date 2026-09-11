package com.source.client.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.source.client.storage.ChatData
import com.source.client.storage.EncryptedBlobStore
import com.source.client.storage.LibraryData
import com.source.client.storage.LibraryItem
import com.source.client.storage.LibraryManifest
import com.source.client.storage.SourceDataStore
import com.source.client.model.ChatConversation
import com.source.client.model.ChatConversationTombstone
import com.source.client.model.ChatConversations
import com.source.client.model.ChatMessage
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.io.ByteArrayInputStream

@RunWith(AndroidJUnit4::class)
class SecureVaultDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferencesName = "source_secure_vault_device_test"
    private val alias = "source-client-device-wrap-test"

    @Before fun cleanBefore() = cleanup()
    @After fun cleanAfter() = cleanup()

    @Test
    fun identityCanBeCreatedPersistedAndUnlockedWithAndroidKeystore() {
        val vault = SecureVault(context, preferencesName, alias)
        val dataStore = SourceDataStore(vault)
        val password = "local test password".toCharArray()
        val created = vault.create("Robin", password)
        val clientId = created.vault.identity.clientId
        created.close()

        assertTrue(vault.isInitialized)
        assertTrue(clientId.startsWith("srcclient_"))
        val profileId = vault.profiles.single().id
        assertNull(SecureVault(context, preferencesName, alias).unlock(profileId, "wrong password".toCharArray()))

        val unlocked = SecureVault(context, preferencesName, alias).unlock(profileId, password)
        assertEquals(clientId, unlocked?.vault?.identity?.clientId)
        assertFalse(unlocked?.vault?.identity?.clientPrivateKey.isNullOrBlank())
        assertTrue(unlocked != null && dataStore.load(unlocked, ChatData).conversations.isEmpty())
        unlocked?.close()
        password.fill('\u0000')
    }

    @Test
    fun conversationIsEncryptedPersistedAndRestorableFromSnapshot() {
        val vault = SecureVault(context, preferencesName, alias)
        val dataStore = SourceDataStore(vault)
        val password = "local test password".toCharArray()
        val created = vault.create("Robin", password)
        val messages = listOf(
            ChatMessage.user("A secret question"),
            ChatMessage.assistant("A local answer"),
        )
        val conversations = conversations(messages).copy(
            tombstones = listOf(ChatConversationTombstone("deleted-conversation", 500)),
        )

        dataStore.save(created, ChatData, conversations)
        val snapshot = dataStore.createSnapshot(created, ChatData, conversations)
        assertFalse(snapshot.toString(Charsets.UTF_8).contains("A secret question"))
        created.close()

        val reopened = SecureVault(context, preferencesName, alias).unlock(vault.profiles.single().id, password)!!
        assertEquals(conversations, dataStore.load(reopened, ChatData))

        dataStore.save(reopened, ChatData, ChatConversations())
        val restored = dataStore.readSnapshot(reopened, ChatData, snapshot)
        dataStore.save(reopened, ChatData, restored)
        assertEquals(conversations, restored)
        assertEquals(conversations, dataStore.load(reopened, ChatData))
        reopened.close()
        password.fill('\u0000')
    }

    @Test
    fun legacyConversationSnapshotMigratesWithoutLosingMessages() {
        val vault = SecureVault(context, preferencesName, alias)
        val dataStore = SourceDataStore(vault)
        val password = "local test password".toCharArray()
        val created = vault.create("Robin", password)
        val legacyPayload = """
            {"version":1,"messages":[
              {"id":"message-1","role":"user","content":"Existing chat","createdAtMillis":100}
            ]}
        """.trimIndent().toByteArray()
        val snapshot = vault.createDataSnapshot(
            created,
            ChatData.descriptor.copy(formatVersion = 1),
            legacyPayload,
        )

        val first = dataStore.readSnapshot(created, ChatData, snapshot)
        val second = dataStore.readSnapshot(created, ChatData, snapshot)

        assertEquals(first, second)
        assertEquals(first.conversations.single().id, first.activeConversationId)
        assertEquals(100L, first.activeConversation!!.createdAtMillis)
        assertEquals("Existing chat", first.activeConversation!!.messages.single().content)
        created.close()
        password.fill('\u0000')
    }

    @Test
    fun nodeDataKeyRestoresSnapshotIntoANewLocalVault() {
        val vault = SecureVault(context, preferencesName, alias)
        val dataStore = SourceDataStore(vault)
        val first = vault.create("First phone", "one".toCharArray())
        val messages = listOf(ChatMessage.user("Data from the old phone"))
        val conversations = conversations(messages)
        val dataKey = SourceCrypto.base64UrlDecode(
            SourceCrypto.generateRecoveryMaterial("srcnode_${"n".repeat(43)}").dataKey,
        )
        val snapshot = dataStore.createSnapshot(first, ChatData, conversations, dataKey)
        first.close()

        val replacement = vault.create("New phone", "two".toCharArray())
        val restored = dataStore.readSnapshot(replacement, ChatData, snapshot, dataKey)
        dataStore.save(replacement, ChatData, restored)
        assertEquals(conversations, restored)
        assertEquals(conversations, dataStore.load(replacement, ChatData))
        replacement.close()
        dataKey.fill(0)
    }

    @Test
    fun libraryFileIsCopiedEncryptedAndManifestRoundTrips() {
        val vault = SecureVault(context, preferencesName, alias)
        val dataStore = SourceDataStore(vault)
        val blobStore = EncryptedBlobStore(context)
        val created = vault.create("Robin", "password".toCharArray())
        val secret = "raw private Library contents".toByteArray()
        val imported = blobStore.importFile(created, ByteArrayInputStream(secret))
        val encryptedFile = context.filesDir.resolve("library/${created.profileId}/${imported.id}.blob")
        assertTrue(encryptedFile.isFile)
        assertFalse(encryptedFile.readBytes().toString(Charsets.UTF_8).contains(secret.toString(Charsets.UTF_8)))

        val item = LibraryItem(
            id = imported.id,
            name = "private.txt",
            mimeType = "text/plain",
            byteCount = imported.byteCount,
            createdAtMillis = System.currentTimeMillis(),
            contentSha256 = imported.contentSha256,
            nodeStored = true,
        )
        val manifest = LibraryManifest(listOf(item), modifiedAtMillis = item.createdAtMillis)
        dataStore.save(created, LibraryData, manifest)
        assertEquals(manifest, dataStore.load(created, LibraryData))
        assertTrue(dataStore.load(created, LibraryData).items.single().nodeStored)
        assertArrayEquals(secret, blobStore.readPreview(created, item, 1024))
        val legacyManifest = JSONObject(LibraryData.encode(manifest).toString(Charsets.UTF_8)).apply {
            getJSONArray("items").getJSONObject(0).remove("nodeStored")
        }.toString().toByteArray(Charsets.UTF_8)
        assertFalse(LibraryData.decode(legacyManifest).items.single().nodeStored)

        val nodeKey = ByteArray(32) { 7 }
        blobStore.createUploadPayload(created, item, nodeKey).use { payload ->
            assertEquals(secret.size + 36L, payload.byteCount)
            val hash = MessageDigest.getInstance("SHA-256").digest(payload.file.readBytes())
                .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
            assertEquals(hash, payload.encryptedSha256)
        }
        created.close()
        secret.fill(0)
        nodeKey.fill(0)
    }

    @Test
    fun multipleUsersKeepPasswordsIdentitiesAndConversationsSeparate() {
        val vault = SecureVault(context, preferencesName, alias)
        val dataStore = SourceDataStore(vault)
        val robinPassword = "Robin password".toCharArray()
        val testPassword = "test".toCharArray()
        val robin = vault.create("Robin", robinPassword)
        dataStore.save(robin, ChatData, conversations(listOf(ChatMessage.user("Robins privata chatt"))))
        robin.close()
        val testUser = vault.create("Test", testPassword)
        dataStore.save(testUser, ChatData, conversations(listOf(ChatMessage.user("Testets privata chatt"))))
        testUser.close()

        val profiles = vault.profiles
        assertEquals(listOf("Robin", "Test"), profiles.map { it.displayName })
        assertNull(vault.unlock(profiles[0].id, testPassword))

        val reopenedRobin = vault.unlock(profiles[0].id, robinPassword)!!
        val reopenedTest = vault.unlock(profiles[1].id, testPassword)!!
        assertEquals("Robin", reopenedRobin.vault.identity.userDisplayName)
        assertEquals("Robins privata chatt", dataStore.load(reopenedRobin, ChatData).activeConversation!!.messages.single().content)
        assertEquals("Test", reopenedTest.vault.identity.userDisplayName)
        assertEquals("Testets privata chatt", dataStore.load(reopenedTest, ChatData).activeConversation!!.messages.single().content)
        assertFalse(reopenedRobin.vault.identity.clientId == reopenedTest.vault.identity.clientId)
        reopenedRobin.close()
        reopenedTest.close()
        robinPassword.fill('\u0000')
        testPassword.fill('\u0000')
    }

    @Test
    fun legacySingleUserVaultMigratesWithoutLosingItsIdentity() {
        val vault = SecureVault(context, preferencesName, alias)
        val password = "old password".toCharArray()
        val created = vault.create("Robin", password)
        val originalClientId = created.vault.identity.clientId
        created.close()
        val generatedProfileId = vault.profiles.single().id
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val legacyKeys = listOf(
            "password_salt",
            "password_nonce",
            "device_nonce",
            "wrapped_vault_key",
            "vault_nonce",
            "vault_data",
            "conversation_nonce",
            "conversation_data",
        )
        val legacyValues = legacyKeys.associateWith { key ->
            preferences.getString("profile.$generatedProfileId.$key", null)
        }
        val editor = preferences.edit().clear().putBoolean("initialized", true)
        legacyValues.forEach { (key, value) -> value?.let { editor.putString(key, it) } }
        assertTrue(editor.commit())

        val migrated = SecureVault(context, preferencesName, alias)
        val migratedProfile = migrated.profiles.single()
        assertEquals("Existing user", migratedProfile.displayName)
        val reopened = migrated.unlock(migratedProfile.id, password)!!
        assertEquals(originalClientId, reopened.vault.identity.clientId)
        assertEquals("Robin", migrated.profiles.single().displayName)
        reopened.close()
        password.fill('\u0000')
    }

    private fun cleanup() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
        context.filesDir.resolve("library").deleteRecursively()
        context.cacheDir.resolve("library-sync").deleteRecursively()
    }

    private fun conversations(messages: List<ChatMessage>): ChatConversations {
        val conversation = ChatConversation(id = "conversation-test", messages = messages)
        return ChatConversations(listOf(conversation), conversation.id)
    }
}
