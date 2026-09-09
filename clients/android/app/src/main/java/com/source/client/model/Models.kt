package com.source.client.model

import java.util.UUID

data class LocalIdentity(
    val userId: String,
    val userDisplayName: String,
    val clientId: String,
    val clientDisplayName: String,
    val clientPublicKey: String,
    val clientPrivateKey: String,
)

data class TrustedNode(
    val nodeId: String,
    val nodePublicKey: String,
    val tlsCaCertificate: String,
    val displayName: String,
    val clientCredential: String,
    val userId: String,
    val clientId: String,
)

data class UnlockedVault(
    val identity: LocalIdentity,
    val trustedNodes: List<TrustedNode>,
)

enum class ChatRole(val apiValue: String) {
    USER("user"),
    ASSISTANT("assistant");

    override fun toString(): String = apiValue

    companion object {
        fun fromApiValue(value: String): ChatRole = entries.firstOrNull { it.apiValue == value }
            ?: throw IllegalArgumentException("Unsupported chat role")
    }
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val content: String,
    val createdAtMillis: Long = System.currentTimeMillis(),
) {
    companion object {
        fun user(content: String): ChatMessage = ChatMessage(role = ChatRole.USER, content = content)
        fun assistant(content: String): ChatMessage = ChatMessage(role = ChatRole.ASSISTANT, content = content)
    }
}

enum class AiSelection {
    AUTO,
    THIS_DEVICE,
    NODE,
}

data class DiscoveredNode(
    val serviceName: String,
    val nodeIdHint: String,
    val displayName: String,
    val apiBaseUrl: String,
)

data class PairingInvitation(
    val protocol: Int,
    val nodeId: String,
    val nodePublicKey: String,
    val tlsCaCertificate: String,
    val nodeName: String,
    val pairingEndpoint: String,
    val invitationId: String,
    val invitationSecret: String,
    val expiresAtMillis: Long,
)

sealed interface NodeStatus {
    data object Searching : NodeStatus
    data object NoneFound : NodeStatus
    data class Found(val nodes: List<DiscoveredNode>) : NodeStatus
    data class Connecting(val name: String) : NodeStatus
    data class Connected(val node: TrustedNode) : NodeStatus
    data class PairedOffline(val node: TrustedNode) : NodeStatus
    data class Error(val message: String, val canRetry: Boolean = true) : NodeStatus
}
