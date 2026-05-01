package com.vela.app.ui.nodeconfig

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.NodeBootstrapper
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
class NodeConfigViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    private val bootstrapper: NodeBootstrapper,
) : ViewModel() {

    val nodeId: String = checkNotNull(savedStateHandle["nodeId"])

    // ── Node from registry ────────────────────────────────────────────────────

    val node: StateFlow<SshNode?> = registry.allFlow()
        .map { it.find { n -> n.id == nodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ── Tool toggles ──────────────────────────────────────────────────────────

    private val _tools = MutableStateFlow(
        mapOf(
            "bash"        to true,
            "github"      to true,
            "web_search"  to true,
            "read_file"   to true,
            "code_runner" to false,
        )
    )
    val tools: StateFlow<Map<String, Boolean>> = _tools

    private val _maxSteps = MutableStateFlow(10)
    val maxSteps: StateFlow<Int> = _maxSteps

    private val _isPushing = MutableStateFlow(false)
    val isPushing: StateFlow<Boolean> = _isPushing

    fun toggleTool(name: String) {
        _tools.update { current ->
            current.toMutableMap().apply { this[name] = !(this[name] ?: true) }
        }
    }

    fun setMaxSteps(steps: Int) {
        _maxSteps.value = steps
    }

    fun pushToNode() {
        viewModelScope.launch(Dispatchers.IO) {
            _isPushing.value = true
            // TODO: serialize tools + maxSteps and push config to node
            _isPushing.value = false
        }
    }

    // ── Connection form ───────────────────────────────────────────────────────

    data class ConnectionFormState(
        val label: String = "",
        val host: String = "",
        val port: String = "22",
        val username: String = "",
        val workspaceDir: String = "~",
    )

    private val _connForm = MutableStateFlow(ConnectionFormState())
    val connForm: StateFlow<ConnectionFormState> = _connForm

    // Populate form fields as soon as the node loads.
    init {
        viewModelScope.launch {
            node.filterNotNull().first().let { n ->
                _connForm.value = ConnectionFormState(
                    label        = n.label,
                    host         = n.hosts.firstOrNull() ?: "",
                    port         = n.port.toString(),
                    username     = n.username,
                    workspaceDir = n.workspaceDir,
                )
            }
        }
    }

    fun updateLabel(v: String)        { _connForm.update { it.copy(label = v) } }
    fun updateHost(v: String)         { _connForm.update { it.copy(host = v) } }
    fun updatePort(v: String)         { _connForm.update { it.copy(port = v) } }
    fun updateUsername(v: String)     { _connForm.update { it.copy(username = v) } }
    fun updateWorkspaceDir(v: String) { _connForm.update { it.copy(workspaceDir = v) } }

    private val _isSavingConn = MutableStateFlow(false)
    val isSavingConn: StateFlow<Boolean> = _isSavingConn

    fun saveConnection() {
        val f = _connForm.value
        val n = node.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isSavingConn.value = true
            registry.updateConnection(
                n.copy(
                    label        = f.label.ifBlank { f.host },
                    hosts        = listOf(f.host),
                    port         = f.port.toIntOrNull() ?: 22,
                    username     = f.username,
                    workspaceDir = f.workspaceDir.ifBlank { "~" },
                )
            )
            _isSavingConn.value = false
        }
    }
}
