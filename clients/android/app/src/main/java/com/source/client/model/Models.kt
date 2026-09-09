package com.source.client.model

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
    val displayName: String,
    val clientCredential: String,
    val userId: String,
    val clientId: String,
)

data class UnlockedVault(
    val identity: LocalIdentity,
    val trustedNodes: List<TrustedNode>,
)

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
