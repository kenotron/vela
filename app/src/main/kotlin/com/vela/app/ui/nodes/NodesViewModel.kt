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
        // Keep registry cache warm so tools can resolve labels without blocking
        viewModelScope.launch {
            nodes.collect { registry.cache = it }
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
                host     = host.trim(),
                port     = port,
                username = username.trim(),
            ))
            _addError.value = null
        }
    }

    fun removeNode(id: String) = viewModelScope.launch { registry.removeNode(id) }

    fun clearError() { _addError.value = null }
}
