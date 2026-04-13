    package com.vela.app.ui.conversation

    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.a2ui.VelaUiParser
    import com.vela.app.ai.AmplifierSession
    import com.vela.app.data.repository.ConversationRepository
    import com.vela.app.domain.model.Message
    import com.vela.app.domain.model.MessageRole
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.launch
    import javax.inject.Inject

    @HiltViewModel
    class ConversationViewModel @Inject constructor(
        private val session: AmplifierSession,
        private val repository: ConversationRepository,
    ) : ViewModel() {

        private val _messages = MutableStateFlow<List<Message>>(emptyList())
        val messages: StateFlow<List<Message>> = _messages.asStateFlow()

        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        private val _streamingResponse = MutableStateFlow<String?>(null)
        val streamingResponse: StateFlow<String?> = _streamingResponse.asStateFlow()

        /** Shown while a tool is executing between LLM calls. */
        private val _toolState = MutableStateFlow<String?>(null)
        val toolState: StateFlow<String?> = _toolState.asStateFlow()

        val isConfigured: Boolean get() = session.isConfigured()

        init {
            viewModelScope.launch {
                repository.getMessages().collect { _messages.value = it }
            }
        }

        fun onVoiceInput(text: String) = processInput(text)
        fun onTextInput(text: String) = processInput(text)

        fun setApiKey(key: String) = session.setApiKey(key)
        fun clearHistory() = session.clearHistory()

        private fun processInput(input: String) {
            viewModelScope.launch {
                repository.saveMessage(Message(role = MessageRole.USER, content = input))
                _isProcessing.value = true
                val sb = StringBuilder()
                try {
                    session.runTurn(input).collect { chunk ->
                        sb.append(chunk)
                        _streamingResponse.value = sb.toString()
                    }
                    val finalText = sb.toString().trim()
                    if (finalText.isNotEmpty()) {
                        repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = finalText))
                    }
                } catch (e: Exception) {
                    repository.saveMessage(
                        Message(role = MessageRole.ASSISTANT,
                                content = "Error: ${e.message?.take(120) ?: "unknown"}"))
                } finally {
                    _streamingResponse.value = null
                    _toolState.value = null
                    _isProcessing.value = false
                }
            }
        }
    }

    data class AgentStep(val current: Int, val max: Int)

    fun hasVelaUiPayload(content: String): Boolean =
        content.contains("\"type\":\"vela-ui\"") && VelaUiParser.parse(content) != null
    