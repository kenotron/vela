package com.vela.app.ui.conversation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.a2ui.VelaUiParser
import com.vela.app.ai.GemmaEngine
import com.vela.app.ai.VelaPromptBuilder
import com.vela.app.ai.tools.ToolCallParser
import com.vela.app.ai.tools.ToolRegistry
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

enum class EngineState { ModelNotReady, ModelReady }

@HiltViewModel
class ConversationViewModel @Inject constructor(
    private val gemmaEngine: GemmaEngine,
    private val repository: ConversationRepository,
    private val ttsEngine: TtsEngine,
    private val toolRegistry: ToolRegistry,
) : ViewModel() {

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _engineState = MutableStateFlow(EngineState.ModelReady)
    val engineState: StateFlow<EngineState> = _engineState.asStateFlow()

    /** Live token stream while Gemma 4 is generating. Null when idle. */
    private val _streamingResponse = MutableStateFlow<String?>(null)
    val streamingResponse: StateFlow<String?> = _streamingResponse.asStateFlow()

    /**
     * Name of the tool currently being executed, e.g. "get_time".
     * Shown in the UI as a "🔧 Using get_time…" chip while the tool runs.
     * Null when no tool is executing.
     */
    private val _toolExecutionState = MutableStateFlow<String?>(null)
    val toolExecutionState: StateFlow<String?> = _toolExecutionState.asStateFlow()

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
                val finalText = runInferenceWithTools(input)
                if (finalText.isNotEmpty()) {
                    ttsEngine.speak(finalText)
                    repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = finalText))
                }
            } catch (e: Exception) {
                _toolExecutionState.value = null
                repository.saveMessage(
                    Message(role = MessageRole.ASSISTANT, content = "⚠️ ${buildErrorMessage(e)}")
                )
            } finally {
                _streamingResponse.value = null
                _toolExecutionState.value = null
                _isProcessing.value = false
            }
        }
    }

    /**
     * Run one turn of inference with optional tool use:
     *
     *   1. Build a tool-aware prompt and stream the first response.
     *   2. If the response is a JSON tool call, execute the tool and stream
     *      a second follow-up response with the result injected.
     *   3. Return the final plain-text (or Vela-UI JSON) response.
     *
     * Capped at one tool call per turn to keep latency predictable.
     */
    private suspend fun runInferenceWithTools(input: String): String {
        val prompt = VelaPromptBuilder.buildWithTools(input, toolRegistry.all())

        // — First inference —
        val firstResponse = streamAndCollect(prompt)

        val toolCall = ToolCallParser.parse(firstResponse)
        if (toolCall == null || !toolRegistry.contains(toolCall.toolName)) {
            // No tool call — return the direct answer
            return firstResponse
        }

        // — Tool execution —
        _streamingResponse.value = null
        _toolExecutionState.value = toolCall.toolName

        val toolResult = try {
            toolRegistry.execute(toolCall.toolName, toolCall.args)
        } catch (e: Exception) {
            "Error running ${toolCall.toolName}: ${e.message ?: "unknown"}"
        }
        _toolExecutionState.value = null

        // — Second inference: formulate final answer using tool result —
        val followUpPrompt = VelaPromptBuilder.buildToolResult(
            originalPrompt = prompt,
            toolCallJson = firstResponse,
            toolResult = toolResult,
        )
        return streamAndCollect(followUpPrompt)
    }

    /** Stream [prompt] and collect all delta chunks into a single String. */
    private suspend fun streamAndCollect(prompt: String): String {
        val accumulated = StringBuilder()
        gemmaEngine.streamText(prompt).collect { chunk ->
            accumulated.append(chunk)
            _streamingResponse.value = accumulated.toString()
        }
        _streamingResponse.value = null
        return accumulated.toString().trim()
    }

    private fun buildErrorMessage(e: Exception): String = when {
        e.message?.contains("606") == true ->
            "Gemma 4 isn't ready yet — opt into AICore Developer Preview and try again."
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

/** True if [content] is a Vela-UI JSON payload the screen should render with [VelaUiSurface]. */
fun hasVelaUiPayload(content: String): Boolean =
    content.contains("\"type\":\"vela-ui\"") && VelaUiParser.parse(content) != null
