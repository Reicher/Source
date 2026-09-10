package com.source.client.ui

import com.source.client.SourceClientApplication
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeStatus
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

internal data class ConnectedNode(val discovered: DiscoveredNode, val trusted: TrustedNode)

internal fun keepActiveConnectionStatus(status: NodeStatus, connectionAttemptActive: Boolean): Boolean =
    status is NodeStatus.Connecting && connectionAttemptActive

internal class NodeConnection(
    private val app: SourceClientApplication,
    private val scope: CoroutineScope,
    private val session: () -> VaultSession?,
    private val connectionAllowed: () -> Boolean,
    private val onStatus: (NodeStatus) -> Unit,
    private val onConnected: suspend () -> Unit,
    private val onHeartbeat: suspend () -> Unit,
    private val onRecoveryConfigured: suspend () -> Unit,
) {
    private var foreground = false
    private var observationJob: Job? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastConnectedService: String? = null
    private var lastConnectedApiBaseUrl: String? = null
    private var status: NodeStatus = NodeStatus.Searching

    var current: ConnectedNode? = null
        private set

    fun start() {
        if (observationJob != null) return
        observationJob = scope.launch {
            combine(app.nodeDiscovery.nodes, app.networkMonitor.available) { nodes, network -> nodes to network }
                .collect { (nodes, network) -> reconcile(nodes, network) }
        }
    }

    fun onForeground() {
        foreground = true
        if (session() != null) startRuntime()
    }

    fun onBackground() {
        foreground = false
        current = null
        app.nodeDiscovery.stop()
        app.networkMonitor.stop()
        connectJob?.cancel()
        heartbeatJob?.cancel()
    }

    fun clear() {
        onBackground()
        lastConnectedService = null
        lastConnectedApiBaseUrl = null
    }

    fun close() {
        clear()
        observationJob?.cancel()
        observationJob = null
    }

    fun retry() {
        publishStatus(statusForCurrentNodes())
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    fun cancelActiveAttempt() {
        connectJob?.cancel()
        connectJob = null
    }

    fun disconnect(trusted: TrustedNode) {
        current = null
        lastConnectedService = null
        lastConnectedApiBaseUrl = null
        publishStatus(NodeStatus.PairedOffline(trusted))
    }

    fun saveTrustedNode(activeSession: VaultSession, trusted: TrustedNode) {
        activeSession.vault = activeSession.vault.copy(
            trustedNodes = activeSession.vault.trustedNodes.filterNot { it.nodeId == trusted.nodeId } + trusted,
        )
        app.secureVault.save(activeSession)
    }

    suspend fun acceptConnection(discovered: DiscoveredNode, trusted: TrustedNode) {
        lastConnectedService = discovered.serviceName
        lastConnectedApiBaseUrl = discovered.apiBaseUrl
        current = ConnectedNode(discovered, trusted)
        publishStatus(NodeStatus.Connected(trusted))
        onConnected()
        startHeartbeat(discovered, trusted)
    }

    fun statusForCurrentNodes(): NodeStatus {
        val trusted = session()?.vault?.trustedNodes.orEmpty()
        val nodes = app.nodeDiscovery.nodes.value
        return when {
            trusted.isNotEmpty() -> NodeStatus.PairedOffline(trusted.first())
            nodes.isNotEmpty() -> NodeStatus.Found(nodes)
            else -> NodeStatus.NoneFound
        }
    }

    private fun startRuntime() {
        app.networkMonitor.start()
        if (app.networkMonitor.available.value) app.nodeDiscovery.start()
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    private fun reconcile(nodes: List<DiscoveredNode>, network: Boolean) {
        val activeSession = session() ?: return
        if (!foreground || !connectionAllowed()) return
        if (!network) {
            current = null
            app.nodeDiscovery.stop()
            heartbeatJob?.cancel()
            connectJob?.cancel()
            publishStatus(activeSession.vault.trustedNodes.firstOrNull()?.let(NodeStatus::PairedOffline) ?: NodeStatus.NoneFound)
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
                lastConnectedService == candidate.first.serviceName &&
                lastConnectedApiBaseUrl == candidate.first.apiBaseUrl
            ) return
            authenticate(candidate.first, candidate.second)
            return
        }
        current = null
        if (keepActiveConnectionStatus(status, connectJob?.isActive == true)) return
        heartbeatJob?.cancel()
        publishStatus(
            when {
                trustedNodes.isNotEmpty() -> NodeStatus.PairedOffline(trustedNodes.first())
                nodes.isNotEmpty() -> NodeStatus.Found(nodes)
                else -> NodeStatus.NoneFound
            },
        )
    }

    private fun authenticate(discovered: DiscoveredNode, trusted: TrustedNode, attempt: Int = 0) {
        if (connectJob?.isActive == true) return
        publishStatus(NodeStatus.Connecting(trusted.displayName))
        connectJob = scope.launch {
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
                lastConnectedService = discovered.serviceName
                lastConnectedApiBaseUrl = discovered.apiBaseUrl
                current = ConnectedNode(discovered, refreshed)
                publishStatus(NodeStatus.Connected(refreshed))
                onConnected()
                refreshed = configureRecoveryIfNeeded(activeSession, discovered, refreshed)
                startHeartbeat(discovered, refreshed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                current = null
                if (foreground && attempt < MAX_RECONNECT_ATTEMPTS) {
                    delay((1L shl attempt).coerceAtMost(16) * 1_000)
                    connectJob = null
                    if (app.nodeDiscovery.nodes.value.any { it.serviceName == discovered.serviceName }) {
                        authenticate(discovered, trusted, attempt + 1)
                        return@launch
                    }
                }
                publishStatus(NodeStatus.PairedOffline(trusted))
            }
        }
    }

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
        current = ConnectedNode(discovered, pending)
        publishStatus(NodeStatus.Connected(pending))
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
        current = ConnectedNode(discovered, updated)
        publishStatus(NodeStatus.Connected(updated))
        onRecoveryConfigured()
        return updated
    }

    private fun startHeartbeat(discovered: DiscoveredNode, trusted: TrustedNode) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (foreground) {
                delay(HEARTBEAT_INTERVAL_MILLIS)
                try {
                    val refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                    current = ConnectedNode(discovered, refreshed)
                    publishStatus(NodeStatus.Connected(refreshed))
                    onHeartbeat()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    current = null
                    lastConnectedService = null
                    lastConnectedApiBaseUrl = null
                    publishStatus(NodeStatus.PairedOffline(trusted))
                    connectJob = null
                    authenticate(discovered, trusted)
                    return@launch
                }
            }
        }
    }

    private fun publishStatus(next: NodeStatus) {
        status = next
        onStatus(next)
    }

    private companion object {
        const val MAX_RECONNECT_ATTEMPTS = 5
        const val HEARTBEAT_INTERVAL_MILLIS = 20_000L
    }
}
