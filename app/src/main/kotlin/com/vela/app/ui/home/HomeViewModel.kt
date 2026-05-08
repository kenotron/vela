package com.vela.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.ConnectivityPoller
import com.vela.app.ssh.NodeConnectivity
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
    private val poller: ConnectivityPoller,
) : ViewModel() {

    /** Live list of all nodes from the DB. */
    val nodes: StateFlow<List<SshNode>> = registry.allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Live reachability state keyed by node ID.
     * Driven by [ConnectivityPoller] — polling starts/stops with page visibility.
     */
    val nodeConnectivity: StateFlow<Map<String, NodeConnectivity>> = poller.nodeConnectivity

    init {
        // Keep registry in-memory cache fresh.
        // When new AMPLIFIERD nodes appear (e.g. just bootstrapped), trigger an immediate check.
        viewModelScope.launch {
            var lastAmplifierdIds = emptySet<String>()
            nodes.collect { current ->
                registry.updateCache(current)
                val ampIds = current
                    .filter { it.type == NodeType.AMPLIFIERD }
                    .map { it.id }
                    .toSet()
                if (ampIds.any { it !in lastAmplifierdIds }) {
                    poller.checkNow()
                }
                lastAmplifierdIds = ampIds
            }
        }
    }

    /** Call from HomeScreen ON_RESUME. Resets backoff and starts polling immediately. */
    fun onPageVisible() = poller.onPageVisible()

    /** Call from HomeScreen ON_PAUSE. Stops polling to conserve battery. */
    fun onPageHidden() = poller.onPageHidden()

    /** Trigger an immediate recheck of all nodes (e.g. pull-to-refresh). */
    fun refreshAll() = poller.checkNow()
}
