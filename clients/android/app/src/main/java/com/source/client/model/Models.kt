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
    val recoveryKey: String? = null,
    val dataKey: String? = null,
    val recoverySetupPending: Boolean = false,
)

data class PairingResult(
    val trustedNode: TrustedNode,
    val recoveryEnvelope: String? = null,
)

sealed interface ProfileNodeAuthority {
    data object Unpaired : ProfileNodeAuthority
    data class Authoritative(val node: TrustedNode) : ProfileNodeAuthority
    data class AmbiguousLegacy(val nodes: List<TrustedNode>) : ProfileNodeAuthority {
        init {
            require(nodes.map(TrustedNode::nodeId).distinct().size > 1) {
                "Ambiguous Node authority requires multiple distinct Node identities"
            }
        }
    }
}

data class UnlockedVault(
    val identity: LocalIdentity,
    val nodeAuthority: ProfileNodeAuthority = ProfileNodeAuthority.Unpaired,
)

val UnlockedVault.authoritativeNode: TrustedNode?
    get() = (nodeAuthority as? ProfileNodeAuthority.Authoritative)?.node

fun UnlockedVault.bindAuthoritativeNode(node: TrustedNode): UnlockedVault {
    val canBind = when (val authority = nodeAuthority) {
        ProfileNodeAuthority.Unpaired -> true
        is ProfileNodeAuthority.Authoritative -> authority.node.nodeId == node.nodeId
        is ProfileNodeAuthority.AmbiguousLegacy -> authority.nodes.any { it.nodeId == node.nodeId }
    }
    require(canBind) { "Moving a profile to another Node requires an explicit migration" }
    return copy(nodeAuthority = ProfileNodeAuthority.Authoritative(node))
}

data class VaultProfile(
    val id: String,
    val displayName: String,
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

data class ChatConversation(
    val id: String = UUID.randomUUID().toString(),
    val createdAtMillis: Long = System.currentTimeMillis(),
    val messages: List<ChatMessage> = emptyList(),
)

data class ChatConversationTombstone(
    val conversationId: String,
    val deletedAtMillis: Long,
)

data class ChatConversations(
    val conversations: List<ChatConversation> = emptyList(),
    val activeConversationId: String? = null,
    val tombstones: List<ChatConversationTombstone> = emptyList(),
) {
    val activeConversation: ChatConversation?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    fun withFreshConversation(conversation: ChatConversation = ChatConversation()): ChatConversations = copy(
        conversations = conversations + conversation,
        activeConversationId = conversation.id,
    )

    fun replaceActive(conversation: ChatConversation): ChatConversations = copy(
        conversations = conversations.map { if (it.id == conversation.id) conversation else it },
    )
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

data class ConnectedNode(
    val discovered: DiscoveredNode,
    val trusted: TrustedNode,
    val aiModel: AiModelMetadata? = null,
)

data class AiModelMetadata(
    val modelId: String,
    val parameterCount: Long,
) {
    init {
        require(modelId.isNotBlank() && modelId.length <= 200) { "Invalid AI model identifier" }
        require(parameterCount > 0) { "AI model parameter count must be positive" }
    }
}

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
    val recovery: Boolean = false,
)

enum class NodeDisconnectReason { BACKGROUND, NETWORK_UNAVAILABLE, NOT_FOUND, LOST, AUTHENTICATION_FAILED }

enum class NodeRecoveryPhase { REPLACING_CLIENT, CONFIGURING_DATA_KEY }

sealed interface NodeConnectionState {
    data object Discovering : NodeConnectionState
    data class Found(val nodes: List<DiscoveredNode>) : NodeConnectionState
    data class Pairing(val node: DiscoveredNode, val name: String) : NodeConnectionState
    data class Recovering(
        val node: DiscoveredNode,
        val name: String,
        val phase: NodeRecoveryPhase,
    ) : NodeConnectionState
    data class Authenticating(
        val node: DiscoveredNode,
        val trusted: TrustedNode,
        val attempt: Int,
    ) : NodeConnectionState
    data class Connected(val connection: ConnectedNode) : NodeConnectionState
    data class Disconnected(
        val trusted: TrustedNode?,
        val reason: NodeDisconnectReason,
    ) : NodeConnectionState
    data class Failed(val message: String, val canRetry: Boolean = true) : NodeConnectionState
}
