package com.source.client.protocol

import com.source.client.model.LocalIdentity
import com.source.client.model.PairingInvitation
import com.source.client.model.PairingResult
import com.source.client.model.TrustedNode
import com.source.client.ai.SOURCE_AI_CONTRACT_VERSION
import com.source.client.ai.SourceAiEvent
import com.source.client.ai.SourceAiRequest
import com.source.client.security.SourceCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
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

class SourceApiException(
    val code: String,
    message: String,
    val responseStarted: Boolean = false,
) : IOException(message)

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
        if (start.getInt("protocol") != 1) throw SourceApiException("unsupported_pairing_protocol", "The pairing protocol is not supported.")
        val handshakeId = start.requiredString("handshakeId")
        val challenge = start.requiredString("challenge")
        val signingPayload = start.requiredString("signingPayload")
        val nodeSignature = start.requiredString("nodeSignature")
        if (runCatching { java.util.UUID.fromString(handshakeId) }.isFailure ||
            !challenge.matches(Regex("^[A-Za-z0-9_-]{43}$")) ||
            !nodeSignature.matches(Regex("^[A-Za-z0-9_-]{80,100}$"))
        ) throw SourceApiException("invalid_challenge", "The Node returned an invalid response.")
        val expires = runCatching { java.time.Instant.parse(start.requiredString("expiresAt")).toEpochMilli() }.getOrNull()
            ?: throw SourceApiException("invalid_challenge", "The Node returned an invalid response.")
        if (expires <= System.currentTimeMillis() || expires > invitation.expiresAtMillis) {
            throw SourceApiException("expired_challenge", "The pairing attempt has expired.")
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
        ) throw SourceApiException("node_proof_failed", "The cryptographic identity of the Node could not be verified.")

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
            throw SourceApiException("node_identity_changed", "The Node identity changed during pairing.")
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
            throw SourceApiException("client_identity_changed", "The Node returned the wrong client identity.")
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
        ) throw SourceApiException("invalid_response", "The Node returned an invalid response.")
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
        if (!valid) throw SourceApiException("node_proof_failed", "The Node identity could not be verified.")
        trusted.copy(displayName = displayName)
    }

    fun streamAi(
        apiBaseUrl: String,
        trusted: TrustedNode,
        request: SourceAiRequest,
    ): Flow<SourceAiEvent> = channelFlow {
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/ai/stream",
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        val worker = launch(Dispatchers.IO) {
            try {
                connection.requestMethod = "POST"
                connection.readTimeout = CHAT_TIMEOUT_MILLIS
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/x-ndjson")
                val body = JSONObject().apply {
                    put("contractVersion", SOURCE_AI_CONTRACT_VERSION)
                    put("runId", request.runId)
                    put("conversationId", request.conversationId)
                    put("messages", JSONArray().apply {
                        request.messages.forEach { message ->
                            put(JSONObject().apply {
                                put("role", message.role.name.lowercase())
                                put("content", JSONArray().apply {
                                    message.content.forEach { part ->
                                        when (part) {
                                            is com.source.client.ai.SourceAiContent.Text ->
                                                put(JSONObject().put("type", "text").put("text", part.text))
                                            is com.source.client.ai.SourceAiContent.Image ->
                                                put(JSONObject().put("type", "image").put("uri", part.uri).apply {
                                                    part.mimeType?.let { put("mimeType", it) }
                                                })
                                        }
                                    }
                                })
                            })
                        }
                    })
                }
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status !in 200..299) throw apiError(connection, status)
                connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    lines.filter(String::isNotBlank).forEach { raw ->
                        val event = runCatching { JSONObject(raw) }.getOrElse {
                            throw SourceApiException("invalid_response", "The Node returned invalid AI streaming data.")
                        }
                        val eventRunId = event.optString("runId")
                        if (eventRunId != request.runId) {
                            throw SourceApiException("invalid_response", "The Node returned the wrong run identifier.")
                        }
                        trySend(when (event.optString("type")) {
                            "started" -> SourceAiEvent.Started(eventRunId)
                            "delta" -> SourceAiEvent.Delta(
                                eventRunId,
                                event.getLong("sequence"),
                                requiredAiDeltaText(event.opt("text")),
                            )
                            "completed" -> SourceAiEvent.Completed(
                                eventRunId,
                                event.requiredString("finishReason"),
                            )
                            "failed" -> SourceAiEvent.Failed(
                                eventRunId,
                                event.requiredString("code"),
                                event.optBoolean("retryable", false),
                            )
                            else -> throw SourceApiException("invalid_response", "The Node returned an unknown AI event.")
                        }).getOrThrow()
                    }
                }
            } catch (error: SSLException) {
                throw SourceApiException("tls_identity_mismatch", "The HTTPS identity of the Node does not match the QR code.")
            } finally {
                connection.disconnect()
            }
        }
        worker.invokeOnCompletion { cause -> close(cause) }
        awaitClose {
            connection.disconnect()
            worker.cancel()
        }
    }

    suspend fun uploadSnapshot(
        apiBaseUrl: String,
        trusted: TrustedNode,
        appId: String,
        snapshotId: String,
        snapshot: ByteArray,
    ) = withContext(Dispatchers.IO) {
        requireStorageAppId(appId)
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/storage/$appId/snapshots/$snapshotId",
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

    suspend fun latestSnapshot(
        apiBaseUrl: String,
        trusted: TrustedNode,
        appId: String,
    ): ByteArray? = withContext(Dispatchers.IO) {
        requireStorageAppId(appId)
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/storage/$appId/snapshots/latest",
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
                throw SourceApiException("invalid_response", "The Node returned an invalid response.")
            }
            if (status !in 200..299) {
                val error = response.optJSONObject("error")
                throw SourceApiException(error?.optString("code").orEmpty().ifBlank { "http_$status" }, mapError(error?.optString("code")))
            }
            response
        } catch (error: SSLException) {
            throw SourceApiException(
                "tls_identity_mismatch",
                "The HTTPS identity of the Node does not match the QR code.",
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
            ?: throw SourceApiException("https_required", "Source Node must use HTTPS.")
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

    private fun requireStorageAppId(appId: String) {
        require(STORAGE_APP_PATTERN.matches(appId)) { "Invalid storage application identifier" }
    }

    private fun sslSocketFactory(encodedCaCertificate: String): SSLSocketFactory {
        val certificate = runCatching {
            CertificateFactory.getInstance("X.509").generateCertificate(
                ByteArrayInputStream(SourceCrypto.base64UrlDecode(encodedCaCertificate)),
            ) as X509Certificate
        }.getOrElse { throw SourceApiException("invalid_ca_certificate", "The CA certificate in the QR code is invalid.") }
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
        "pairing_unavailable" -> "The invitation has expired, was canceled, or has already been used."
        "duplicate_client" -> "This client identity is already paired."
        "pairing_proof_failed" -> "The client identity could not be verified."
        "invalid_recovery_key" -> "The recovery key is incorrect."
        "recovery_not_configured" -> "The user does not have a recovery key."
        "authentication_required" -> "The Node no longer recognizes this client."
        "model_unavailable" -> "The local AI model on the Node is unavailable."
        "chat_rate_limited" -> "Too many AI requests. Wait a moment."
        "storage_quota_exceeded" -> "The user storage space on the Node is full."
        else -> "The Node could not complete the request."
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf { it.isNotBlank() }
            ?: throw SourceApiException("invalid_response", "The Node returned an incomplete response.")

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 8_000
        const val CHAT_TIMEOUT_MILLIS = 310_000
        val STORAGE_APP_PATTERN = Regex("^[a-z][a-z0-9-]{1,31}$")
    }
}

internal fun requiredAiDeltaText(value: Any?): String {
    val text = value as? String
    if (text.isNullOrEmpty()) {
        throw SourceApiException("invalid_response", "The Node returned invalid AI streaming data.")
    }
    return text
}
