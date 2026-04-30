package com.vela.app.ui.nodeconfig

    import androidx.lifecycle.SavedStateHandle
    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.ssh.NodeBootstrapper
    import com.vela.app.ssh.SshNodeRegistry
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.update
    import kotlinx.coroutines.launch
    import javax.inject.Inject

    @HiltViewModel
    class NodeConfigViewModel @Inject constructor(
        savedStateHandle: SavedStateHandle,
        private val registry:    SshNodeRegistry,
        private val bootstrapper: NodeBootstrapper,
    ) : ViewModel() {

        val nodeId: String = checkNotNull(savedStateHandle["nodeId"])

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
    }
    