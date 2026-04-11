package com.vela.app.ui.conversation

    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.ai.GemmaEngine
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
        private val gemmaEngine: GemmaEngine,
        private val repository: ConversationRepository,
    ) : ViewModel() {

        private val _messages = MutableStateFlow<List<Message>>(emptyList())
        val messages: StateFlow<List<Message>> = _messages.asStateFlow()

        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        init {
            viewModelScope.launch {
                repository.getMessages().collect { messages ->
                    _messages.value = messages
                }
            }
        }

        fun onVoiceInput(audioPath: String) {
            viewModelScope.launch {
                val userMessage = Message(
                    role = MessageRole.USER,
                    content = "[Voice input: $audioPath]",
                )
                repository.saveMessage(userMessage)
                _isProcessing.value = true
                try {
                    val response = gemmaEngine.processText(userMessage.content)
                    val assistantMessage = Message(
                        role = MessageRole.ASSISTANT,
                        content = response,
                    )
                    repository.saveMessage(assistantMessage)
                } finally {
                    _isProcessing.value = false
                }
            }
        }
    }
    