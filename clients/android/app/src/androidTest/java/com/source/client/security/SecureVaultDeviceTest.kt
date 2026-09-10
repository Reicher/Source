package com.source.client.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.source.client.model.ChatMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

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
        assertTrue(unlocked != null && vault.loadConversation(unlocked).isEmpty())
        unlocked?.close()
        password.fill('\u0000')
    }

    @Test
    fun conversationIsEncryptedPersistedAndRestorableFromSnapshot() {
        val vault = SecureVault(context, preferencesName, alias)
        val password = "local test password".toCharArray()
        val created = vault.create("Robin", password)
        val messages = listOf(
            ChatMessage.user("A secret question"),
            ChatMessage.assistant("A local answer"),
        )

        vault.saveConversation(created, messages)
        val snapshot = vault.createConversationSnapshot(created, messages)
        assertFalse(snapshot.toString(Charsets.UTF_8).contains("A secret question"))
        created.close()

        val reopened = SecureVault(context, preferencesName, alias).unlock(vault.profiles.single().id, password)!!
        assertEquals(messages, vault.loadConversation(reopened))

        vault.saveConversation(reopened, emptyList())
        assertEquals(messages, vault.restoreConversationSnapshot(reopened, snapshot))
        assertEquals(messages, vault.loadConversation(reopened))
        reopened.close()
        password.fill('\u0000')
    }

    @Test
    fun nodeDataKeyRestoresSnapshotIntoANewLocalVault() {
        val vault = SecureVault(context, preferencesName, alias)
        val first = vault.create("First phone", "one".toCharArray())
        val messages = listOf(ChatMessage.user("Data from the old phone"))
        val dataKey = SourceCrypto.base64UrlDecode(
            SourceCrypto.generateRecoveryMaterial("srcnode_${"n".repeat(43)}").dataKey,
        )
        val snapshot = vault.createConversationSnapshot(first, messages, dataKey)
        first.close()

        val replacement = vault.create("New phone", "two".toCharArray())
        assertEquals(messages, vault.restoreConversationSnapshot(replacement, snapshot, dataKey))
        assertEquals(messages, vault.loadConversation(replacement))
        replacement.close()
        dataKey.fill(0)
    }

    @Test
    fun multipleUsersKeepPasswordsIdentitiesAndConversationsSeparate() {
        val vault = SecureVault(context, preferencesName, alias)
        val robinPassword = "Robin password".toCharArray()
        val testPassword = "test".toCharArray()
        val robin = vault.create("Robin", robinPassword)
        vault.saveConversation(robin, listOf(ChatMessage.user("Robins privata chatt")))
        robin.close()
        val testUser = vault.create("Test", testPassword)
        vault.saveConversation(testUser, listOf(ChatMessage.user("Testets privata chatt")))
        testUser.close()

        val profiles = vault.profiles
        assertEquals(listOf("Robin", "Test"), profiles.map { it.displayName })
        assertNull(vault.unlock(profiles[0].id, testPassword))

        val reopenedRobin = vault.unlock(profiles[0].id, robinPassword)!!
        val reopenedTest = vault.unlock(profiles[1].id, testPassword)!!
        assertEquals("Robin", reopenedRobin.vault.identity.userDisplayName)
        assertEquals("Robins privata chatt", vault.loadConversation(reopenedRobin).single().content)
        assertEquals("Test", reopenedTest.vault.identity.userDisplayName)
        assertEquals("Testets privata chatt", vault.loadConversation(reopenedTest).single().content)
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
    }
}
