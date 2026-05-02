package com.vela.app.ui.nodedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.amplifierd.AmplifierdCapabilities
import com.vela.app.amplifierd.AmplifierdProject
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.ssh.BootstrapEvent
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.nodes.BootstrapUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NodeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
    private val bootstrapper: NodeBootstrapper,
) : ViewModel() {

    val nodeId: String = checkNotNull(savedStateHandle["nodeId"])

    val node: StateFlow<SshNode?> = registry.allFlow()
        .map { list -> list.find { it.id == nodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _projects = MutableStateFlow<List<AmplifierdProject>>(emptyList())
    val projects: StateFlow<List<AmplifierdProject>> = _projects

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ── Live capabilities (Phase 8) ────────────────────────────────────────

    private val _capabilities = MutableStateFlow<AmplifierdCapabilities?>(null)
    val capabilities: StateFlow<AmplifierdCapabilities?> = _capabilities

    init {
        viewModelScope.launch {
            node.filterNotNull().first() // wait for node to load
            loadProjects()
            startCapabilitiesPolling()
        }
    }

    private fun startCapabilitiesPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                try {
                    val n = node.value ?: break
                    _capabilities.value = amplifierd.clientForNode(n)?.getCapabilities()
                } catch (_: Exception) { /* silent — node may be offline */ }
                delay(30_000)
            }
        }
    }

    fun loadProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                // Use the node we already have in the StateFlow to build the client —
                // this avoids the registry.cache race condition where the cache may not
                // yet be populated when loadProjects() is first called.
                val client = amplifierd.clientForNode(node.value) ?: return@launch
                _projects.value = client.getProjects()
            } catch (e: Exception) {
                android.util.Log.w("NodeDetailVM", "loadProjects failed: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun removeNode() {
        registry.removeNode(nodeId)
    }

    val workspaceDir: String get() = node.value?.workspaceDir ?: "~"

    suspend fun createProject(name: String, workingDir: String = ""): Boolean {
        return try {
            val n = node.value ?: return false
            val client = amplifierd.clientForNode(n) ?: return false
            val effectiveDir = workingDir.ifBlank {
                val base = n.workspaceDir.trimEnd('/')
                val slug = name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
                "$base/$slug"
            }
            val project = client.createProject(name, workingDir = effectiveDir)
            _projects.update { it + project }
            true
        } catch (e: Exception) {
            android.util.Log.w("NodeDetailVM", "createProject failed: ${e.message}")
            false
        }
    }

        suspend fun updateProject(projectId: String, name: String, workingDir: String): Boolean {
            return try {
                val n = node.value ?: return false
                val client = amplifierd.clientForNode(n) ?: return false
                val updated = client.updateProject(projectId, name, workingDir)
                _projects.update { list -> list.map { if (it.id == projectId) updated else it } }
                true
            } catch (e: Exception) {
                android.util.Log.w("NodeDetailVM", "updateProject failed: ${e.message}")
                false
            }
        }

        suspend fun deleteProject(projectId: String): Boolean {
            return try {
                val n = node.value ?: return false
                val client = amplifierd.clientForNode(n) ?: return false
                client.deleteProject(projectId)
                _projects.update { list -> list.filter { it.id != projectId } }
                true
            } catch (e: Exception) {
                android.util.Log.w("NodeDetailVM", "deleteProject failed: ${e.message}")
                false
            }
        }
    
    // ── Repair state ──────────────────────────────────────────────────────

    private val _repairState = MutableStateFlow(BootstrapUiState())
    val repairState: StateFlow<BootstrapUiState> = _repairState

    fun startRepair() {
        val n = node.value ?: return
        _repairState.value = BootstrapUiState(isBootstrapping = true)
        viewModelScope.launch(Dispatchers.IO) {
            bootstrapper.repair(
                nodeId        = nodeId,
                host          = n.primaryHost,
                port          = n.port,
                username      = n.username,
                existingToken = n.token,
            ).collect { event ->
                when (event) {
                    is BootstrapEvent.Output       -> _repairState.update { it.copy(logLines = it.logLines + event.line) }
                    is BootstrapEvent.StepStart    -> _repairState.update { it.copy(currentStep = event.step) }
                    is BootstrapEvent.StepComplete -> _repairState.update { it.copy(completedSteps = it.completedSteps + event.step) }
                    is BootstrapEvent.Failed       -> _repairState.update {
                        it.copy(
                            isBootstrapping = false,
                            errorMessage    = event.error,
                            logLines        = it.logLines + event.logs,
                        )
                    }
                    is BootstrapEvent.Complete     -> {
                        // Sync the URL back to Room — Tailscale IP may have changed since first bootstrap
                        n.copy(url = event.url).let { updated ->
                            viewModelScope.launch(Dispatchers.IO) { registry.updateConnection(updated) }
                        }
                        _repairState.update { it.copy(isBootstrapping = false, isComplete = true) }
                    }
                }
            }
        }
    }

    fun clearRepairState() { _repairState.value = BootstrapUiState() }
}
