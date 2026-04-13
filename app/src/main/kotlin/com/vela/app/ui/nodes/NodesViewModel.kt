package com.vela.app.ui.nodes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.SshKeyManager
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class NodesViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
    private val keyManager: SshKeyManager,
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
}
