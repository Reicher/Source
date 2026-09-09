package com.source.client.protocol

import com.source.client.security.SourceCrypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID

class PairingPayloadParserTest {
    private val now = 1_900_000_000_000L
    private val publicKey = SourceCrypto.generateClientKeyPair().public
    private val encodedKey = SourceCrypto.encodePublicKey(publicKey)
    private val nodeId = SourceCrypto.nodeId(publicKey.encoded)

    @Test
    fun `parses exact Node protocol invitation`() {
        val invitation = PairingPayloadParser.parse(payload(), now)

        assertEquals(1, invitation.protocol)
        assertEquals(nodeId, invitation.nodeId)
        assertEquals("Plattservern hemma", invitation.nodeName)
        assertEquals("https://192.168.1.10:8443/api/v1/pairing", invitation.pairingEndpoint)
    }

    @Test
    fun `rejects expired public and identity-mismatched invitations`() {
        assertThrows(PairingPayloadException::class.java) { PairingPayloadParser.parse(payload(expires = now - 1), now) }
        assertThrows(PairingPayloadException::class.java) {
            PairingPayloadParser.parse(payload(endpoint = "https://example.com/api/v1/pairing"), now)
        }
        assertThrows(PairingPayloadException::class.java) {
            PairingPayloadParser.parse(payload(nodeIdOverride = "srcnode_${"a".repeat(43)}"), now)
        }
    }

    @Test
    fun `rejects unknown duplicate and non-Source fields`() {
        assertThrows(PairingPayloadException::class.java) { PairingPayloadParser.parse("https://example.com", now) }
        assertThrows(PairingPayloadException::class.java) { PairingPayloadParser.parse("${payload()}&secret=again", now) }
        assertThrows(PairingPayloadException::class.java) { PairingPayloadParser.parse("${payload()}&extra=value", now) }
    }

    private fun payload(
        expires: Long = now + 300_000,
        endpoint: String = "https://192.168.1.10:8443/api/v1/pairing",
        nodeIdOverride: String = nodeId,
    ): String {
        val values = listOf(
            "v" to "1",
            "node_id" to nodeIdOverride,
            "node_key" to encodedKey,
            "name" to "Plattservern hemma",
            "endpoint" to endpoint,
            "invite" to UUID.fromString("11111111-2222-3333-4444-555555555555").toString(),
            "secret" to "s".repeat(43),
            "expires" to Instant.ofEpochMilli(expires).toString(),
        )
        return "source://pair?" + values.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, Charsets.UTF_8)}"
        }
    }
}
