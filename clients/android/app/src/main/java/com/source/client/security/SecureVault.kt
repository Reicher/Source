package com.source.client.security

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.source.client.R
import com.source.client.storage.SourceDataDescriptor
import com.source.client.storage.CanonicalSyncState
import com.source.client.storage.decodeCanonicalSyncState
import com.source.client.storage.encodeCanonicalSyncState
import com.source.client.model.LocalIdentity
import com.source.client.model.TrustedNode
import com.source.client.model.UnlockedVault
import com.source.client.model.VaultProfile
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.AEADBadTagException
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

        val session = VaultSession(profile.id, vaultKey, UnlockedVault(identity))
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

    fun unlock(profileId: String, password: CharArray): VaultSession? =
        (unlockDetailed(profileId, password) as? VaultUnlockResult.Success)?.session

    fun unlockDetailed(profileId: String, password: CharArray): VaultUnlockResult {
        if (profiles.none { it.id == profileId }) return VaultUnlockResult.Unreadable
        val envelopeData = try {
            val salt = storedBytes(profileId, KEY_SALT)
            val passwordNonce = storedBytes(profileId, KEY_PASSWORD_NONCE)
            val deviceNonce = storedBytes(profileId, KEY_DEVICE_NONCE)
            Triple(
                keystoreDecrypt(storedBytes(profileId, KEY_WRAPPED_KEY), deviceNonce),
                salt,
                passwordNonce,
            )
        } catch (_: Exception) {
            return VaultUnlockResult.Unreadable
        }
        val (envelope, salt, passwordNonce) = envelopeData
        val passwordKey = try {
            SourceCrypto.derivePasswordKey(password, salt)
        } catch (_: Exception) {
            envelope.fill(0)
            return VaultUnlockResult.Unreadable
        }
        val vaultKey = try {
            SourceCrypto.decrypt(passwordKey, envelope, passwordNonce)
        } catch (_: AEADBadTagException) {
            return VaultUnlockResult.IncorrectPassword
        } catch (_: Exception) {
            return VaultUnlockResult.Unreadable
        } finally {
            passwordKey.fill(0)
            envelope.fill(0)
        }
        return try {
            val decoded = decryptVault(profileId, vaultKey)
            updateProfileName(profileId, decoded.vault.identity.userDisplayName)
            val session = VaultSession(profileId, vaultKey, decoded.vault)
            if (decoded.requiresMigration) persistVault(session)
            VaultUnlockResult.Success(session)
        } catch (_: Exception) {
            vaultKey.fill(0)
            VaultUnlockResult.Unreadable
        }
    }

    fun save(session: VaultSession) = persistVault(session)

    /** Removes one local profile and its encrypted device-only data without contacting a Node. */
    fun deleteProfile(profileId: String) = deleteProfile(profileId, ::deleteLocalFiles)

    internal fun deleteProfile(profileId: String, removeLocalFiles: (String) -> Unit) {
        val currentProfiles = profiles
        if (currentProfiles.none { it.id == profileId }) return

        // Keep the profile and wrapped key until every local file is gone. If this
        // fails, callers can surface the error and retry the same profile.
        removeLocalFiles(profileId)

        val profilePrefix = "profile.$profileId."
        val editor = preferences.edit()
        preferences.all.keys.filter { it.startsWith(profilePrefix) }.forEach(editor::remove)
        if (profileId == LEGACY_PROFILE_ID) {
            LEGACY_KEYS.forEach(editor::remove)
            editor.remove(KEY_INITIALIZED)
        }
        editor.putString(KEY_PROFILES, encodeProfiles(currentProfiles.filterNot { it.id == profileId }))
        check(editor.commit()) { "Could not delete Source identity" }
    }

    private fun deleteLocalFiles(profileId: String) {
        check(context.filesDir.resolve("library/$profileId").deleteRecursively()) {
            "Could not delete encrypted Library data"
        }
        check(context.cacheDir.resolve("library-sync").deleteRecursively()) {
            "Could not delete cached Library data"
        }
    }

    internal fun loadData(session: VaultSession, data: SourceDataDescriptor): ByteArray? {
        check(!session.closed)
        val encodedNonce = preferences.getString(profileKey(session.profileId, dataKey(data.id, KEY_DATA_NONCE)), null)
            ?: return null
        val encodedData = preferences.getString(profileKey(session.profileId, dataKey(data.id, KEY_DATA_VALUE)), null)
            ?: return null
        return SourceCrypto.decrypt(
            session.key,
            SourceCrypto.base64UrlDecode(encodedData),
            SourceCrypto.base64UrlDecode(encodedNonce),
        )
    }

    internal fun saveData(session: VaultSession, data: SourceDataDescriptor, plaintext: ByteArray) {
        check(!session.closed)
        val nonce = randomBytes(12)
        val ciphertext = SourceCrypto.encrypt(session.key, plaintext, nonce)
        check(preferences.edit()
            .putString(profileKey(session.profileId, dataKey(data.id, KEY_DATA_NONCE)), SourceCrypto.base64Url(nonce))
            .putString(profileKey(session.profileId, dataKey(data.id, KEY_DATA_VALUE)), SourceCrypto.base64Url(ciphertext))
            .commit()) { "Could not persist encrypted Source data" }
    }

    internal fun loadSyncState(session: VaultSession, data: SourceDataDescriptor): CanonicalSyncState? {
        check(!session.closed)
        val nonce = preferences.getString(
            profileKey(session.profileId, dataKey(data.id, KEY_SYNC_NONCE)),
            null,
        ) ?: return null
        val ciphertext = preferences.getString(
            profileKey(session.profileId, dataKey(data.id, KEY_SYNC_VALUE)),
            null,
        ) ?: return null
        val plaintext = SourceCrypto.decrypt(
            session.key,
            SourceCrypto.base64UrlDecode(ciphertext),
            SourceCrypto.base64UrlDecode(nonce),
        )
        return try {
            decodeCanonicalSyncState(plaintext)
        } finally {
            plaintext.fill(0)
        }
    }

    internal fun saveSyncState(session: VaultSession, data: SourceDataDescriptor, state: CanonicalSyncState) {
        check(!session.closed)
        val plaintext = encodeCanonicalSyncState(state)
        try {
            saveEncryptedSyncState(session, data, plaintext, preferences.edit()).commitOrThrow()
        } finally {
            plaintext.fill(0)
        }
    }

    internal fun saveDataAndSyncState(
        session: VaultSession,
        data: SourceDataDescriptor,
        plaintext: ByteArray,
        state: CanonicalSyncState,
    ) {
        check(!session.closed)
        val dataNonce = randomBytes(12)
        val dataCiphertext = SourceCrypto.encrypt(session.key, plaintext, dataNonce)
        val statePlaintext = encodeCanonicalSyncState(state)
        try {
            val editor = preferences.edit()
                .putString(profileKey(session.profileId, dataKey(data.id, KEY_DATA_NONCE)), SourceCrypto.base64Url(dataNonce))
                .putString(profileKey(session.profileId, dataKey(data.id, KEY_DATA_VALUE)), SourceCrypto.base64Url(dataCiphertext))
            saveEncryptedSyncState(session, data, statePlaintext, editor).commitOrThrow()
        } finally {
            statePlaintext.fill(0)
        }
    }

    private fun saveEncryptedSyncState(
        session: VaultSession,
        data: SourceDataDescriptor,
        plaintext: ByteArray,
        editor: android.content.SharedPreferences.Editor,
    ): android.content.SharedPreferences.Editor {
        val nonce = randomBytes(12)
        val ciphertext = SourceCrypto.encrypt(session.key, plaintext, nonce)
        return editor
            .putString(profileKey(session.profileId, dataKey(data.id, KEY_SYNC_NONCE)), SourceCrypto.base64Url(nonce))
            .putString(profileKey(session.profileId, dataKey(data.id, KEY_SYNC_VALUE)), SourceCrypto.base64Url(ciphertext))
    }

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Could not persist canonical Source sync state" }
    }

    internal fun createDataSnapshot(
        session: VaultSession,
        data: SourceDataDescriptor,
        plaintext: ByteArray,
        encryptionKey: ByteArray = session.key,
    ): ByteArray {
        check(!session.closed)
        val nonce = randomBytes(12)
        val ciphertext = SourceCrypto.encrypt(encryptionKey, plaintext, nonce)
        return JSONObject().apply {
            put("format", data.snapshotFormat)
            put("version", data.formatVersion)
            put("nonce", SourceCrypto.base64Url(nonce))
            put("ciphertext", SourceCrypto.base64Url(ciphertext))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    internal fun readDataSnapshot(
        session: VaultSession,
        data: SourceDataDescriptor,
        snapshot: ByteArray,
        encryptionKey: ByteArray = session.key,
        supportedFormatVersions: Set<Int> = setOf(data.formatVersion),
    ): ByteArray {
        check(!session.closed)
        val envelope = JSONObject(snapshot.toString(Charsets.UTF_8))
        require(envelope.getString("format") == data.snapshotFormat)
        require(envelope.getInt("version") in supportedFormatVersions)
        return SourceCrypto.decrypt(
            encryptionKey,
            SourceCrypto.base64UrlDecode(envelope.getString("ciphertext")),
            SourceCrypto.base64UrlDecode(envelope.getString("nonce")),
        )
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

    private fun decryptVault(profileId: String, key: ByteArray): DecodedVault {
        val plaintext = SourceCrypto.decrypt(key, storedBytes(profileId, KEY_VAULT_DATA), storedBytes(profileId, KEY_VAULT_NONCE))
        return try {
            decode(plaintext.toString(Charsets.UTF_8))
        } finally {
            plaintext.fill(0)
        }
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
            encodeProfiles(listOf(VaultProfile(LEGACY_PROFILE_ID, context.getString(R.string.existing_user)))),
        ).commit()
    }

    private fun randomBytes(size: Int) = ByteArray(size).also(random::nextBytes)

    private fun androidClientName(userName: String): String {
        val model = Build.MODEL.trim().take(60).ifBlank { context.getString(R.string.android_device_name) }
        return "$userName · $model".take(100)
    }

    private fun encode(vault: UnlockedVault): String = JSONObject().apply {
        put("version", 2)
        put("identity", JSONObject().apply {
            put("userId", vault.identity.userId)
            put("userDisplayName", vault.identity.userDisplayName)
            put("clientId", vault.identity.clientId)
            put("clientDisplayName", vault.identity.clientDisplayName)
            put("clientPublicKey", vault.identity.clientPublicKey)
            put("clientPrivateKey", vault.identity.clientPrivateKey)
        })
        vault.authoritativeNode?.let { put("authoritativeNode", encodeTrustedNode(it)) }
    }.toString()

    private fun decode(raw: String): DecodedVault {
        val root = JSONObject(raw)
        val version = root.getInt("version")
        require(version == 1 || version == 2)
        val identity = root.getJSONObject("identity").let {
            LocalIdentity(
                it.getString("userId"), it.getString("userDisplayName"), it.getString("clientId"),
                it.getString("clientDisplayName"), it.getString("clientPublicKey"), it.getString("clientPrivateKey"),
            )
        }
        val authoritativeNode = when {
            version == 1 -> decodeSingleLegacyNode(root.getJSONArray("trustedNodes"))
            root.has("nodeAuthority") -> decodeIntermediateNodeAuthority(root.getJSONObject("nodeAuthority"))
            else -> root.optJSONObject("authoritativeNode")?.let(::decodeTrustedNode)
        }
        return DecodedVault(
            vault = UnlockedVault(identity, authoritativeNode),
            requiresMigration = version == 1 || root.has("nodeAuthority"),
        )
    }

    private fun decodeSingleLegacyNode(nodes: JSONArray): TrustedNode? {
        require(nodes.length() <= 1) { "A legacy profile is bound to multiple Nodes" }
        return if (nodes.length() == 1) decodeTrustedNode(nodes.getJSONObject(0)) else null
    }

    private fun decodeIntermediateNodeAuthority(authority: JSONObject): TrustedNode? = when (authority.getString("status")) {
        "unpaired" -> null
        "authoritative" -> decodeTrustedNode(authority.getJSONObject("node"))
        "ambiguous_legacy" -> decodeSingleLegacyNode(authority.getJSONArray("nodes"))
        else -> throw IllegalArgumentException("Unsupported Node authority status")
    }

    private fun encodeTrustedNode(node: TrustedNode): JSONObject = JSONObject().apply {
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
    }

    private fun decodeTrustedNode(raw: JSONObject): TrustedNode = TrustedNode(
        raw.getString("nodeId"), raw.getString("nodePublicKey"), raw.getString("tlsCaCertificate"), raw.getString("displayName"),
        raw.getString("clientCredential"), raw.getString("userId"), raw.getString("clientId"),
        raw.optString("recoveryKey").takeIf(String::isNotBlank),
        raw.optString("dataKey").takeIf(String::isNotBlank),
        raw.optBoolean("recoverySetupPending", false),
    )

    private fun dataKey(dataId: String, suffix: String) = "${dataId}_$suffix"

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
        private const val KEY_DATA_NONCE = "nonce"
        private const val KEY_DATA_VALUE = "data"
        private const val KEY_SYNC_NONCE = "sync_nonce"
        private const val KEY_SYNC_VALUE = "sync_data"
        private const val KEY_CONVERSATION_NONCE = "conversation_nonce"
        private const val KEY_CONVERSATION_DATA = "conversation_data"
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

private data class DecodedVault(
    val vault: UnlockedVault,
    val requiresMigration: Boolean,
)

sealed interface VaultUnlockResult {
    data class Success(val session: VaultSession) : VaultUnlockResult
    data object IncorrectPassword : VaultUnlockResult
    data object Unreadable : VaultUnlockResult
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
