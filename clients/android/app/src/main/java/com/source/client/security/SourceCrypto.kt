package com.source.client.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

object SourceCrypto {
    const val PBKDF2_ITERATIONS = 310_000
    private const val GCM_TAG_BITS = 128

    fun generateClientKeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun encodePublicKey(key: PublicKey): String = base64Url(key.encoded)
    fun encodePrivateKey(key: PrivateKey): String = base64Url(key.encoded)

    fun decodePublicKey(value: String): PublicKey =
        KeyFactory.getInstance("Ed25519").generatePublic(X509EncodedKeySpec(base64UrlDecode(value)))

    fun decodePrivateKey(value: String): PrivateKey =
        KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(base64UrlDecode(value)))

    fun clientId(publicKeyDer: ByteArray): String = "srcclient_${base64Url(sha256(publicKeyDer))}"
    fun nodeId(publicKeyDer: ByteArray): String = "srcnode_${base64Url(sha256(publicKeyDer))}"

    fun sign(privateKey: PrivateKey, payload: String): String {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(privateKey)
        signer.update(payload.toByteArray(Charsets.UTF_8))
        return base64Url(signer.sign())
    }

    fun verify(publicKey: PublicKey, payload: String, signature: String): Boolean = runCatching {
        val verifier = Signature.getInstance("Ed25519")
        verifier.initVerify(publicKey)
        verifier.update(payload.toByteArray(Charsets.UTF_8))
        verifier.verify(base64UrlDecode(signature))
    }.getOrDefault(false)

    fun derivePasswordKey(password: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun encrypt(key: ByteArray, plaintext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(plaintext)
    }

    fun decrypt(key: ByteArray, ciphertext: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce))
        return cipher.doFinal(ciphertext)
    }

    fun sha256(value: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(value)
    fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
