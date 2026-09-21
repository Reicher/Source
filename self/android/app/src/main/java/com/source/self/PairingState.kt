package com.source.self

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.spec.ECGenParameterSpec
import java.util.Date
import javax.security.auth.x500.X500Principal

data class SourceRef(val id: String, val pin: String, val token: String) {
    companion object {
        fun fromQr(qr: JSONObject): SourceRef {
            require(qr.getInt("v") == 1) { "unsupported QR version" }
            val id = qr.getString("id")
            val pin = qr.getString("fp")
            val token = qr.getString("token")
            require(id.matches(Regex("[A-Za-z0-9_-]{22}"))) { "invalid Source ID" }
            require(pin.matches(Regex("[0-9a-f]{64}"))) { "invalid Source fingerprint" }
            require(token.matches(Regex("[A-Za-z0-9_-]{43}"))) { "invalid pairing token" }
            return SourceRef(id, pin, token)
        }
    }
}

class PairingState(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    private val alias = "self-identity-v1"

    fun ensureSelfIdentity() {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return
        if (source() != null) throw IllegalStateException("paired Self key missing")
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256)
            .setCertificateSubject(X500Principal("CN=Self"))
            .setCertificateSerialNumber(BigInteger.valueOf(now))
            .setCertificateNotBefore(Date(now - 60_000))
            .setCertificateNotAfter(Date(now + 20L * 365 * 24 * 60 * 60 * 1000))
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
    }

    fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    fun keyAlias(): String = alias
    fun isPaired(): Boolean = prefs.getString("person_id", null) != null
    fun personId(): String? = prefs.getString("person_id", null)

    fun source(): SourceRef? {
        val id = prefs.getString("source_id", null) ?: return null
        val pin = prefs.getString("source_pin", null) ?: return null
        return SourceRef(id, pin, prefs.getString("pending_token", "") ?: "")
    }

    fun savePending(source: SourceRef) {
        check(!isPaired())
        check(prefs.edit().putString("source_id", source.id).putString("source_pin", source.pin)
            .putString("pending_token", source.token).commit()) { "could not save pending pairing" }
    }

    fun savePaired(source: SourceRef, personId: String) {
        check(source.id == source()?.id && source.pin == source()?.pin)
        check(personId.matches(Regex("[A-Za-z0-9_-]{22}"))) { "invalid person ID" }
        check(prefs.edit().putString("person_id", personId).remove("pending_token").commit()) { "could not save pairing" }
    }
}
