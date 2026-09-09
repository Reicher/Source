package com.source.client.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.source.client.SourceClientApplication
import com.source.client.model.DiscoveredNode
import com.source.client.model.NodeStatus
import com.source.client.model.TrustedNode
import com.source.client.protocol.PairingPayloadException
import com.source.client.protocol.PairingPayloadParser
import com.source.client.protocol.SourceApiException
import com.source.client.security.VaultSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AppScreen {
    data object Loading : AppScreen
    data class Setup(val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Locked(val error: String? = null, val busy: Boolean = false) : AppScreen
    data class Main(val status: NodeStatus) : AppScreen
    data class Scanner(val node: DiscoveredNode, val error: String? = null) : AppScreen
    data class Pairing(val name: String) : AppScreen
}

internal fun keepActiveConnectionStatus(status: NodeStatus, connectionAttemptActive: Boolean): Boolean =
    status is NodeStatus.Connecting && connectionAttemptActive

class SourceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as SourceClientApplication
    private val _screen = MutableStateFlow<AppScreen>(AppScreen.Loading)
    private var session: VaultSession? = null
    private var foreground = false
    private var selectedNode: DiscoveredNode? = null
    private var connectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastConnectedService: String? = null
    private var lastConnectedApiBaseUrl: String? = null

    val screen: StateFlow<AppScreen> = _screen.asStateFlow()

    init {
        _screen.value = if (app.secureVault.isInitialized) AppScreen.Locked() else AppScreen.Setup()
        viewModelScope.launch {
            combine(app.nodeDiscovery.nodes, app.networkMonitor.available) { nodes, network -> nodes to network }
                .collect { (nodes, network) -> reconcile(nodes, network) }
        }
    }

    fun createIdentity(userName: String, password: String, confirmation: String) {
        val name = userName.trim()
        val error = when {
            name.isEmpty() -> "Ange ett användarnamn."
            name.length > 100 || name.any { it.isISOControl() } -> "Användarnamnet är ogiltigt."
            password.isEmpty() -> "Ange ett lösenord."
            password != confirmation -> "Lösenorden matchar inte."
            else -> null
        }
        if (error != null) {
            _screen.value = AppScreen.Setup(error)
            return
        }
        _screen.value = AppScreen.Setup(busy = true)
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                session = withContext(Dispatchers.Default) { app.secureVault.create(name, chars) }
                openMain()
            } catch (_: Exception) {
                _screen.value = AppScreen.Setup("Identiteten kunde inte skapas på den här enheten.")
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun unlock(password: String) {
        if (password.isEmpty()) {
            _screen.value = AppScreen.Locked("Ange ditt lösenord.")
            return
        }
        _screen.value = AppScreen.Locked(busy = true)
        viewModelScope.launch {
            val chars = password.toCharArray()
            try {
                session = withContext(Dispatchers.Default) { app.secureVault.unlock(chars) }
                if (session == null) _screen.value = AppScreen.Locked("Fel lösenord.") else openMain()
            } finally {
                chars.fill('\u0000')
            }
        }
    }

    fun onForeground() {
        foreground = true
        if (session != null) startRuntime()
    }

    fun onBackground() {
        foreground = false
        app.nodeDiscovery.stop()
        app.networkMonitor.stop()
        connectJob?.cancel()
        heartbeatJob?.cancel()
        if (_screen.value is AppScreen.Pairing) {
            _screen.value = AppScreen.Main(statusForCurrentNodes())
        }
        (_screen.value as? AppScreen.Main)?.let { main ->
            val connected = (main.status as? NodeStatus.Connected)?.node
            if (connected != null) _screen.value = AppScreen.Main(NodeStatus.PairedOffline(connected))
        }
    }

    fun scan(node: DiscoveredNode) {
        selectedNode = node
        _screen.value = AppScreen.Scanner(node)
    }

    fun cancelScanner() {
        selectedNode = null
        _screen.value = AppScreen.Main(statusForCurrentNodes())
    }

    fun onQrScanned(raw: String) {
        val scanner = _screen.value as? AppScreen.Scanner ?: return
        val invitation = try {
            PairingPayloadParser.parse(raw)
        } catch (error: PairingPayloadException) {
            _screen.value = scanner.copy(error = error.message)
            return
        }
        if (invitation.nodeId != scanner.node.nodeIdHint) {
            _screen.value = scanner.copy(error = "QR-koden tillhör inte noden du valde.")
            return
        }
        val activeSession = session ?: return
        _screen.value = AppScreen.Pairing(invitation.nodeName)
        connectJob?.cancel()
        connectJob = viewModelScope.launch {
            try {
                val trusted = app.nodeApi.pair(invitation, activeSession.vault.identity)
                activeSession.vault = activeSession.vault.copy(
                    trustedNodes = activeSession.vault.trustedNodes.filterNot { it.nodeId == trusted.nodeId } + trusted,
                )
                app.secureVault.save(activeSession)
                selectedNode = null
                _screen.value = AppScreen.Main(NodeStatus.Connected(trusted))
                startHeartbeat(scanner.node, trusted)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _screen.value = AppScreen.Main(NodeStatus.Error(readableError(error)))
            }
        }
    }

    fun retry() {
        _screen.value = AppScreen.Main(statusForCurrentNodes())
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    private fun openMain() {
        _screen.value = AppScreen.Main(NodeStatus.Searching)
        if (foreground) startRuntime()
    }

    private fun startRuntime() {
        app.networkMonitor.start()
        if (app.networkMonitor.available.value) app.nodeDiscovery.start()
        reconcile(app.nodeDiscovery.nodes.value, app.networkMonitor.available.value)
    }

    private fun reconcile(nodes: List<DiscoveredNode>, network: Boolean) {
        if (!foreground || session == null || _screen.value !is AppScreen.Main) return
        if (!network) {
            app.nodeDiscovery.stop()
            heartbeatJob?.cancel()
            connectJob?.cancel()
            _screen.value = AppScreen.Main(session!!.vault.trustedNodes.firstOrNull()?.let(NodeStatus::PairedOffline) ?: NodeStatus.NoneFound)
            return
        }
        app.nodeDiscovery.start()
        val trustedNodes = session!!.vault.trustedNodes
        val candidate = nodes.firstNotNullOfOrNull { discovered ->
            trustedNodes.firstOrNull { it.nodeId == discovered.nodeIdHint }?.let { discovered to it }
        }
        if (candidate != null) {
            val current = (_screen.value as? AppScreen.Main)?.status as? NodeStatus.Connected
            if (current?.node?.nodeId == candidate.second.nodeId &&
                lastConnectedService == candidate.first.serviceName &&
                lastConnectedApiBaseUrl == candidate.first.apiBaseUrl
            ) return
            authenticate(candidate.first, candidate.second)
            return
        }
        val currentStatus = (_screen.value as? AppScreen.Main)?.status
        if (currentStatus != null && keepActiveConnectionStatus(currentStatus, connectJob?.isActive == true)) return
        heartbeatJob?.cancel()
        _screen.value = AppScreen.Main(
            when {
                trustedNodes.isNotEmpty() -> NodeStatus.PairedOffline(trustedNodes.first())
                nodes.isNotEmpty() -> NodeStatus.Found(nodes)
                else -> NodeStatus.NoneFound
            },
        )
    }

    private fun authenticate(discovered: DiscoveredNode, trusted: TrustedNode, attempt: Int = 0) {
        if (connectJob?.isActive == true) return
        _screen.value = AppScreen.Main(NodeStatus.Connecting(trusted.displayName))
        connectJob = viewModelScope.launch {
            try {
                val refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                val activeSession = session ?: return@launch
                if (refreshed.displayName != trusted.displayName) {
                    activeSession.vault = activeSession.vault.copy(
                        trustedNodes = activeSession.vault.trustedNodes.map { if (it.nodeId == refreshed.nodeId) refreshed else it },
                    )
                    app.secureVault.save(activeSession)
                }
                lastConnectedService = discovered.serviceName
                lastConnectedApiBaseUrl = discovered.apiBaseUrl
                _screen.value = AppScreen.Main(NodeStatus.Connected(refreshed))
                startHeartbeat(discovered, refreshed)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (foreground && attempt < 5) {
                    delay((1L shl attempt).coerceAtMost(16) * 1_000)
                    connectJob = null
                    if (app.nodeDiscovery.nodes.value.any { it.serviceName == discovered.serviceName }) {
                        authenticate(discovered, trusted, attempt + 1)
                        return@launch
                    }
                }
                _screen.value = AppScreen.Main(NodeStatus.PairedOffline(trusted))
            }
        }
    }

    private fun startHeartbeat(discovered: DiscoveredNode, trusted: TrustedNode) {
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (foreground) {
                delay(20_000)
                try {
                    val refreshed = app.nodeApi.authenticate(discovered.apiBaseUrl, trusted)
                    _screen.value = AppScreen.Main(NodeStatus.Connected(refreshed))
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    lastConnectedService = null
                    lastConnectedApiBaseUrl = null
                    _screen.value = AppScreen.Main(NodeStatus.PairedOffline(trusted))
                    connectJob = null
                    authenticate(discovered, trusted)
                    return@launch
                }
            }
        }
    }

    private fun statusForCurrentNodes(): NodeStatus {
        val trusted = session?.vault?.trustedNodes.orEmpty()
        val nodes = app.nodeDiscovery.nodes.value
        return when {
            trusted.isNotEmpty() -> NodeStatus.PairedOffline(trusted.first())
            nodes.isNotEmpty() -> NodeStatus.Found(nodes)
            else -> NodeStatus.NoneFound
        }
    }

    private fun readableError(error: Exception): String = when {
        error is SourceApiException && error.code == "pairing_unavailable" -> "Inbjudan är inte längre tillgänglig."
        error is SourceApiException && error.code == "duplicate_client" -> "Den här klienten är redan parkopplad."
        error is SourceApiException -> error.message ?: "Anslutningen misslyckades."
        else -> "Source Node kunde inte nås."
    }

    override fun onCleared() {
        app.nodeDiscovery.stop()
        app.networkMonitor.stop()
        session?.close()
        super.onCleared()
    }
}
