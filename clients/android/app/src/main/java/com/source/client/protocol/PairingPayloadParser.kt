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

enum class PairingPayloadError {
    INVALID_SIZE,
    NOT_SOURCE_CODE,
    MISSING_CONTENT,
    UNEXPECTED_FIELDS,
    UNSUPPORTED_PROTOCOL,
    INVALID_NODE_IDENTITY,
    INVALID_NODE_KEY,
    NODE_IDENTITY_MISMATCH,
    INVALID_NODE_NAME,
    INVALID_INVITATION,
    INVALID_EXPIRATION,
    EXPIRED_INVITATION,
    INVALID_ACTION,
    INVALID_CA_CERTIFICATE,
    EXPIRED_CA_CERTIFICATE,
    INVALID_ENDPOINT,
    NON_LOCAL_ENDPOINT,
    MALFORMED,
    INVALID_ENCODING,
}

class PairingPayloadException(val error: PairingPayloadError) :
    IllegalArgumentException("Invalid Source pairing payload: ${error.name.lowercase()}")

object PairingPayloadParser {
    private val required = setOf("v", "node_id", "node_key", "ca", "name", "endpoint", "invite", "secret", "expires")

    fun parse(raw: String, nowMillis: Long = System.currentTimeMillis()): PairingInvitation {
        if (raw.length !in 1..8_192) fail(PairingPayloadError.INVALID_SIZE)
        val uri = runCatching { URI(raw) }.getOrElse { fail(PairingPayloadError.NOT_SOURCE_CODE) }
        if (uri.scheme != "source" || uri.host != "pair" || uri.fragment != null || uri.userInfo != null) {
            fail(PairingPayloadError.NOT_SOURCE_CODE)
        }
        val values = parseQuery(uri.rawQuery ?: fail(PairingPayloadError.MISSING_CONTENT))
        val allowedKeys = if (values.containsKey("action")) required + "action" else required
        if (values.keys != allowedKeys || values.values.any { it.size != 1 }) fail(PairingPayloadError.UNEXPECTED_FIELDS)
        fun field(name: String) = values.getValue(name).single()

        val version = field("v").toIntOrNull()
        if (version != 1) fail(PairingPayloadError.UNSUPPORTED_PROTOCOL)
        val nodeId = field("node_id")
        if (!nodeId.matches(Regex("^srcnode_[A-Za-z0-9_-]{43}$"))) fail(PairingPayloadError.INVALID_NODE_IDENTITY)
        val nodeKey = field("node_key")
        val parsedKey = runCatching { SourceCrypto.decodePublicKey(nodeKey) }.getOrElse { fail(PairingPayloadError.INVALID_NODE_KEY) }
        if (SourceCrypto.nodeId(parsedKey.encoded) != nodeId) fail(PairingPayloadError.NODE_IDENTITY_MISMATCH)
        val caCertificate = validateCaCertificate(field("ca"), nowMillis)

        val name = field("name").trim()
        if (name.isEmpty() || name.length > 100 || name.any { it.isISOControl() }) fail(PairingPayloadError.INVALID_NODE_NAME)
        val endpoint = validateEndpoint(field("endpoint"))
        val invitationId = runCatching { UUID.fromString(field("invite")).toString() }
            .getOrElse { fail(PairingPayloadError.INVALID_INVITATION) }
        val secret = field("secret")
        if (!secret.matches(Regex("^[A-Za-z0-9_-]{43}$"))) fail(PairingPayloadError.INVALID_INVITATION)
        val expires = runCatching { Instant.parse(field("expires")).toEpochMilli() }
            .getOrElse { fail(PairingPayloadError.INVALID_EXPIRATION) }
        if (expires <= nowMillis) fail(PairingPayloadError.EXPIRED_INVITATION)
        val recovery = values["action"]?.singleOrNull()?.let {
            if (it != "recover") fail(PairingPayloadError.INVALID_ACTION)
            true
        } ?: false
        return PairingInvitation(version, nodeId, nodeKey, caCertificate, name, endpoint, invitationId, secret, expires, recovery)
    }

    private fun validateCaCertificate(encoded: String, nowMillis: Long): String {
        if (encoded.length !in 1..4_096 || !encoded.matches(Regex("^[A-Za-z0-9_-]+$"))) {
            fail(PairingPayloadError.INVALID_CA_CERTIFICATE)
        }
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(SourceCrypto.base64UrlDecode(encoded)),
            ) as X509Certificate
        }.getOrElse { fail(PairingPayloadError.INVALID_CA_CERTIFICATE) }
        if (certificate.basicConstraints < 0 || runCatching { certificate.checkValidity(Date(nowMillis)) }.isFailure) {
            fail(PairingPayloadError.EXPIRED_CA_CERTIFICATE)
        }
        return SourceCrypto.base64Url(certificate.encoded)
    }

    private fun validateEndpoint(raw: String): String {
        val uri = runCatching { URI(raw) }.getOrElse { fail(PairingPayloadError.INVALID_ENDPOINT) }
        if (uri.scheme != "https" || uri.host == null || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            uri.path != "/api/v1/pairing" || uri.port == 0 || uri.port < -1 || uri.port > 65_535
        ) fail(PairingPayloadError.INVALID_ENDPOINT)
        val host = uri.host.lowercase()
        val localName = host == "localhost" || host.endsWith(".local")
        // Never resolve arbitrary DNS while validating untrusted QR input.
        val isLiteral = host.matches(Regex("^\\d{1,3}(?:\\.\\d{1,3}){3}$")) || host.contains(':')
        val localAddress = if (isLiteral) runCatching { InetAddress.getByName(host) }.getOrNull()?.let {
            it.isAnyLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress || it.isSiteLocalAddress ||
                (it.address.size == 16 && (it.address[0].toInt() and 0xfe) == 0xfc)
        } == true else false
        if (!localName && !localAddress) fail(PairingPayloadError.NON_LOCAL_ENDPOINT)
        return uri.toASCIIString().removeSuffix("/")
    }

    private fun parseQuery(query: String): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        for (part in query.split('&')) {
            val bits = part.split('=', limit = 2)
            if (bits.size != 2) fail(PairingPayloadError.MALFORMED)
            val key = decode(bits[0])
            val value = decode(bits[1])
            result.getOrPut(key) { mutableListOf() }.add(value)
        }
        return result
    }

    private fun decode(value: String): String = runCatching {
        java.net.URLDecoder.decode(value, Charsets.UTF_8)
    }.getOrElse { fail(PairingPayloadError.INVALID_ENCODING) }

    private fun fail(error: PairingPayloadError): Nothing = throw PairingPayloadException(error)
}
