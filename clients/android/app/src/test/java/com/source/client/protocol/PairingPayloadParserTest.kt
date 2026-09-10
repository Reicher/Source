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
    private val caCertificate = "MIIBRjCB-aADAgECAhRVRG_Eel2JGVX0d6gs588FanR7FTAFBgMrZXAwGTEXMBUGA1UEAwwOU291cmNlLVRlc3QtQ0EwHhcNMjYwOTA5MTIyODA1WhcNMzYwOTA2MTIyODA1WjAZMRcwFQYDVQQDDA5Tb3VyY2UtVGVzdC1DQTAqMAUGAytlcAMhAPo_2axphEzqC7vn5ElfUEQEcBxtHuM-3PxtGmVpm9lwo1MwUTAdBgNVHQ4EFgQUQlM82RFXRYILrXvF_mZe-43lzsQwHwYDVR0jBBgwFoAUQlM82RFXRYILrXvF_mZe-43lzsQwDwYDVR0TAQH_BAUwAwEB_zAFBgMrZXADQQC_DYnJEkF3ONdmL4HNnTdtmgG8BP4JRjjKLpz68qxqvOAhZWhIEfiiqet4IloN9MQE8v0wXJ_z-5bQt8fxqMYF"

    @Test
    fun `parses exact Node protocol invitation`() {
        val invitation = PairingPayloadParser.parse(payload(), now)

        assertEquals(1, invitation.protocol)
        assertEquals(nodeId, invitation.nodeId)
        assertEquals(caCertificate, invitation.tlsCaCertificate)
        assertEquals("Home server", invitation.nodeName)
        assertEquals("https://192.168.1.10:8443/api/v1/pairing", invitation.pairingEndpoint)
        assertEquals(false, invitation.recovery)
        assertEquals(true, PairingPayloadParser.parse("${payload()}&action=recover", now).recovery)
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
        assertThrows(PairingPayloadException::class.java) { PairingPayloadParser.parse("${payload()}&action=delete", now) }
    }

    @Test
    fun `rejects malformed and non-CA trust anchors`() {
        assertThrows(PairingPayloadException::class.java) {
            PairingPayloadParser.parse(payload(caOverride = "not-a-certificate"), now)
        }
        assertThrows(PairingPayloadException::class.java) {
            PairingPayloadParser.parse(payload().replace("&ca=$caCertificate", ""), now)
        }
    }

    private fun payload(
        expires: Long = now + 300_000,
        endpoint: String = "https://192.168.1.10:8443/api/v1/pairing",
        nodeIdOverride: String = nodeId,
        caOverride: String = caCertificate,
    ): String {
        val values = listOf(
            "v" to "1",
            "node_id" to nodeIdOverride,
            "node_key" to encodedKey,
            "ca" to caOverride,
            "name" to "Home server",
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
