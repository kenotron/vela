package com.vela.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.ssh.NodeConnectivity
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
) : ViewModel() {

    val nodes: StateFlow<List<SshNode>> = registry.allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _nodeConnectivity = MutableStateFlow<Map<String, NodeConnectivity>>(emptyMap())
    val nodeConnectivity: StateFlow<Map<String, NodeConnectivity>> = _nodeConnectivity.asStateFlow()

    init {
        // Keep the registry in-memory cache fresh
        viewModelScope.launch {
            nodes.collect { registry.updateCache(it) }
        }
        // Start background health polling
        startHealthPolling()
    }

    /** Immediately recheck all AMPLIFIERD nodes. */
    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            checkAllNodes(nodes.value)
        }
    }

    /** Immediately recheck a single node by ID. */
    fun refresh(nodeId: String) {
        val node = nodes.value.find { it.id == nodeId } ?: return
        viewModelScope.launch(Dispatchers.IO) { checkNode(node) }
    }

    private fun startHealthPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            // Initial check as soon as we have nodes
            var lastNodes = emptyList<SshNode>()
            nodes.collect { current ->
                val amplifierdNodes = current.filter { it.type == NodeType.AMPLIFIERD }
                val newNodes = amplifierdNodes.filter { n -> lastNodes.none { it.id == n.id } }
                lastNodes = amplifierdNodes
                // Mark new nodes as Checking immediately
                if (newNodes.isNotEmpty()) {
                    _nodeConnectivity.update { map ->
                        map + newNodes.associate { it.id to NodeConnectivity.Checking }
                    }
                    newNodes.forEach { checkNode(it) }
                }
            }
        }
        // Periodic recheck every 60s
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                delay(60_000)
                checkAllNodes(nodes.value)
            }
        }
    }

    private suspend fun checkAllNodes(nodeList: List<SshNode>) {
        nodeList.filter { it.type == NodeType.AMPLIFIERD }.forEach { checkNode(it) }
    }

    private suspend fun checkNode(node: SshNode) {
        _nodeConnectivity.update { it + (node.id to NodeConnectivity.Checking) }
        val reachableUrl = amplifierd.findReachableUrl(node)
        val connectivity = if (reachableUrl != null)
            NodeConnectivity.Reachable(reachableUrl)
        else
            NodeConnectivity.Unreachable
        _nodeConnectivity.update { it + (node.id to connectivity) }
    }
}
