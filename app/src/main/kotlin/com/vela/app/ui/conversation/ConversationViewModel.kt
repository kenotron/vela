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
        processInput(transcript)
    }

    fun onTextInput(text: String) {
        processInput(text)
    }

    private fun processInput(input: String) {
        viewModelScope.launch {
            repository.saveMessage(Message(role = MessageRole.USER, content = input))
            _isProcessing.value = true
            try {
                val intent = intentExtractor.extract(input)
                val enginePrompt = buildEnginePrompt(intent.action, intent.target, input)
                val response = gemmaEngine.processText(enginePrompt)
                ttsEngine.speak(response)
                repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = response))
            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("606") == true ->
                        "Gemma 4 isn't ready yet on this device. Opt into the AICore Developer " +
                        "Preview at developer.android.com/ai/aicore, or keep typing below."
                    e.message?.contains("not loaded") == true ||
                    e.message?.contains("not initialized") == true ->
                        "The AI model isn't loaded yet — give it a moment and try again."
                    else -> "Something went wrong: ${e.message?.take(120) ?: "unknown error"}"
                }
                repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = "⚠️ $msg"))
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
