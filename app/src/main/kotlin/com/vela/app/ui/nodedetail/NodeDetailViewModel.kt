package com.vela.app.ui.nodedetail

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.amplifierd.AmplifierdProject
import com.vela.app.amplifierd.AmplifierdRepository
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
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NodeDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
) : ViewModel() {

    val nodeId: String = checkNotNull(savedStateHandle["nodeId"])

    val node: StateFlow<SshNode?> = registry.allFlow()
        .map { list -> list.find { it.id == nodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _projects = MutableStateFlow<List<AmplifierdProject>>(emptyList())
    val projects: StateFlow<List<AmplifierdProject>> = _projects

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        viewModelScope.launch {
            node.filterNotNull().first() // wait for node to load
            loadProjects()
        }
    }

    fun loadProjects() {
        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.value = true
            try {
                val client = amplifierd.clientFor(nodeId) ?: return@launch
                _projects.value = client.getProjects()
            } catch (e: Exception) {
                // silently fail — node might not be reachable yet
            } finally {
                _isLoading.value = false
            }
        }
    }

    suspend fun removeNode() {
        registry.removeNode(nodeId)
    }

    suspend fun createProject(name: String): Boolean {
        return try {
            val client = amplifierd.clientFor(nodeId) ?: return false
            val project = client.createProject(name)
            _projects.value = _projects.value + project
            true
        } catch (e: Exception) {
            false
        }
    }
}
