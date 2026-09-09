package com.source.client.protocol

import com.source.client.model.LocalIdentity
import com.source.client.model.PairingInvitation
import com.source.client.model.TrustedNode
import com.source.client.security.SourceCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.SecureRandom
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException

class SourceApiException(val code: String, message: String) : IOException(message)

class SourceNodeApi {
    private val random = SecureRandom()

    suspend fun pair(invitation: PairingInvitation, identity: LocalIdentity): TrustedNode = withContext(Dispatchers.IO) {
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
            },
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
        TrustedNode(
            nodeId = invitation.nodeId,
            nodePublicKey = invitation.nodePublicKey,
            displayName = invitation.nodeName,
            clientCredential = credential,
            userId = returnedUserId,
            clientId = returnedClientId,
        )
    }

    suspend fun authenticate(apiBaseUrl: String, trusted: TrustedNode): TrustedNode = withContext(Dispatchers.IO) {
        val nonce = SourceCrypto.base64Url(ByteArray(32).also(random::nextBytes))
        val proof = postJson(
            "${apiBaseUrl.removeSuffix("/")}/identity/challenge",
            JSONObject().put("protocol", 1).put("nonce", nonce),
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

    private fun postJson(url: String, body: JSONObject, credential: String? = null): JSONObject {
        val connection = (URL(url).openConnection() as? HttpsURLConnection)
            ?: throw SourceApiException("https_required", "Source Node måste använda HTTPS.")
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            credential?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
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
        } catch (error: SSLHandshakeException) {
            throw SourceApiException(
                "tls_untrusted",
                "Nodens lokala certifikat är inte betrott. Installera Source Node-CA på enheten och försök igen.",
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun mapError(code: String?): String = when (code) {
        "pairing_unavailable" -> "Inbjudan har gått ut, avbrutits eller redan använts."
        "duplicate_client" -> "Den här klientidentiteten är redan parkopplad."
        "pairing_proof_failed" -> "Klientens identitet kunde inte verifieras."
        "authentication_required" -> "Noden känner inte längre igen den här klienten."
        else -> "Noden kunde inte slutföra begäran."
    }

    private fun JSONObject.requiredString(name: String): String =
        optString(name).takeIf { it.isNotBlank() }
            ?: throw SourceApiException("invalid_response", "Noden skickade ett ofullständigt svar.")
}
