package com.vela.app.ui.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStep
import com.vela.app.ssh.BundleChoice
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * UI state for the amplifierd bootstrap flow. Driven by NodeBootstrapper events
 * collected by NodesViewModel.bootstrapNode().
 */
data class BootstrapUiState(
    val isBootstrapping: Boolean = false,
    val currentStep: BootstrapStep? = null,
    val completedSteps: Set<BootstrapStep> = emptySet(),
    val logLines: List<String> = emptyList(),
    val errorMessage: String? = null,
    val isComplete: Boolean = false,
)

@HiltViewModel
class NodesViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
    private val keyManager: SshKeyManager,
    private val bootstrapper: NodeBootstrapper,
) : ViewModel() {

    val nodes: StateFlow<List<SshNode>> = registry.allFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val publicKey: String get() = keyManager.getPublicKey()

    private val _addError = MutableStateFlow<String?>(null)
    val addError: StateFlow<String?> = _addError

    init {
        viewModelScope.launch {
            nodes.collect { registry.updateCache(it) }
        }
    }

    fun addNode(label: String, host: String, portStr: String, username: String) {
        val port = portStr.toIntOrNull() ?: 22
        if (label.isBlank() || host.isBlank() || username.isBlank()) {
            _addError.value = "Label, host and username are required"
            return
        }
        viewModelScope.launch {
            registry.addNode(SshNode(
                id       = UUID.randomUUID().toString(),
                label    = label.trim(),
                hosts    = listOf(host.trim()),
                port     = port,
                username = username.trim(),
                type     = NodeType.SSH,
            ))
            _addError.value = null
        }
    }

    fun addAmplifierdNode(label: String, url: String, token: String) {
        if (label.isBlank() || url.isBlank()) {
            _addError.value = "Label and URL are required"
            return
        }
        viewModelScope.launch {
            registry.addNode(SshNode(
                id    = UUID.randomUUID().toString(),
                label = label.trim(),
                type  = NodeType.AMPLIFIERD,
                url   = url.trim().trimEnd('/'),
                token = token.trim(),
            ))
            _addError.value = null
        }
    }

    /** Add an IP/hostname to an existing node's fallback list. */
    fun addHostToNode(nodeId: String, newHost: String) {
        if (newHost.isBlank()) return
        val node = nodes.value.firstOrNull { it.id == nodeId } ?: return
        if (node.hosts.contains(newHost.trim())) return
        viewModelScope.launch {
            registry.updateNode(node.copy(hosts = node.hosts + newHost.trim()))
        }
    }

    /** Remove one IP/hostname from a node. Guard: can't remove the last one. */
    fun removeHostFromNode(nodeId: String, host: String) {
        val node = nodes.value.firstOrNull { it.id == nodeId } ?: return
        if (node.hosts.size <= 1) return
        viewModelScope.launch {
            registry.updateNode(node.copy(hosts = node.hosts - host))
        }
    }

    fun removeNode(id: String) = viewModelScope.launch { registry.removeNode(id) }
    fun clearError()           { _addError.value = null }

    // ── Bootstrap flow ──────────────────────────────────────────────────────

    private val _bootstrapState = MutableStateFlow(BootstrapUiState())
    val bootstrapState: StateFlow<BootstrapUiState> = _bootstrapState

    /**
     * Drive the amplifierd bootstrap pipeline against [host] for the SSH node
     * identified by [nodeId]. Updates [bootstrapState] live as events arrive.
     */
    fun bootstrapNode(
        nodeId: String,
        host: String,
        port: Int,
        username: String,
        bundle: BundleChoice,
        anthropicKey: String,
    ) {
        _bootstrapState.value = BootstrapUiState(isBootstrapping = true)
        viewModelScope.launch(Dispatchers.IO) {
            bootstrapper.bootstrap(nodeId, host, port, username, bundle, anthropicKey)
                .collect { event ->
                    when (event) {
                        is BootstrapEvent.Output ->
                            _bootstrapState.update { it.copy(logLines = it.logLines + event.line) }
                        is BootstrapEvent.StepStart ->
                            _bootstrapState.update { it.copy(currentStep = event.step) }
                        is BootstrapEvent.StepComplete ->
                            _bootstrapState.update { it.copy(completedSteps = it.completedSteps + event.step) }
                        is BootstrapEvent.Failed ->
                            _bootstrapState.update {
                                it.copy(
                                    isBootstrapping = false,
                                    errorMessage    = event.error,
                                    logLines        = it.logLines + event.logs,
                                )
                            }
                        is BootstrapEvent.Complete ->
                            _bootstrapState.update { it.copy(isBootstrapping = false, isComplete = true) }
                    }
                }
        }
    }

    /** Reset bootstrap UI state to defaults (e.g. after the user dismisses the sheet). */
    fun clearBootstrapState() {
        _bootstrapState.value = BootstrapUiState()
    }
}
