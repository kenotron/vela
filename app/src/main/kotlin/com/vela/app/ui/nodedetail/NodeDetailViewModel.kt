package com.vela.app.ui.nodedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class NodeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    // Projects come from the amplifierd HTTP API — placeholder: returns empty list for now.
    // Phase 3 will inject an AmplifierdClient and expose a projects StateFlow.
) : ViewModel() {

    val nodeId: String = checkNotNull(savedStateHandle["nodeId"])

    val node: StateFlow<SshNode?> = registry.allFlow()
        .map { nodes -> nodes.find { it.id == nodeId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
