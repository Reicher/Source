package com.source.client.protocol

import com.source.client.model.LocalIdentity
import com.source.client.model.ChatMessage
import com.source.client.model.PairingInvitation
import com.source.client.model.PairingResult
import com.source.client.model.TrustedNode
import com.source.client.security.SourceCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.URL
import java.security.MessageDigest
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

class SourceApiException(val code: String, message: String) : IOException(message)

class SourceNodeApi {
    private val random = SecureRandom()

    suspend fun pair(
        invitation: PairingInvitation,
        identity: LocalIdentity,
        recoveryKey: String,
        recoveryEnvelope: String? = null,
    ): PairingResult = withContext(Dispatchers.IO) {
        val start = postJson(
            "${invitation.pairingEndpoint}/start",
            JSONObject().apply {
                put("protocol", 1)
                put("invitationId", invitation.invitationId)
                put("invitationSecret", invitation.invitationSecret)
                put("clientPublicKey", identity.clientPublicKey)
                put("userDisplayName", identity.userDisplayName)
                put("clientDisplayName", identity.clientDisplayName)
            },
            invitation.tlsCaCertificate,
        )
        if (start.getInt("protocol") != 1) throw SourceApiException("unsupported_pairing_protocol", "Protokollet stöds inte.")
        val handshakeId = start.requiredString("handshakeId")
        val challenge = start.requiredString("challenge")
        val signingPayload = start.requiredString("signingPayload")
        val nodeSignature = start.requiredString("nodeSignature")
        if (runCatching { java.util.UUID.fromString(handshakeId) }.isFailure ||
            !challenge.matches(Regex("^[A-Za-z0-9_-]{43}$")) ||
            !nodeSignature.matches(Regex("^[A-Za-z0-9_-]{80,100}$"))
        ) throw SourceApiException("invalid_challenge", "Noden skickade ett ogiltigt svar.")
        val expires = runCatching { java.time.Instant.parse(start.requiredString("expiresAt")).toEpochMilli() }.getOrNull()
            ?: throw SourceApiException("invalid_challenge", "Noden skickade ett ogiltigt svar.")
        if (expires <= System.currentTimeMillis() || expires > invitation.expiresAtMillis) {
            throw SourceApiException("expired_challenge", "Parkopplingsförsöket har gått ut.")
        }
        val expectedPayload = listOf(
            "source-pairing-v1", invitation.nodeId, invitation.invitationId, handshakeId, challenge,
            identity.clientId,
            SourceCrypto.base64Url(identity.userDisplayName.toByteArray(Charsets.UTF_8)),
            SourceCrypto.base64Url(identity.clientDisplayName.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")
        if (signingPayload != expectedPayload || !SourceCrypto.verify(
                SourceCrypto.decodePublicKey(invitation.nodePublicKey),
                signingPayload,
                nodeSignature,
            )
        ) throw SourceApiException("node_proof_failed", "Nodens kryptografiska identitet kunde inte verifieras.")

        val complete = postJson(
            "${invitation.pairingEndpoint}/complete",
            JSONObject().apply {
                put("protocol", 1)
                put("invitationId", invitation.invitationId)
                put("invitationSecret", invitation.invitationSecret)
                put("handshakeId", handshakeId)
                put("signature", SourceCrypto.sign(SourceCrypto.decodePrivateKey(identity.clientPrivateKey), signingPayload))
                put("recoveryKey", recoveryKey)
                recoveryEnvelope?.let { put("recoveryEnvelope", it) }
            },
            invitation.tlsCaCertificate,
        )
        if (complete.getInt("protocol") != 1 || complete.requiredString("nodeId") != invitation.nodeId) {
            throw SourceApiException("node_identity_changed", "Nodidentiteten ändrades under parkopplingen.")
        }
        val user = complete.getJSONObject("user")
        val client = complete.getJSONObject("client")
        val returnedClientId = client.requiredString("id")
        val returnedUserId = user.requiredString("id")
        val credential = complete.requiredString("clientCredential")
        if (returnedClientId != identity.clientId ||
            runCatching { java.util.UUID.fromString(returnedUserId) }.isFailure ||
            !credential.matches(Regex("^[A-Za-z0-9_-]{43}$"))
        ) {
            throw SourceApiException("client_identity_changed", "Noden returnerade fel klientidentitet.")
        }
        PairingResult(
            trustedNode = TrustedNode(
                nodeId = invitation.nodeId,
                nodePublicKey = invitation.nodePublicKey,
                tlsCaCertificate = invitation.tlsCaCertificate,
                displayName = invitation.nodeName,
                clientCredential = credential,
                userId = returnedUserId,
                clientId = returnedClientId,
                recoveryKey = recoveryKey,
            ),
            recoveryEnvelope = complete.optString("recoveryEnvelope").takeIf(String::isNotBlank),
        )
    }

    suspend fun setupRecovery(
        apiBaseUrl: String,
        trusted: TrustedNode,
        recoveryKey: String,
        recoveryEnvelope: String,
    ) = withContext(Dispatchers.IO) {
        postJson(
            "${apiBaseUrl.removeSuffix("/")}/recovery/setup",
            JSONObject().put("recoveryKey", recoveryKey).put("recoveryEnvelope", recoveryEnvelope),
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
    }

    suspend fun authenticate(apiBaseUrl: String, trusted: TrustedNode): TrustedNode = withContext(Dispatchers.IO) {
        val nonce = SourceCrypto.base64Url(ByteArray(32).also(random::nextBytes))
        val proof = postJson(
            "${apiBaseUrl.removeSuffix("/")}/identity/challenge",
            JSONObject().put("protocol", 1).put("nonce", nonce),
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        val displayName = proof.requiredString("displayName")
        val nodeSignature = proof.requiredString("nodeSignature")
        if (displayName.length !in 1..100 || displayName.any { it.isISOControl() } ||
            !nodeSignature.matches(Regex("^[A-Za-z0-9_-]{80,100}$"))
        ) throw SourceApiException("invalid_response", "Noden skickade ett ogiltigt svar.")
        val expectedPayload = listOf(
            "source-node-auth-v1", trusted.nodeId, trusted.clientId, nonce,
            SourceCrypto.base64Url(displayName.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")
        val valid = proof.getInt("protocol") == 1 &&
            proof.requiredString("nodeId") == trusted.nodeId &&
            proof.requiredString("nodePublicKey") == trusted.nodePublicKey &&
            proof.requiredString("clientId") == trusted.clientId &&
            proof.requiredString("nonce") == nonce &&
            proof.requiredString("signingPayload") == expectedPayload &&
            SourceCrypto.verify(
                SourceCrypto.decodePublicKey(trusted.nodePublicKey),
                expectedPayload,
                nodeSignature,
            )
        if (!valid) throw SourceApiException("node_proof_failed", "Nodens identitet kunde inte verifieras.")
        trusted.copy(displayName = displayName)
    }

    suspend fun chat(
        apiBaseUrl: String,
        trusted: TrustedNode,
        messages: List<ChatMessage>,
    ): ChatMessage = withContext(Dispatchers.IO) {
        val response = postJson(
            "${apiBaseUrl.removeSuffix("/")}/chat",
            JSONObject().put("messages", JSONArray().apply {
                messages.forEach { message ->
                    put(JSONObject().put("role", message.role.apiValue).put("content", message.content))
                }
            }),
            trusted.tlsCaCertificate,
            trusted.clientCredential,
            CHAT_TIMEOUT_MILLIS,
        )
        val message = response.optJSONObject("message")
            ?: throw SourceApiException("invalid_response", "Noden skickade ett ofullständigt svar.")
        val role = message.requiredString("role")
        val content = message.requiredString("content").trim()
        if (role != "assistant" || content.length > MAX_MESSAGE_CHARACTERS) {
            throw SourceApiException("invalid_response", "Noden skickade ett ogiltigt AI-svar.")
        }
        ChatMessage.assistant(content)
    }

    suspend fun uploadConversationSnapshot(
        apiBaseUrl: String,
        trusted: TrustedNode,
        snapshotId: String,
        snapshot: ByteArray,
    ) = withContext(Dispatchers.IO) {
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/storage/$CHAT_STORAGE_APP/snapshots/$snapshotId",
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        try {
            connection.requestMethod = "PUT"
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Content-SHA256", sha256Hex(snapshot))
            connection.outputStream.use { it.write(snapshot) }
            val status = connection.responseCode
            if (status !in 200..299) throw apiError(connection, status)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun latestConversationSnapshot(
        apiBaseUrl: String,
        trusted: TrustedNode,
    ): ByteArray? = withContext(Dispatchers.IO) {
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/storage/$CHAT_STORAGE_APP/snapshots/latest",
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        try {
            connection.requestMethod = "GET"
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/octet-stream")
            val status = connection.responseCode
            when {
                status == 404 -> null
                status !in 200..299 -> throw apiError(connection, status)
                else -> connection.inputStream.use { it.readBytes() }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(
        url: String,
        body: JSONObject,
        tlsCaCertificate: String,
        credential: String? = null,
        readTimeoutMillis: Int = NETWORK_TIMEOUT_MILLIS,
    ): JSONObject {
        val connection = openConnection(url, tlsCaCertificate, credential)
        return try {
            connection.requestMethod = "POST"
            connection.readTimeout = readTimeoutMillis
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val raw = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val response = runCatching { JSONObject(raw) }.getOrElse {
                throw SourceApiException("invalid_response", "Noden skickade ett ogiltigt svar.")
            }
            if (status !in 200..299) {
                val error = response.optJSONObject("error")
                throw SourceApiException(error?.optString("code").orEmpty().ifBlank { "http_$status" }, mapError(error?.optString("code")))
            }
            response
        } catch (error: SSLException) {
            throw SourceApiException(
                "tls_identity_mismatch",
                "Nodens HTTPS-identitet stämmer inte med QR-koden.",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnection(
        url: String,
        tlsCaCertificate: String,
        credential: String? = null,
    ): HttpsURLConnection {
        val connection = (URL(url).openConnection() as? HttpsURLConnection)
            ?: throw SourceApiException("https_required", "Source Node måste använda HTTPS.")
        connection.sslSocketFactory = sslSocketFactory(tlsCaCertificate)
        connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
        connection.useCaches = false
        credential?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
        return connection
    }

    private fun apiError(connection: HttpsURLConnection, status: Int): SourceApiException {
        val raw = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        val code = runCatching { JSONObject(raw).optJSONObject("error")?.optString("code") }.getOrNull()
        return SourceApiException(code.orEmpty().ifBlank { "http_$status" }, mapError(code))
    }

    private fun sha256Hex(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun sslSocketFactory(encodedCaCertificate: String): SSLSocketFactory {
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(SourceCrypto.base64UrlDecode(encodedCaCertificate)),
            ) as X509Certificate
        }.getOrElse { throw SourceApiException("invalid_ca_certificate", "QR-kodens CA-certifikat är ogiltigt.") }
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("source-node-ca", certificate)
        }
        val trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore)
        }.trustManagers
        return SSLContext.getInstance("TLS").apply { init(null, trustManagers, null) }.socketFactory
    }

    private fun mapError(code: String?): String = when (code) {
        "pairing_unavailable" -> "Inbjudan har gått ut, avbrutits eller redan använts."
        "duplicate_client" -> "Den här klientidentiteten är redan parkopplad."
        "pairing_proof_failed" -> "Klientens identitet kunde inte verifieras."
        "invalid_recovery_key" -> "Återställningsnyckeln är fel."
        "recovery_not_configured" -> "Användaren har ingen återställningsnyckel."
        "authentication_required" -> "Noden känner inte längre igen den här klienten."
        "model_unavailable" -> "Nodens lokala AI-modell är inte tillgänglig."
        "chat_rate_limited" -> "För många AI-frågor. Vänta en stund."
        "storage_quota_exceeded" -> "Nodens lagringsutrymme för användaren är fullt."
        else -> "Noden kunde inte slutföra begäran."
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf { it.isNotBlank() }
            ?: throw SourceApiException("invalid_response", "Noden skickade ett ofullständigt svar.")

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 8_000
        const val CHAT_TIMEOUT_MILLIS = 125_000
        const val MAX_MESSAGE_CHARACTERS = 4_000
        const val CHAT_STORAGE_APP = "source-client"
    }
}
