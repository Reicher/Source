package com.source.client.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.source.client.model.ChatMessage
import com.source.client.model.ChatRole
import com.source.client.model.LocalIdentity
import com.source.client.model.TrustedNode
import com.source.client.model.UnlockedVault
import com.source.client.model.VaultProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class SecureVault(
    private val context: Context,
    preferencesName: String = DEFAULT_PREFERENCES,
    private val keystoreAlias: String = DEFAULT_KEYSTORE_ALIAS,
) {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val profiles: List<VaultProfile>
        get() {
            migrateLegacyVaultIfNeeded()
            return decodeProfiles(preferences.getString(KEY_PROFILES, null))
        }

    val isInitialized: Boolean get() = profiles.isNotEmpty()

    fun create(userName: String, password: CharArray): VaultSession {
        val trimmedName = userName.trim()
        require(trimmedName.isNotEmpty())
        val profile = VaultProfile(UUID.randomUUID().toString(), trimmedName)

        val keyPair = SourceCrypto.generateClientKeyPair()
        val publicKey = SourceCrypto.encodePublicKey(keyPair.public)
        val identity = LocalIdentity(
            userId = UUID.randomUUID().toString(),
            userDisplayName = trimmedName,
            clientId = SourceCrypto.clientId(keyPair.public.encoded),
            clientDisplayName = androidClientName(trimmedName),
            clientPublicKey = publicKey,
            clientPrivateKey = SourceCrypto.encodePrivateKey(keyPair.private),
        )
        val vaultKey = randomBytes(32)
        val salt = randomBytes(16)
        val passwordNonce = randomBytes(12)
        val passwordKey = SourceCrypto.derivePasswordKey(password, salt)
        val passwordEnvelope = try {
            SourceCrypto.encrypt(passwordKey, vaultKey, passwordNonce)
        } finally {
            passwordKey.fill(0)
        }
        val (deviceNonce, deviceEnvelope) = keystoreEncrypt(passwordEnvelope)
        passwordEnvelope.fill(0)

        val session = VaultSession(profile.id, vaultKey, UnlockedVault(identity, emptyList()))
        persistVault(session)
        val updatedProfiles = profiles + profile
        val committed = preferences.edit()
            .putString(profileKey(profile.id, KEY_SALT), SourceCrypto.base64Url(salt))
            .putString(profileKey(profile.id, KEY_PASSWORD_NONCE), SourceCrypto.base64Url(passwordNonce))
            .putString(profileKey(profile.id, KEY_DEVICE_NONCE), SourceCrypto.base64Url(deviceNonce))
            .putString(profileKey(profile.id, KEY_WRAPPED_KEY), SourceCrypto.base64Url(deviceEnvelope))
            .putString(KEY_PROFILES, encodeProfiles(updatedProfiles))
            .commit()
        check(committed) { "Could not persist Source identity" }
        return session
    }

    fun unlock(profileId: String, password: CharArray): VaultSession? {
        if (profiles.none { it.id == profileId }) return null
        var unlockedKey: ByteArray? = null
        return runCatching {
            val salt = storedBytes(profileId, KEY_SALT)
            val passwordNonce = storedBytes(profileId, KEY_PASSWORD_NONCE)
            val deviceNonce = storedBytes(profileId, KEY_DEVICE_NONCE)
            val passwordEnvelope = keystoreDecrypt(storedBytes(profileId, KEY_WRAPPED_KEY), deviceNonce)
            val passwordKey = SourceCrypto.derivePasswordKey(password, salt)
            val vaultKey = try {
                SourceCrypto.decrypt(passwordKey, passwordEnvelope, passwordNonce)
            } finally {
                passwordKey.fill(0)
                passwordEnvelope.fill(0)
            }
            unlockedKey = vaultKey
            val vault = decryptVault(profileId, vaultKey)
            updateProfileName(profileId, vault.identity.userDisplayName)
            VaultSession(profileId, vaultKey, vault)
        }.onFailure { unlockedKey?.fill(0) }.getOrNull()
    }

    fun save(session: VaultSession) = persistVault(session)

    fun loadConversation(session: VaultSession): List<ChatMessage> {
        check(!session.closed)
        val encodedNonce = preferences.getString(profileKey(session.profileId, KEY_CONVERSATION_NONCE), null) ?: return emptyList()
        val encodedData = preferences.getString(profileKey(session.profileId, KEY_CONVERSATION_DATA), null) ?: return emptyList()
        val plaintext = SourceCrypto.decrypt(
            session.key,
            SourceCrypto.base64UrlDecode(encodedData),
            SourceCrypto.base64UrlDecode(encodedNonce),
        )
        return try {
            decodeConversation(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    fun saveConversation(session: VaultSession, messages: List<ChatMessage>) {
        check(!session.closed)
        val (nonce, ciphertext) = encryptConversation(session, messages)
        check(preferences.edit()
            .putString(profileKey(session.profileId, KEY_CONVERSATION_NONCE), SourceCrypto.base64Url(nonce))
            .putString(profileKey(session.profileId, KEY_CONVERSATION_DATA), SourceCrypto.base64Url(ciphertext))
            .commit()) { "Could not persist encrypted conversation" }
    }

    fun createConversationSnapshot(
        session: VaultSession,
        messages: List<ChatMessage>,
        encryptionKey: ByteArray = session.key,
    ): ByteArray {
        check(!session.closed)
        val (nonce, ciphertext) = encryptConversation(encryptionKey, messages)
        return JSONObject().apply {
            put("format", CONVERSATION_SNAPSHOT_FORMAT)
            put("version", CONVERSATION_FORMAT_VERSION)
            put("nonce", SourceCrypto.base64Url(nonce))
            put("ciphertext", SourceCrypto.base64Url(ciphertext))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun restoreConversationSnapshot(
        session: VaultSession,
        snapshot: ByteArray,
        encryptionKey: ByteArray = session.key,
    ): List<ChatMessage> {
        val messages = readConversationSnapshot(session, snapshot, encryptionKey)
        saveConversation(session, messages)
        return messages
    }

    fun readConversationSnapshot(
        session: VaultSession,
        snapshot: ByteArray,
        encryptionKey: ByteArray = session.key,
    ): List<ChatMessage> {
        check(!session.closed)
        val envelope = JSONObject(snapshot.toString(Charsets.UTF_8))
        require(envelope.getString("format") == CONVERSATION_SNAPSHOT_FORMAT)
        require(envelope.getInt("version") == CONVERSATION_FORMAT_VERSION)
        val plaintext = SourceCrypto.decrypt(
            encryptionKey,
            SourceCrypto.base64UrlDecode(envelope.getString("ciphertext")),
            SourceCrypto.base64UrlDecode(envelope.getString("nonce")),
        )
        return try {
            decodeConversation(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun persistVault(session: VaultSession) {
        check(!session.closed)
        val nonce = randomBytes(12)
        val plaintext = encode(session.vault).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            SourceCrypto.encrypt(session.key, plaintext, nonce)
        } finally {
            plaintext.fill(0)
        }
        check(preferences.edit()
            .putString(profileKey(session.profileId, KEY_VAULT_NONCE), SourceCrypto.base64Url(nonce))
            .putString(profileKey(session.profileId, KEY_VAULT_DATA), SourceCrypto.base64Url(ciphertext))
            .commit()) { "Could not persist encrypted Source data" }
    }

    private fun decryptVault(profileId: String, key: ByteArray): UnlockedVault {
        val plaintext = SourceCrypto.decrypt(key, storedBytes(profileId, KEY_VAULT_DATA), storedBytes(profileId, KEY_VAULT_NONCE))
        return try {
            decode(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
    }

    private fun encryptConversation(
        session: VaultSession,
        messages: List<ChatMessage>,
    ): Pair<ByteArray, ByteArray> = encryptConversation(session.key, messages)

    private fun encryptConversation(
        encryptionKey: ByteArray,
        messages: List<ChatMessage>,
    ): Pair<ByteArray, ByteArray> {
        val nonce = randomBytes(12)
        val plaintext = encodeConversation(messages).toByteArray(Charsets.UTF_8)
        val ciphertext = try {
            SourceCrypto.encrypt(encryptionKey, plaintext, nonce)
        } finally {
            plaintext.fill(0)
        }
        return nonce to ciphertext
    }

    private fun getOrCreateDeviceKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keystoreAlias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                keystoreAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun keystoreEncrypt(value: ByteArray): Pair<ByteArray, ByteArray> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // Android Keystore keys with randomized encryption reject caller-supplied
        // IVs. Let KeyMint generate the GCM nonce and persist it with the envelope.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateDeviceKey())
        return cipher.iv to cipher.doFinal(value)
    }

    private fun keystoreDecrypt(value: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateDeviceKey(), GCMParameterSpec(128, nonce))
        return cipher.doFinal(value)
    }

    private fun storedBytes(profileId: String, key: String): ByteArray =
        SourceCrypto.base64UrlDecode(checkNotNull(preferences.getString(profileKey(profileId, key), null)))

    private fun profileKey(profileId: String, key: String) = "profile.$profileId.$key"

    private fun encodeProfiles(profiles: List<VaultProfile>): String = JSONArray().apply {
        profiles.forEach { profile ->
            put(JSONObject().put("id", profile.id).put("displayName", profile.displayName))
        }
    }.toString()

    private fun decodeProfiles(raw: String?): List<VaultProfile> = runCatching {
        val array = JSONArray(raw ?: "[]")
        List(array.length()) { index ->
            array.getJSONObject(index).let { VaultProfile(it.getString("id"), it.getString("displayName")) }
        }
    }.getOrDefault(emptyList())

    private fun updateProfileName(profileId: String, displayName: String) {
        val current = decodeProfiles(preferences.getString(KEY_PROFILES, null))
        if (current.none { it.id == profileId && it.displayName != displayName }) return
        preferences.edit().putString(
            KEY_PROFILES,
            encodeProfiles(current.map { if (it.id == profileId) it.copy(displayName = displayName) else it }),
        ).commit()
    }

    private fun migrateLegacyVaultIfNeeded() {
        if (preferences.contains(KEY_PROFILES) || !preferences.getBoolean(KEY_INITIALIZED, false)) return
        val editor = preferences.edit()
        LEGACY_KEYS.forEach { key ->
            preferences.getString(key, null)?.let { editor.putString(profileKey(LEGACY_PROFILE_ID, key), it) }
        }
        editor.putString(
            KEY_PROFILES,
            encodeProfiles(listOf(VaultProfile(LEGACY_PROFILE_ID, "Befintlig användare"))),
        ).commit()
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun androidClientName(userName: String): String {
        val model = Build.MODEL.trim().take(60).ifBlank { "Android" }
        return "$userName · $model".take(100)
    }

    private fun encode(vault: UnlockedVault): String = JSONObject().apply {
        put("version", 1)
        put("identity", JSONObject().apply {
            put("userId", vault.identity.userId)
            put("userDisplayName", vault.identity.userDisplayName)
            put("clientId", vault.identity.clientId)
            put("clientDisplayName", vault.identity.clientDisplayName)
            put("clientPublicKey", vault.identity.clientPublicKey)
            put("clientPrivateKey", vault.identity.clientPrivateKey)
        })
        put("trustedNodes", JSONArray().apply {
            vault.trustedNodes.forEach { node ->
                put(JSONObject().apply {
                    put("nodeId", node.nodeId)
                    put("nodePublicKey", node.nodePublicKey)
                    put("tlsCaCertificate", node.tlsCaCertificate)
                    put("displayName", node.displayName)
                    put("clientCredential", node.clientCredential)
                    put("userId", node.userId)
                    put("clientId", node.clientId)
                    node.recoveryKey?.let { put("recoveryKey", it) }
                    node.dataKey?.let { put("dataKey", it) }
                    if (node.recoverySetupPending) put("recoverySetupPending", true)
                })
            }
        })
    }.toString()

    private fun decode(raw: String): UnlockedVault {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val identity = root.getJSONObject("identity").let {
            LocalIdentity(
                it.getString("userId"), it.getString("userDisplayName"), it.getString("clientId"),
                it.getString("clientDisplayName"), it.getString("clientPublicKey"), it.getString("clientPrivateKey"),
            )
        }
        val trusted = root.getJSONArray("trustedNodes")
        return UnlockedVault(identity, List(trusted.length()) { index ->
            trusted.getJSONObject(index).let {
                TrustedNode(
                    it.getString("nodeId"), it.getString("nodePublicKey"), it.getString("tlsCaCertificate"), it.getString("displayName"),
                    it.getString("clientCredential"), it.getString("userId"), it.getString("clientId"),
                    it.optString("recoveryKey").takeIf(String::isNotBlank),
                    it.optString("dataKey").takeIf(String::isNotBlank),
                    it.optBoolean("recoverySetupPending", false),
                )
            }
        })
    }

    private fun encodeConversation(messages: List<ChatMessage>): String = JSONObject().apply {
        put("version", CONVERSATION_FORMAT_VERSION)
        put("messages", JSONArray().apply {
            messages.forEach { message ->
                put(JSONObject().apply {
                    put("id", message.id)
                    put("role", message.role.apiValue)
                    put("content", message.content)
                    put("createdAtMillis", message.createdAtMillis)
                })
            }
        })
    }.toString()

    private fun decodeConversation(raw: String): List<ChatMessage> {
        val root = JSONObject(raw)
        require(root.getInt("version") == CONVERSATION_FORMAT_VERSION)
        val messages = root.getJSONArray("messages")
        return List(messages.length()) { index ->
            messages.getJSONObject(index).let {
                ChatMessage(
                    id = it.getString("id"),
                    role = ChatRole.fromApiValue(it.getString("role")),
                    content = it.getString("content"),
                    createdAtMillis = it.getLong("createdAtMillis"),
                )
            }
        }
    }

    companion object {
        private const val DEFAULT_KEYSTORE_ALIAS = "source-client-device-wrap-v1"
        private const val DEFAULT_PREFERENCES = "source_secure_vault_v1"
        private const val KEY_PROFILES = "profiles_v2"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_SALT = "password_salt"
        private const val KEY_PASSWORD_NONCE = "password_nonce"
        private const val KEY_DEVICE_NONCE = "device_nonce"
        private const val KEY_WRAPPED_KEY = "wrapped_vault_key"
        private const val KEY_VAULT_NONCE = "vault_nonce"
        private const val KEY_VAULT_DATA = "vault_data"
        private const val KEY_CONVERSATION_NONCE = "conversation_nonce"
        private const val KEY_CONVERSATION_DATA = "conversation_data"
        private const val CONVERSATION_FORMAT_VERSION = 1
        private const val CONVERSATION_SNAPSHOT_FORMAT = "source-client-conversation"
        private const val LEGACY_PROFILE_ID = "legacy-v1"
        private val LEGACY_KEYS = listOf(
            KEY_SALT,
            KEY_PASSWORD_NONCE,
            KEY_DEVICE_NONCE,
            KEY_WRAPPED_KEY,
            KEY_VAULT_NONCE,
            KEY_VAULT_DATA,
            KEY_CONVERSATION_NONCE,
            KEY_CONVERSATION_DATA,
        )
    }
}

class VaultSession internal constructor(
    val profileId: String,
    internal val key: ByteArray,
    vault: UnlockedVault,
) {
    var vault: UnlockedVault = vault
        internal set
    internal var closed = false

    fun close() {
        key.fill(0)
        closed = true
    }
}
