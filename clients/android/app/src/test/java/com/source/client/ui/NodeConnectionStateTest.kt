package com.source.client.ui

import com.source.client.model.ConnectedNode
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeConnectionState
import com.source.client.model.ProfileNodeAuthority
import com.source.client.model.TrustedNode
import com.source.client.model.UnlockedVault
import com.source.client.model.LocalIdentity
import com.source.client.model.bindAuthoritativeNode
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
        val connected = ConnectedNode(discovered, trusted)

        assertEquals(connected, NodeConnectionState.Connected(connected).connectedNodeOrNull())
        assertNull(NodeConnectionState.Discovering.connectedNodeOrNull())
        assertNull(NodeConnectionState.Authenticating(discovered, trusted, 0).connectedNodeOrNull())
    }

    @Test
    fun `authentication state identifies its discovery endpoint explicitly`() {
        val state = NodeConnectionState.Authenticating(discovered, trusted, 2)

        assertTrue(state.isAuthenticating(discovered))
        assertEquals(2, state.attempt)
    }

    @Test
    fun `automatic reconnect ignores discovery order and selects only the authoritative node`() {
        val other = discovered.copy(serviceName = "other", nodeIdHint = "other", displayName = "Other")
        val authority = ProfileNodeAuthority.Authoritative(trusted)

        val candidate = authority.automaticConnectionCandidate(listOf(other, discovered))

        assertEquals(discovered, candidate?.first)
        assertEquals(trusted, candidate?.second)
        assertNull(authority.automaticConnectionCandidate(listOf(other)))
    }

    @Test
    fun `ambiguous legacy trust never produces an automatic reconnect candidate`() {
        val otherTrusted = trusted.copy(nodeId = "other", displayName = "Other")
        val authority = ProfileNodeAuthority.AmbiguousLegacy(listOf(otherTrusted, trusted))

        assertNull(authority.automaticConnectionCandidate(listOf(discovered)))
        assertEquals(listOf(discovered), authority.manuallySelectableNodes(listOf(
            discovered.copy(nodeIdHint = "unrelated"),
            discovered,
        )))
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
        ).nodeAuthority.let { (it as ProfileNodeAuthority.Authoritative).node })
    }
}
