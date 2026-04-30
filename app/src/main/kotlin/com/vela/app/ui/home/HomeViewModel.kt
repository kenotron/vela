package com.vela.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ssh.SshNode
import com.vela.app.ssh.SshNodeRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val registry: SshNodeRegistry,
) : ViewModel() {

    val nodes: StateFlow<List<SshNode>> = registry.allFlow()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
}
