package com.vela.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.a2ui.VelaUiParser
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.VelaPromptBuilder
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
    private val ttsEngine: TtsEngine,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.ModelReady)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    /**
     * Live streaming text while Gemma 4 is generating a response.
     * Non-null while streaming is in progress; null when idle or when a completed
     * response has been committed to [messages].
     *
     * The UI shows this as a live "typing" bubble that grows token by token.
     */
    private val _streamingResponse = MutableStateFlow<String?>(null)
    val streamingResponse: StateFlow<String?> = _streamingResponse.asStateFlow()

    init {
        viewModelScope.launch {
            repository.getMessages().collect { msgs ->
                _messages.value = msgs
            }
        }
    }

    fun setEngineState(state: EngineState) {
        _engineState.value = state
    }

    fun onVoiceInput(transcript: String) = processInput(transcript)
    fun onTextInput(text: String) = processInput(text)

    private fun processInput(input: String) {
        viewModelScope.launch {
            repository.saveMessage(Message(role = MessageRole.USER, content = input))
            _isProcessing.value = true

            val prompt = VelaPromptBuilder.build(input)
            val accumulated = StringBuilder()

            try {
                gemmaEngine.streamText(prompt).collect { chunk ->
                    accumulated.append(chunk)
                    _streamingResponse.value = accumulated.toString()
                }

                val responseText = accumulated.toString().trim()
                if (responseText.isNotEmpty()) {
                    ttsEngine.speak(responseText)
                    repository.saveMessage(
                        Message(role = MessageRole.ASSISTANT, content = responseText)
                    )
                }
            } catch (e: Exception) {
                val errorMsg = buildErrorMessage(e)
                repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = "⚠️ $errorMsg"))
            } finally {
                _streamingResponse.value = null
                _isProcessing.value = false
            }
        }
    }

    private fun buildErrorMessage(e: Exception): String = when {
        e.message?.contains("606") == true ->
            "Gemma 4 isn't ready yet on this device. Opt into the AICore Developer " +
            "Preview at developer.android.com/ai/aicore, or keep typing below."
        e.message?.contains("not loaded") == true ||
        e.message?.contains("not initialized") == true ->
            "The AI model isn't loaded yet — give it a moment and try again."
        e.message?.contains("shut down") == true ->
            "The engine is restarting — please try again."
        else -> "Something went wrong: ${e.message?.take(120) ?: "unknown error"}"
    }

    override fun onCleared() {
        super.onCleared()
        ttsEngine.shutdown()
    }
}

/**
 * Attempt to parse [content] as Vela-UI JSON.
 * Returns true if the message should be rendered as a structured VelaUiPayload.
 * Used in [ConversationScreen] to decide which renderer to use per message.
 */
fun hasVelaUiPayload(content: String): Boolean =
    content.contains("\"type\":\"vela-ui\"") && VelaUiParser.parse(content) != null
