package com.source.client.ui

import com.source.client.model.ConnectedNode
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeConnectionState
import com.source.client.model.TrustedNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
