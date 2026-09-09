package com.source.client.protocol

import com.source.client.model.PairingInvitation
import com.source.client.security.SourceCrypto
import java.net.InetAddress
import java.net.URI
import java.time.Instant
import java.io.ByteArrayInputStream
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Date
import java.util.UUID

class PairingPayloadException(message: String) : IllegalArgumentException(message)

object PairingPayloadParser {
    private val required = setOf("v", "node_id", "node_key", "ca", "name", "endpoint", "invite", "secret", "expires")

    fun parse(raw: String, nowMillis: Long = System.currentTimeMillis()): PairingInvitation {
        if (raw.length !in 1..8_192) fail("QR-koden har ogiltig storlek.")
        val uri = runCatching { URI(raw) }.getOrElse { fail("Det här är ingen giltig Source-kod.") }
        if (uri.scheme != "source" || uri.host != "pair" || uri.fragment != null || uri.userInfo != null) {
            fail("Det här är ingen giltig Source-kod.")
        }
        val values = parseQuery(uri.rawQuery ?: fail("Source-koden saknar innehåll."))
        val allowedKeys = if (values.containsKey("action")) required + "action" else required
        if (values.keys != allowedKeys || values.values.any { it.size != 1 }) fail("Source-koden har oväntade fält.")
        fun field(name: String) = values.getValue(name).single()

        val version = field("v").toIntOrNull()
        if (version != 1) fail("Source-koden använder ett protokoll som appen inte stöder.")
        val nodeId = field("node_id")
        if (!nodeId.matches(Regex("^srcnode_[A-Za-z0-9_-]{43}$"))) fail("Nodidentiteten är ogiltig.")
        val nodeKey = field("node_key")
        val parsedKey = runCatching { SourceCrypto.decodePublicKey(nodeKey) }.getOrElse { fail("Nodnyckeln är ogiltig.") }
        if (SourceCrypto.nodeId(parsedKey.encoded) != nodeId) fail("Nodidentiteten stämmer inte med nodnyckeln.")
        val caCertificate = validateCaCertificate(field("ca"), nowMillis)

        val name = field("name").trim()
        if (name.isEmpty() || name.length > 100 || name.any { it.isISOControl() }) fail("Nodnamnet är ogiltigt.")
        val endpoint = validateEndpoint(field("endpoint"))
        val invitationId = runCatching { UUID.fromString(field("invite")).toString() }
            .getOrElse { fail("Inbjudan är ogiltig.") }
        val secret = field("secret")
        if (!secret.matches(Regex("^[A-Za-z0-9_-]{43}$"))) fail("Inbjudan är ogiltig.")
        val expires = runCatching { Instant.parse(field("expires")).toEpochMilli() }
            .getOrElse { fail("Inbjudans sluttid är ogiltig.") }
        if (expires <= nowMillis) fail("Inbjudan har gått ut.")
        val recovery = values["action"]?.singleOrNull()?.let {
            if (it != "recover") fail("Source-koden har en ogiltig åtgärd.")
            true
        } ?: false
        return PairingInvitation(version, nodeId, nodeKey, caCertificate, name, endpoint, invitationId, secret, expires, recovery)
    }

    private fun validateCaCertificate(encoded: String, nowMillis: Long): String {
        if (encoded.length !in 1..4_096 || !encoded.matches(Regex("^[A-Za-z0-9_-]+$"))) {
            fail("Nodens CA-certifikat är ogiltigt.")
        }
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(SourceCrypto.base64UrlDecode(encoded)),
            ) as X509Certificate
        }.getOrElse { fail("Nodens CA-certifikat är ogiltigt.") }
        if (certificate.basicConstraints < 0 || runCatching { certificate.checkValidity(Date(nowMillis)) }.isFailure) {
            fail("Nodens CA-certifikat är ogiltigt eller har gått ut.")
        }
        return SourceCrypto.base64Url(certificate.encoded)
    }

    private fun validateEndpoint(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrElse { fail("Nodens adress är ogiltig.") }
        if (uri.scheme != "https" || uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            uri.path != "/api/v1/pairing" || uri.port == 0 || uri.port < -1 || uri.port > 65_535
        ) fail("Nodens adress är ogiltig.")
        val host = uri.host.lowercase()
        val localName = host == "localhost" || host.endsWith(".local")
        // Never resolve arbitrary DNS while validating untrusted QR input.
        val isLiteral = host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) || host.contains(':')
        val localAddress = if (isLiteral) runCatching { InetAddress.getByName(host) }.getOrNull()?.let {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress ||
                (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
        } == true else false
        if (!localName && !localAddress) fail("Nodens adress finns inte på det lokala nätverket.")
        return uri.toASCIIString().removeSuffix("/")
    }

    private fun parseQuery(query: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        for (part in query.split('&')) {
            val bits = part.split('=', limit = 2)
            if (bits.size != 2) fail("Source-koden är felaktig.")
            val key = decode(bits[0])
            val value = decode(bits[1])
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, Charsets.UTF_8)
    }.getOrElse { fail("Source-koden är felaktigt kodad.") }

    private fun fail(message: String): Nothing = throw PairingPayloadException(message)
}
