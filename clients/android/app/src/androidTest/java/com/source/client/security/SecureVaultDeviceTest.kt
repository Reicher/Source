package com.source.client.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
        val password = "lokalt testlösenord".toCharArray()
        val created = vault.create("Robin", password)
        val clientId = created.vault.identity.clientId
        created.close()

        assertTrue(vault.isInitialized)
        assertTrue(clientId.startsWith("srcclient_"))
        assertNull(SecureVault(context, preferencesName, alias).unlock("fel lösenord".toCharArray()))

        val unlocked = SecureVault(context, preferencesName, alias).unlock(password)
        assertEquals(clientId, unlocked?.vault?.identity?.clientId)
        assertFalse(unlocked?.vault?.identity?.clientPrivateKey.isNullOrBlank())
        unlocked?.close()
        password.fill('\u0000')
    }

    private fun cleanup() {
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) store.deleteEntry(alias)
    }
}
