package com.source.client.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceCryptoTest {
    @Test
    fun `client identity is stable and proves private key possession`() {
        val pair = SourceCrypto.generateClientKeyPair()
        val encoded = SourceCrypto.encodePublicKey(pair.public)
        val decoded = SourceCrypto.decodePublicKey(encoded)
        val payload = "source-pairing-v1\nchallenge"
        val signature = SourceCrypto.sign(pair.private, payload)

        assertTrue(SourceCrypto.clientId(pair.public.encoded).startsWith("srcclient_"))
        assertArrayEquals(pair.public.encoded, decoded.encoded)
        assertTrue(SourceCrypto.verify(decoded, payload, signature))
        assertFalse(SourceCrypto.verify(decoded, "$payload-tampered", signature))
    }

    @Test
    fun `password derived key encrypts the local vault`() {
        val salt = ByteArray(16) { it.toByte() }
        val nonce = ByteArray(12) { (it + 10).toByte() }
        val password = "correct horse source battery".toCharArray()
        val key = SourceCrypto.derivePasswordKey(password, salt, iterations = 1_000)
        val plaintext = "private-key-material".toByteArray()
        val ciphertext = SourceCrypto.encrypt(key, plaintext, nonce)

        assertFalse(ciphertext.contentEquals(plaintext))
        assertArrayEquals(plaintext, SourceCrypto.decrypt(key, ciphertext, nonce))
    }

    @Test
    fun `node recovery key unwraps only its bound data key`() {
        val nodeId = "srcnode_${"n".repeat(43)}"
        val material = SourceCrypto.generateRecoveryMaterial(nodeId)
        val dataKey = SourceCrypto.base64UrlDecode(material.dataKey)

        assertTrue(material.recoveryKey.matches(Regex("^[A-Za-z0-9_-]{43}$")))
        assertTrue(material.envelope.matches(Regex("^[A-Za-z0-9_-]{80}$")))
        assertArrayEquals(dataKey, SourceCrypto.unwrapDataKey(nodeId, material.recoveryKey, material.envelope))
        assertThrows(Exception::class.java) {
            SourceCrypto.unwrapDataKey("srcnode_${"x".repeat(43)}", material.recoveryKey, material.envelope)
        }
    }
}
