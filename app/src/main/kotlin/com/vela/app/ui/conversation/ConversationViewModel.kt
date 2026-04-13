    package com.vela.app.ui.conversation

    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.a2ui.VelaUiParser
    import com.vela.app.ai.AgentOrchestrator
    import com.vela.app.data.repository.ConversationRepository
    import com.vela.app.domain.model.Message
    import com.vela.app.domain.model.MessageRole
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.launch
    import javax.inject.Inject

    enum class EngineState { ModelNotReady, ModelReady }

    @HiltViewModel
    class ConversationViewModel @Inject constructor(
        private val orchestrator: AgentOrchestrator,
        private val repository: ConversationRepository,
    ) : ViewModel() {

        private val _messages = MutableStateFlow<List<Message>>(emptyList())
        val messages: StateFlow<List<Message>> = _messages.asStateFlow()

        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        private val _engineState = MutableStateFlow(EngineState.ModelReady)
        val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

        private val _streamingResponse = MutableStateFlow<String?>(null)
        val streamingResponse: StateFlow<String?> = _streamingResponse.asStateFlow()

        private val _toolExecutionState = MutableStateFlow<String?>(null)
        val toolExecutionState: StateFlow<String?> = _toolExecutionState.asStateFlow()

        private val _agentStep = MutableStateFlow<AgentStep?>(null)
        val agentStep: StateFlow<AgentStep?> = _agentStep.asStateFlow()

        init {
            viewModelScope.launch {
                repository.getMessages().collect { msgs -> _messages.value = msgs }
            }
        }

        fun setEngineState(state: EngineState) { _engineState.value = state }
        fun onVoiceInput(transcript: String) = processInput(transcript)
        fun onTextInput(text: String) = processInput(text)

        private fun processInput(input: String) {
            viewModelScope.launch {
                repository.saveMessage(Message(role = MessageRole.USER, content = input))
                _isProcessing.value = true
                try {
                    val finalText = orchestrator.runLoop(
                        userInput = input,
                        onTokenChunk = { chunk ->
                            _streamingResponse.value = (_streamingResponse.value ?: "") + chunk
                        },
                        onToolStart = { toolName ->
                            _streamingResponse.value = null
                            _agentStep.value = AgentStep(current = 0, max = AgentOrchestrator.MAX_STEPS)
                            _toolExecutionState.value = toolName
                        },
                        onToolEnd = {
                            _toolExecutionState.value = null
                            _agentStep.value = null
                        },
                        onStepChange = { current, max ->
                            _agentStep.value = AgentStep(current = current, max = max)
                        },
                    )
                    if (finalText.isNotEmpty()) {
                        repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = finalText))
                    }
                } catch (e: Exception) {
                    _toolExecutionState.value = null
                    _agentStep.value = null
                    repository.saveMessage(
                        Message(role = MessageRole.ASSISTANT, content = "⚠️ ${buildErrorMessage(e)}")
                    )
                } finally {
                    _streamingResponse.value = null
                    _toolExecutionState.value = null
                    _agentStep.value = null
                    _isProcessing.value = false
                }
            }
        }

        private fun buildErrorMessage(e: Exception): String = when {
            e.message?.contains("606") == true ->
                "Gemma 4 isn't ready yet — opt into AICore Developer Preview and try again."
            e.message?.contains("not loaded") == true ||
            e.message?.contains("not initialized") == true ->
                "The AI model isn't loaded yet — give it a moment and try again."
            e.message?.contains("shut down") == true ->
                "The engine is restarting — please try again."
            e.message?.contains("Failed to load model") == true ->
                "Couldn't load the local model. Ensure it downloaded correctly."
            else -> "Something went wrong: ${e.message?.take(120) ?: "unknown error"}"
        }
    }

    data class AgentStep(val current: Int, val max: Int)

    fun hasVelaUiPayload(content: String): Boolean =
        content.contains("\"type\":\"vela-ui\"") && VelaUiParser.parse(content) != null
    