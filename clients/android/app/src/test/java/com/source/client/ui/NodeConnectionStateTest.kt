package com.source.client.ui

import com.source.client.model.ConnectedNode
import com.source.client.model.DiscoveredNode
import com.source.client.model.LocalIdentity
import com.source.client.model.NodeConnectionState
import com.source.client.model.TrustedNode
import com.source.client.model.UnlockedVault
import com.source.client.model.bindAuthoritativeNode
import com.source.client.ai.SourceAiAvailability
import com.source.client.ai.SourceAiCapabilities
import com.source.client.ai.SourceAiCapability
import com.source.client.ai.SourceAiRuntimeState
import com.source.client.model.AiModelMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NodeConnectionStateTest {
    private val discovered = DiscoveredNode("service", "node", "Node", "https://node/api/v1")
    private val trusted = TrustedNode("node", "key", "ca", "Node", "credential", "user", "client")

    @Test
    fun `connected node exists only in the connected state`() {
        val connected = ConnectedNode(discovered, trusted, readyAi())
        val degraded = connected.copy(
            aiRuntimeState = SourceAiRuntimeState(SourceAiAvailability.MODEL_NOT_INSTALLED),
        )

        assertEquals(connected, NodeConnectionState.Ready(connected).connectedNodeOrNull())
        assertEquals(degraded, NodeConnectionState.Degraded(
            degraded,
            com.source.client.model.NodeDegradedReason.AI_UNAVAILABLE,
        ).connectedNodeOrNull())
        assertNull(NodeConnectionState.Discovering.connectedNodeOrNull())
        assertNull(NodeConnectionState.Authenticating(discovered, trusted, 0).connectedNodeOrNull())
        assertTrue(NodeConnectionState.Ready(connected).nodeStorageUsable())
        assertTrue(NodeConnectionState.Ready(connected).nodeAiUsable())
        assertTrue(NodeConnectionState.Degraded(
            degraded,
            com.source.client.model.NodeDegradedReason.AI_UNAVAILABLE,
        ).nodeStorageUsable())
        assertTrue(!NodeConnectionState.Degraded(
            degraded,
            com.source.client.model.NodeDegradedReason.AI_UNAVAILABLE,
        ).nodeAiUsable())
    }

    @Test
    fun `authentication state identifies its discovery endpoint explicitly`() {
        val state = NodeConnectionState.Authenticating(discovered, trusted, 2)

        assertTrue(state.isConnectingTo(discovered))
        assertEquals(2, state.attempt)

        val retry = NodeConnectionState.Retrying(
            discovered,
            trusted,
            attempt = 3,
            retryInMillis = 4_000,
            reason = com.source.client.model.NodeDisconnectReason.AUTHENTICATION_FAILED,
        )
        assertTrue(retry.isConnectingTo(discovered))
        assertEquals(4_000, retry.retryInMillis)
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 16_000L),
            (0..5).map(::reconnectDelayMillis))
    }

    @Test
    fun `automatic reconnect ignores discovery order and selects only the authoritative node`() {
        val other = discovered.copy(serviceName = "other", nodeIdHint = "other", displayName = "Other")

        val candidate = trusted.automaticConnectionCandidate(listOf(other, discovered))

        assertEquals(discovered, candidate?.first)
        assertEquals(trusted, candidate?.second)
        assertNull(trusted.automaticConnectionCandidate(listOf(other)))
    }

    @Test
    fun `unpaired profiles expose every discovered node without selecting one automatically`() {
        val other = discovered.copy(serviceName = "other", nodeIdHint = "other", displayName = "Other")
        val discoveries = listOf(other, discovered)
        val authority: TrustedNode? = null

        assertNull(authority.automaticConnectionCandidate(discoveries))
        assertEquals(discoveries, authority.manuallySelectableNodes(discoveries))
        assertEquals(discoveries, NodeConnectionState.Found(discoveries).selectableNodes())
    }

    @Test
    fun `binding cannot silently move an authoritative profile to another node`() {
        val identity = LocalIdentity("user", "User", "client", "Client", "public", "private")
        val vault = UnlockedVault(identity).bindAuthoritativeNode(trusted)

        assertThrows(IllegalArgumentException::class.java) {
            vault.bindAuthoritativeNode(trusted.copy(nodeId = "other"))
        }
        assertEquals(trusted.copy(displayName = "Renamed"), vault.bindAuthoritativeNode(
            trusted.copy(displayName = "Renamed"),
        ).authoritativeNode)
    }

    private fun readyAi() = SourceAiRuntimeState(
        SourceAiAvailability.READY,
        AiModelMetadata("model", 1),
        SourceAiCapabilities(setOf(SourceAiCapability.TEXT), true, true, 1_024),
    )
}
