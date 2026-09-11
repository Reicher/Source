package com.source.client.ui

import com.source.client.SourceClientApplication
import com.source.client.model.ConnectedNode
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeConnectionState
import com.source.client.model.NodeDisconnectReason
import com.source.client.model.NodeRecoveryPhase
import com.source.client.model.TrustedNode
import com.source.client.security.SourceCrypto
import com.source.client.security.VaultSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

internal fun NodeConnectionState.connectedNodeOrNull(): ConnectedNode? =
    (this as? NodeConnectionState.Connected)?.connection

internal fun NodeConnectionState.isAuthenticating(node: DiscoveredNode): Boolean =
    this is NodeConnectionState.Authenticating &&
        this.node.serviceName == node.serviceName && this.node.apiBaseUrl == node.apiBaseUrl

internal class NodeConnection(
    private val app: SourceClientApplication,
    private val scope: CoroutineScope,
    private val session: () -> VaultSession?,
    private val onState: (NodeConnectionState) -> Unit,
    private val onConnected: suspend () -> Unit,
    private val onHeartbeat: suspend () -> Unit,
    private val onRecoveryConfigured: suspend () -> Unit,
) {
    private var foreground = false
    private var observationJob: Job? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null

    var state: NodeConnectionState = NodeConnectionState.Discovering
        private set

    val current: ConnectedNode?
        get() = state.connectedNodeOrNull()

    fun start() {
        if (observationJob != null) return
        observationJob = scope.launch {
            combine(app.nodeDiscovery.nodes, app.networkMonitor.available) { nodes, network -> nodes to network }
                .collect { (nodes, network) -> reconcile(nodes, network) }
        }
    }

    fun onForeground() {
        foreground = true
        if (session() == null) return
        transition(NodeConnectionState.Discovering)
        startRuntime()
    }

    fun onBackground() {
        foreground = false
        val trusted = current?.trusted ?: session()?.vault?.trustedNodes?.firstOrNull()
        app.nodeDiscovery.stop()
        app.networkMonitor.stop()
        cancelConnectionWork()
        transition(NodeConnectionState.Disconnected(trusted, NodeDisconnectReason.BACKGROUND))
    }

    fun clear() {
        onBackground()
        transition(NodeConnectionState.Discovering)
    }

    fun close() {
        clear()
        observationJob?.cancel()
        observationJob = null
    }

    fun retry() {
        transition(NodeConnectionState.Discovering)
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    fun beginPairing(node: DiscoveredNode, name: String) {
        cancelConnectionWork()
        transition(NodeConnectionState.Pairing(node, name))
    }

    fun beginRecovery(node: DiscoveredNode, name: String) {
        cancelConnectionWork()
        transition(NodeConnectionState.Recovering(node, name, NodeRecoveryPhase.REPLACING_CLIENT))
    }

    fun cancelConnectionFlow() {
        cancelConnectionWork()
        transition(stateForCurrentNodes())
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    fun fail(message: String) {
        cancelConnectionWork()
        transition(NodeConnectionState.Failed(message))
    }

    fun disconnect(trusted: TrustedNode) {
        cancelConnectionWork()
        transition(NodeConnectionState.Disconnected(trusted, NodeDisconnectReason.LOST))
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    fun saveTrustedNode(activeSession: VaultSession, trusted: TrustedNode) {
        activeSession.vault = activeSession.vault.copy(
            trustedNodes = activeSession.vault.trustedNodes.filterNot { it.nodeId == trusted.nodeId } + trusted,
        )
        app.secureVault.save(activeSession)
    }

    suspend fun acceptConnection(discovered: DiscoveredNode, trusted: TrustedNode) {
        transition(NodeConnectionState.Connected(ConnectedNode(discovered, trusted)))
        onConnected()
        startHeartbeat(discovered, trusted)
    }

    fun stateForCurrentNodes(): NodeConnectionState {
        val trusted = session()?.vault?.trustedNodes.orEmpty()
        val nodes = app.nodeDiscovery.nodes.value
        return when {
            trusted.isNotEmpty() -> NodeConnectionState.Disconnected(trusted.first(), NodeDisconnectReason.NOT_FOUND)
            nodes.isNotEmpty() -> NodeConnectionState.Found(nodes)
            else -> NodeConnectionState.Discovering
        }
    }

    private fun startRuntime() {
        app.networkMonitor.start()
        if (app.networkMonitor.available.value) app.nodeDiscovery.start()
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    private fun reconcile(nodes: List<DiscoveredNode>, network: Boolean) {
        val activeSession = session() ?: return
        if (!foreground || state is NodeConnectionState.Pairing) return
        val recovery = state as? NodeConnectionState.Recovering
        if (recovery?.phase == NodeRecoveryPhase.REPLACING_CLIENT) return
        if (recovery?.phase == NodeRecoveryPhase.CONFIGURING_DATA_KEY && network && nodes.any {
                it.serviceName == recovery.node.serviceName && it.apiBaseUrl == recovery.node.apiBaseUrl
            }
        ) return
        if (!network) {
            app.nodeDiscovery.stop()
            cancelConnectionWork()
            transition(
                NodeConnectionState.Disconnected(
                    activeSession.vault.trustedNodes.firstOrNull(),
                    NodeDisconnectReason.NETWORK_UNAVAILABLE,
                ),
            )
            return
        }
        app.nodeDiscovery.start()
        val trustedNodes = activeSession.vault.trustedNodes
        val candidate = nodes.firstNotNullOfOrNull { discovered ->
            trustedNodes.firstOrNull { it.nodeId == discovered.nodeIdHint }?.let { discovered to it }
        }
        if (candidate != null) {
            val connected = current
            if (connected?.trusted?.nodeId == candidate.second.nodeId &&
                connected.discovered.serviceName == candidate.first.serviceName &&
                connected.discovered.apiBaseUrl == candidate.first.apiBaseUrl
            ) return
            if (state.isAuthenticating(candidate.first)) return
            authenticate(candidate.first, candidate.second)
            return
        }
        cancelConnectionWork()
        transition(
            when {
                trustedNodes.isNotEmpty() ->
                    NodeConnectionState.Disconnected(trustedNodes.first(), NodeDisconnectReason.NOT_FOUND)
                nodes.isNotEmpty() -> NodeConnectionState.Found(nodes)
                else -> NodeConnectionState.Discovering
            },
        )
    }

    private fun authenticate(discovered: DiscoveredNode, trusted: TrustedNode) {
        connectJob?.cancel()
        heartbeatJob?.cancel()
        connectJob = scope.launch {
            for (attempt in 0..MAX_RECONNECT_ATTEMPTS) {
                if (!canAuthenticate(discovered)) {
                    if (state !is NodeConnectionState.Pairing &&
                        (state !is NodeConnectionState.Recovering ||
                            (state as NodeConnectionState.Recovering).phase == NodeRecoveryPhase.CONFIGURING_DATA_KEY)
                    ) {
                        transition(NodeConnectionState.Disconnected(trusted, NodeDisconnectReason.NOT_FOUND))
                    }
                    return@launch
                }
                transition(NodeConnectionState.Authenticating(discovered, trusted, attempt))
                try {
                    var refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                    val activeSession = session() ?: return@launch
                    if (refreshed.displayName != trusted.displayName) {
                        activeSession.vault = activeSession.vault.copy(
                            trustedNodes = activeSession.vault.trustedNodes.map {
                                if (it.nodeId == refreshed.nodeId) refreshed else it
                            },
                        )
                        app.secureVault.save(activeSession)
                    }
                    transition(NodeConnectionState.Connected(ConnectedNode(discovered, refreshed)))
                    onConnected()
                    refreshed = configureRecoveryIfNeeded(activeSession, discovered, refreshed)
                    startHeartbeat(discovered, refreshed)
                    return@launch
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    if (attempt == MAX_RECONNECT_ATTEMPTS || !canAuthenticate(discovered)) {
                        transition(NodeConnectionState.Disconnected(trusted, NodeDisconnectReason.AUTHENTICATION_FAILED))
                        return@launch
                    }
                    delay((1L shl attempt).coerceAtMost(16) * 1_000)
                }
            }
        }
    }

    private fun canAuthenticate(discovered: DiscoveredNode): Boolean = foreground &&
        app.nodeDiscovery.nodes.value.any {
            it.serviceName == discovered.serviceName && it.apiBaseUrl == discovered.apiBaseUrl
        } && state !is NodeConnectionState.Pairing &&
        (state !is NodeConnectionState.Recovering ||
            (state as NodeConnectionState.Recovering).phase == NodeRecoveryPhase.CONFIGURING_DATA_KEY)

    private suspend fun configureRecoveryIfNeeded(
        activeSession: VaultSession,
        discovered: DiscoveredNode,
        trusted: TrustedNode,
    ): TrustedNode {
        if (trusted.recoveryKey != null && trusted.dataKey != null && !trusted.recoverySetupPending) return trusted
        val pending = if (trusted.recoveryKey == null || trusted.dataKey == null) {
            val material = SourceCrypto.generateRecoveryMaterial(trusted.nodeId)
            trusted.copy(
                recoveryKey = material.recoveryKey,
                dataKey = material.dataKey,
                recoverySetupPending = true,
            )
        } else {
            trusted.copy(recoverySetupPending = true)
        }
        saveTrustedNode(activeSession, pending)
        transition(
            NodeConnectionState.Recovering(
                discovered,
                pending.displayName,
                NodeRecoveryPhase.CONFIGURING_DATA_KEY,
            ),
        )
        val recoveryKey = checkNotNull(pending.recoveryKey)
        val dataKey = SourceCrypto.base64UrlDecode(checkNotNull(pending.dataKey))
        val envelope = try {
            SourceCrypto.wrapDataKey(pending.nodeId, recoveryKey, dataKey)
        } finally {
            dataKey.fill(0)
        }
        app.nodeApi.setupRecovery(discovered.apiBaseUrl, pending, recoveryKey, envelope)
        val updated = pending.copy(recoverySetupPending = false)
        saveTrustedNode(activeSession, updated)
        transition(NodeConnectionState.Connected(ConnectedNode(discovered, updated)))
        onRecoveryConfigured()
        return updated
    }

    private fun startHeartbeat(discovered: DiscoveredNode, trusted: TrustedNode) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (foreground && current?.trusted?.nodeId == trusted.nodeId) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                try {
                    val refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                    transition(NodeConnectionState.Connected(ConnectedNode(discovered, refreshed)))
                    onHeartbeat()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    transition(NodeConnectionState.Disconnected(trusted, NodeDisconnectReason.LOST))
                    authenticate(discovered, trusted)
                    return@launch
                }
            }
        }
    }

    private fun cancelConnectionWork() {
        connectJob?.cancel()
        connectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun transition(next: NodeConnectionState) {
        state = next
        onState(next)
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val HEARTBEAT_INTERVAL_MILLIS = 20_000L
    }
}
