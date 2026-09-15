package com.source.client.protocol

import com.source.client.model.LocalIdentity
import com.source.client.model.AiModelMetadata
import com.source.client.model.PairingInvitation
import com.source.client.model.PairingResult
import com.source.client.model.TrustedNode
import com.source.client.ai.SOURCE_AI_CONTRACT_VERSION
import com.source.client.ai.SourceAiEvent
import com.source.client.ai.SourceAiRequest
import com.source.client.security.SourceCrypto
import com.source.client.storage.EncryptedUploadPayload
import com.source.client.storage.LibraryItem
import com.source.client.storage.SOURCE_STORAGE_CONTRACT_VERSION
import com.source.client.storage.StorageChanges
import com.source.client.storage.StorageChange
import com.source.client.storage.StorageCommitReceipt
import com.source.client.storage.StorageCursor
import com.source.client.storage.StorageMutation
import com.source.client.storage.StorageRevision
import com.source.client.storage.toJson
import com.source.client.storage.toStorageCursor
import com.source.client.storage.toStorageReceipt
import com.source.client.storage.toStorageRevision
import com.source.client.knowledge.BronzeTextSource
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

    suspend fun availableAiModel(apiBaseUrl: String, trusted: TrustedNode): AiModelMetadata? =
        withContext(Dispatchers.IO) {
            val connection = openConnection(
                "${apiBaseUrl.removeSuffix("/")}/status",
                trusted.tlsCaCertificate,
                trusted.clientCredential,
            )
            try {
                connection.requestMethod = "GET"
                connection.readTimeout = NETWORK_TIMEOUT_MILLIS
                connection.setRequestProperty("Accept", "application/json")
                val status = connection.responseCode
                if (status !in 200..299) throw apiError(connection, status)
                val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
                if (!response.optBoolean("llmAvailable", false) || response.isNull("ai")) return@withContext null
                response.getJSONObject("ai").let { ai ->
                    AiModelMetadata(
                        modelId = ai.requiredString("modelId"),
                        parameterCount = ai.getLong("parameterCount"),
                    )
                }
            } finally {
                connection.disconnect()
            }
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
                    put("workload", request.workload.name.lowercase())
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
                            "started" -> SourceAiEvent.Started(
                                eventRunId,
                                runCatching {
                                    AiModelMetadata(
                                        modelId = event.requiredString("modelId"),
                                        parameterCount = event.getLong("parameterCount"),
                                    )
                                }.getOrNull(),
                            )
                            "delta" -> SourceAiEvent.Delta(
                                eventRunId,
                                event.getLong("sequence"),
                                requiredAiDeltaText(event.opt("text")),
                            )
                            "completed" -> SourceAiEvent.Completed(
                                eventRunId,
                                event.requiredString("finishReason"),
                                inputTokens = event.optInt("inputTokens").takeIf { event.has("inputTokens") },
                                outputTokens = event.optInt("outputTokens").takeIf { event.has("outputTokens") },
                                reasoningBytes = event.optInt("reasoningBytes").takeIf { event.has("reasoningBytes") },
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

    suspend fun commitStorageMutation(
        apiBaseUrl: String,
        trusted: TrustedNode,
        mutation: StorageMutation,
    ): Pair<StorageCommitReceipt, List<StorageRevision>> = withContext(Dispatchers.IO) {
        val revision = mutation.revision
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/sync/mutations/${mutation.operationId}",
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        try {
            connection.requestMethod = "POST"
            connection.readTimeout = LIBRARY_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(mutation.payloadBytes.size)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Source-Contract-Version", SOURCE_STORAGE_CONTRACT_VERSION.toString())
            connection.setRequestProperty("X-Source-Origin-Epoch", mutation.originEpoch)
            connection.setRequestProperty("X-Source-Origin-Sequence", mutation.originSequence.toString())
            mutation.expectedAuthorityEpoch?.let {
                connection.setRequestProperty("X-Source-Authority-Epoch", it)
            }
            connection.setRequestProperty("X-Source-Collection", revision.objectKey.collection)
            connection.setRequestProperty("X-Source-Object-Id", revision.objectKey.objectId)
            connection.setRequestProperty("X-Source-Revision-Id", revision.revisionId)
            connection.setRequestProperty("X-Source-Revision-Kind", revision.kind)
            if (revision.parentRevisionIds.isNotEmpty()) {
                connection.setRequestProperty("X-Source-Parent-Revisions", revision.parentRevisionIds.joinToString(","))
            }
            revision.createdAtMillis?.let {
                connection.setRequestProperty("X-Source-Created-At-Millis", it.toString())
            }
            revision.payload?.let { payload ->
                connection.setRequestProperty("X-Source-Payload-Format", payload.format)
                connection.setRequestProperty("X-Source-Payload-Format-Version", payload.formatVersion.toString())
                connection.setRequestProperty("X-Source-Byte-Count", payload.byteCount.toString())
                connection.setRequestProperty("X-Source-Plaintext-SHA256", payload.plaintextSha256)
            }
            connection.outputStream.use { it.write(mutation.payloadBytes) }
            val status = connection.responseCode
            if (status !in 200..299) throw apiError(connection, status)
            val response = connection.inputStream.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            if (response.getInt("contractVersion") != SOURCE_STORAGE_CONTRACT_VERSION) {
                throw SourceApiException("unsupported_storage_contract", "The Node returned an unsupported storage contract.")
            }
            val heads = response.getJSONArray("heads")
            response.getJSONObject("receipt").toStorageReceipt() to
                List(heads.length()) { heads.getJSONObject(it).toStorageRevision() }
        } finally {
            connection.disconnect()
        }
    }

    /** Sends plaintext Bronze to the trusted authoritative Node for durable refinement. */
    suspend fun refineSilver(
        apiBaseUrl: String,
        trusted: TrustedNode,
        source: BronzeTextSource,
        operationId: String,
    ) = withContext(Dispatchers.IO) {
        val response = postJson(
            "${apiBaseUrl.removeSuffix("/")}/silver/refinements",
            JSONObject().apply {
                put("contractVersion", SOURCE_STORAGE_CONTRACT_VERSION)
                put("operationId", operationId)
                put("source", JSONObject().apply {
                    put("id", source.id)
                    put("name", source.name)
                    put("sourceType", source.sourceType)
                    put("contentSha256", source.contentSha256)
                    put("text", source.text)
                })
            },
            trusted.tlsCaCertificate,
            trusted.clientCredential,
            LIBRARY_TIMEOUT_MILLIS,
        )
        if (!response.has("bronzeAccepted") || !response.has("refined")) {
            throw SourceApiException("invalid_response", "The Node returned an incomplete Silver result.")
        }
    }

    suspend fun removeSilver(
        apiBaseUrl: String,
        trusted: TrustedNode,
        sourceId: String,
        operationId: String,
    ) = withContext(Dispatchers.IO) {
        val response = postJson(
            "${apiBaseUrl.removeSuffix("/")}/silver/removals",
            JSONObject()
                .put("contractVersion", SOURCE_STORAGE_CONTRACT_VERSION)
                .put("operationId", operationId)
                .put("sourceId", sourceId),
            trusted.tlsCaCertificate,
            trusted.clientCredential,
            LIBRARY_TIMEOUT_MILLIS,
        )
        if (!response.has("bronzeRemoved") || !response.has("silverChanged")) {
            throw SourceApiException("invalid_response", "The Node returned an incomplete Silver removal result.")
        }
    }

    suspend fun storageChanges(
        apiBaseUrl: String,
        trusted: TrustedNode,
        collection: String,
        objectId: String,
        cursor: StorageCursor?,
    ): StorageChanges = withContext(Dispatchers.IO) {
        val response = postJson(
            "${apiBaseUrl.removeSuffix("/")}/sync/changes",
            JSONObject().apply {
                put("contractVersion", SOURCE_STORAGE_CONTRACT_VERSION)
                put("collection", collection)
                put("objectId", objectId)
                cursor?.let { put("cursor", it.toJson()) }
            },
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        if (response.getInt("contractVersion") != SOURCE_STORAGE_CONTRACT_VERSION) {
            throw SourceApiException("unsupported_storage_contract", "The Node returned an unsupported storage contract.")
        }
        val rawChanges = response.getJSONArray("changes")
        val rawHeads = response.getJSONArray("heads")
        StorageChanges(
            cursor = response.getJSONObject("cursor").toStorageCursor(),
            requiresManifest = response.getBoolean("requiresManifest"),
            changes = List(rawChanges.length()) { index ->
                rawChanges.getJSONObject(index).let {
                    StorageChange(it.getJSONObject("receipt").toStorageReceipt(), it.getJSONObject("revision").toStorageRevision())
                }
            },
            heads = List(rawHeads.length()) { rawHeads.getJSONObject(it).toStorageRevision() },
        )
    }

    suspend fun storagePayload(
        apiBaseUrl: String,
        trusted: TrustedNode,
        revision: StorageRevision,
    ): ByteArray = withContext(Dispatchers.IO) {
        val descriptor = revision.payload
            ?: throw SourceApiException("payload_not_found", "The storage revision has no payload.")
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/sync/revisions/${revision.revisionId}/payload",
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        try {
            connection.requestMethod = "GET"
            connection.readTimeout = LIBRARY_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/octet-stream")
            val status = connection.responseCode
            if (status !in 200..299) throw apiError(connection, status)
            val body = connection.inputStream.use { it.readBytes() }
            if (body.size.toLong() != descriptor.byteCount || sha256Hex(body) != descriptor.plaintextSha256) {
                throw SourceApiException("payload_hash_mismatch", "The Node returned corrupt storage payload bytes.")
            }
            body
        } finally {
            connection.disconnect()
        }
    }

    suspend fun acknowledgeStorageCursor(
        apiBaseUrl: String,
        trusted: TrustedNode,
        collection: String,
        objectId: String,
        cursor: StorageCursor,
    ) = withContext(Dispatchers.IO) {
        postJson(
            "${apiBaseUrl.removeSuffix("/")}/sync/ack",
            JSONObject()
                .put("contractVersion", SOURCE_STORAGE_CONTRACT_VERSION)
                .put("collection", collection)
                .put("objectId", objectId)
                .put("cursor", cursor.toJson()),
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
    }

    suspend fun uploadLibraryItem(
        apiBaseUrl: String,
        trusted: TrustedNode,
        item: LibraryItem,
        payload: EncryptedUploadPayload,
    ) = withContext(Dispatchers.IO) {
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/library/items/${item.id}",
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        try {
            connection.requestMethod = "PUT"
            connection.readTimeout = LIBRARY_TIMEOUT_MILLIS
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(payload.byteCount)
            connection.setRequestProperty("Content-Type", "application/octet-stream")
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("X-Source-Content-SHA256", item.contentSha256)
            connection.setRequestProperty("X-Content-SHA256", payload.encryptedSha256)
            connection.outputStream.use { output ->
                payload.file.inputStream().buffered().use { input -> input.copyTo(output, LIBRARY_BUFFER_BYTES) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw apiError(connection, status)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun deleteLibraryItem(
        apiBaseUrl: String,
        trusted: TrustedNode,
        itemId: String,
        contentSha256: String,
    ) = withContext(Dispatchers.IO) {
        val connection = openConnection(
            "${apiBaseUrl.removeSuffix("/")}/library/items/$itemId",
            trusted.tlsCaCertificate,
            trusted.clientCredential,
        )
        try {
            connection.requestMethod = "DELETE"
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.setRequestProperty("X-Source-Content-SHA256", contentSha256)
            val status = connection.responseCode
            if (status !in 200..299) throw apiError(connection, status)
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
        "silver_model_unavailable", "silver_refinement_failed" ->
            "The Node could not refine knowledge from this source."
        "chat_rate_limited" -> "Too many AI requests. Wait a moment."
        "background_ai_rate_limited" -> "Too many background AI requests. Wait a moment."
        "storage_quota_exceeded" -> "The user storage space on the Node is full."
        "silver_source_too_large" -> "This source is too large for one automatic knowledge refinement."
        "library_item_deleted" -> "The Node has already recorded this item as deleted."
        "library_item_identity_conflict", "library_content_exists" -> "The Node rejected a conflicting Library item."
        else -> "The Node could not complete the request."
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf { it.isNotBlank() }
            ?: throw SourceApiException("invalid_response", "The Node returned an incomplete response.")

    private companion object {
        const val NETWORK_TIMEOUT_MILLIS = 8_000
        const val CHAT_TIMEOUT_MILLIS = 310_000
        const val LIBRARY_TIMEOUT_MILLIS = 10 * 60_000
        const val LIBRARY_BUFFER_BYTES = 64 * 1024
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
