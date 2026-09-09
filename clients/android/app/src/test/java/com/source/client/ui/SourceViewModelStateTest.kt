package com.source.client.ui

import com.source.client.model.NodeStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceViewModelStateTest {
    @Test
    fun `transient discovery updates do not replace an active connecting state`() {
        assertTrue(keepActiveConnectionStatus(NodeStatus.Connecting("Plattservern"), true))
    }

    @Test
    fun `connecting state can become offline after the attempt finishes`() {
        assertFalse(keepActiveConnectionStatus(NodeStatus.Connecting("Plattservern"), false))
    }

    @Test
    fun `other states are never retained as active connection attempts`() {
        assertFalse(keepActiveConnectionStatus(NodeStatus.NoneFound, true))
    }
}
