package com.vela.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.IntentExtractor
import com.vela.app.audio.TtsEngine
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class EngineState {
    ModelNotReady,
    ModelReady,
}

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val gemmaEngine: GemmaEngine,
    private val repository: ConversationRepository,
    private val intentExtractor: IntentExtractor,
    private val ttsEngine: TtsEngine,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.ModelReady)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getMessages().collect { messages ->
                _messages.value = messages
            }
        }
    }

    fun setEngineState(state: EngineState) {
        _engineState.value = state
    }

    fun onVoiceInput(transcript: String) {
        viewModelScope.launch {
            val userMessage = Message(
                role = MessageRole.USER,
                content = transcript,
            )
            repository.saveMessage(userMessage)
            _isProcessing.value = true
            try {
                val intent = intentExtractor.extract(transcript)
                val enginePrompt = buildEnginePrompt(intent.action, intent.target, transcript)
                val response = gemmaEngine.processText(enginePrompt)
                ttsEngine.speak(response)
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

    internal fun buildEnginePrompt(action: String, target: String?, transcript: String): String =
        "User intent: action=$action, target=$target\nOriginal request: $transcript\n\nProvide a helpful, concise response."

    override fun onCleared() {
        super.onCleared()
        ttsEngine.shutdown()
    }
}
