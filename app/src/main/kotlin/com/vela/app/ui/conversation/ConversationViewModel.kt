package com.vela.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.GemmaEngine
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationViewModel(
    private val gemmaEngine: GemmaEngine,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    fun onVoiceInput(audioPath: String) {
        viewModelScope.launch {
            val userMessage = Message(
                role = MessageRole.USER,
                content = "[Voice input: $audioPath]",
            )
            _messages.value = _messages.value + userMessage
            _isProcessing.value = true
            val response = gemmaEngine.processText(userMessage.content)
            _isProcessing.value = false
            val assistantMessage = Message(
                role = MessageRole.ASSISTANT,
                content = response,
            )
            _messages.value = _messages.value + assistantMessage
        }
    }
}
