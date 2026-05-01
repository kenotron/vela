package com.vela.app.ui.connectnode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.BundleChoice
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.nodes.BootstrapUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ConnectFormState(
    val host:         String       = "",
    val port:         String       = "22",
    val username:     String       = "",
    val bundle:       BundleChoice = BundleChoice.SUPERPOWERS,
    val anthropicKey: String       = "",
    val workspaceDir: String       = "~",
)

@HiltViewModel
class ConnectNodeViewModel @Inject constructor(
    private val registry:     SshNodeRegistry,
    private val keyManager:   SshKeyManager,
    private val bootstrapper: NodeBootstrapper,
) : ViewModel() {

    val publicKey: String get() = keyManager.getPublicKey()

    // Recent hosts from previously connected nodes (up to 5, most recent first)
    val recentHosts: List<String>
        get() = registry.cache
            .map { it.primaryHost }
            .filter { it.isNotBlank() }
            .distinct()
            .take(5)

    private val _form = MutableStateFlow(ConnectFormState())
    val form: StateFlow<ConnectFormState> = _form

    private val _bootstrapState = MutableStateFlow(BootstrapUiState())
    val bootstrapState: StateFlow<BootstrapUiState> = _bootstrapState

    fun updateHost(h: String)         { _form.update { it.copy(host = h) } }
    fun updatePort(p: String)         { _form.update { it.copy(port = p) } }
    fun updateUsername(u: String)     { _form.update { it.copy(username = u) } }
    fun updateBundle(b: BundleChoice) { _form.update { it.copy(bundle = b) } }
    fun updateApiKey(k: String)       { _form.update { it.copy(anthropicKey = k) } }
    fun updateWorkspaceDir(dir: String) { _form.update { it.copy(workspaceDir = dir) } }

    fun connect() {
        val f      = _form.value
        val nodeId = UUID.randomUUID().toString()
        viewModelScope.launch(Dispatchers.IO) {
            // Add the node AFTER bootstrap succeeds (in the Complete handler below).
            // Storing it first caused dangling SSH-type nodes when bootstrap failed.
            _bootstrapState.value = BootstrapUiState(isBootstrapping = true)

            bootstrapper.bootstrap(
                nodeId       = nodeId,
                host         = f.host,
                port         = f.port.toIntOrNull() ?: 22,
                username     = f.username,
                bundle       = f.bundle,
                anthropicKey = f.anthropicKey,
            ).collect { event ->
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
                    is BootstrapEvent.Complete -> {
                        // Only persist the node after a successful bootstrap —
                        // no dangling SSH-type rows when bootstrap fails.
                        registry.addNode(
                            SshNode(
                                id       = nodeId,
                                label    = f.host,
                                hosts    = listOf(f.host),
                                port     = f.port.toIntOrNull() ?: 22,
                                username = f.username,
                                type     = com.vela.app.ssh.NodeType.AMPLIFIERD,
                                url      = event.url,
                                token    = event.token,
                                bootstrapStatus = BootstrapStatus.RUNNING,
                                workspaceDir    = f.workspaceDir.ifBlank { "~" },
                            )
                        )
                        _bootstrapState.update { it.copy(isBootstrapping = false, isComplete = true) }
                    }
                }
            }
        }
    }

    fun clearBootstrapState() {
        _bootstrapState.value = BootstrapUiState()
    }
}
