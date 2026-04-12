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
     * Name of the tool currently executing between two inference passes.
     * Shown as "🔧 Using search_web…" chip while the tool runs. Null when idle.
     */
    private val _toolExecutionState = MutableStateFlow<String?>(null)
    val toolExecutionState: StateFlow<String?> = _toolExecutionState.asStateFlow()

    /**
     * Current iteration index of the agentic loop (1-based) while it is running.
     * Null when not in a multi-step loop. Used by the UI to show "Step 2/4".
     */
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
                val finalText = agentLoop(input)
                if (finalText.isNotEmpty()) {
                    ttsEngine.speak(finalText)
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

    /**
     * The agentic loop — this IS the answer to "is there an agentic loop here?"
     *
     * Each iteration:
     *   1. Stream the model's response.
     *   2. If the response is a tool call JSON → execute the tool → append result → repeat.
     *   3. If the response is plain text (or Vela-UI JSON) → done, return it.
     *
     * Capped at [MAX_STEPS] to prevent runaway loops and to stay within Gemma 4's
     * 4000-token context budget. Each iteration adds ~600-800 tokens to the prompt,
     * so 4 steps fits comfortably in the budget.
     *
     * The running prompt accumulates the full conversation history so Gemma 4 can
     * use the results of previous tool calls when deciding what to do next:
     *
     *   [System + tools]
     *   User: What does the BBC say about Gemma 4?
     *   Vela: {"tool":"search_web","args":{"query":"BBC Gemma 4 2026"}}
     *   Tool result: • Google releases Gemma 4... URL: https://bbc.com/...
     *   Vela: {"tool":"fetch_url","args":{"url":"https://bbc.com/..."}}
     *   Tool result: Fetched https://bbc.com/...: Google today released Gemma 4...
     *   Vela: According to the BBC, Google released Gemma 4 today with...
     */
    private suspend fun agentLoop(input: String): String {
        var prompt = VelaPromptBuilder.buildWithTools(input, toolRegistry.all())
        var lastResponse = ""

        for (step in 1..MAX_STEPS) {
            // Stream next model response
            lastResponse = streamAndCollect(prompt)

            // Check for a tool call
            val toolCall = ToolCallParser.parse(lastResponse)
            if (toolCall == null || !toolRegistry.contains(toolCall.toolName)) {
                // No (valid) tool call — model has given us the final answer
                return lastResponse
            }

            // Show step indicator in the UI
            _streamingResponse.value = null
            _agentStep.value = AgentStep(current = step, max = MAX_STEPS)
            _toolExecutionState.value = toolCall.toolName

            // Execute the tool
            val toolResult = try {
                toolRegistry.execute(toolCall.toolName, toolCall.args)
            } catch (e: Exception) {
                "Error running ${toolCall.toolName}: ${e.message?.take(120)}"
            }

            _toolExecutionState.value = null
            _agentStep.value = null

            // Append this exchange to the growing prompt so the next inference sees it
            prompt = appendExchange(prompt, lastResponse, toolResult)
        }

        // Hit step limit — do one final inference and return whatever the model says
        return streamAndCollect(prompt)
    }

    /**
     * Append a completed tool-call exchange to the running prompt.
     * Strips the trailing "Vela:" suffix before appending to avoid duplication.
     */
    private fun appendExchange(prompt: String, toolCallJson: String, toolResult: String): String {
        val base = prompt.trimEnd().removeSuffix("Vela:").trimEnd()
        return "$base\nVela: $toolCallJson\nTool result: $toolResult\nVela:"
    }

    /** Stream [prompt] and collect all delta chunks into a trimmed String. */
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

    companion object {
        /**
         * Maximum tool calls per user turn.
         * 4 steps × ~700 tokens/step = ~2800 tokens consumed, leaving ~1200 for final answer.
         * Keeping this within Gemma 4 E2B's 4000-token preview limit.
         */
        const val MAX_STEPS = 4
    }
}

/** Describes which step of the agentic loop we are on. Shown in the UI. */
data class AgentStep(val current: Int, val max: Int)

/** True if [content] is a Vela-UI JSON payload to render with VelaUiSurface. */
fun hasVelaUiPayload(content: String): Boolean =
    content.contains("\"type\":\"vela-ui\"") && VelaUiParser.parse(content) != null
