    package com.vela.app.ui.conversation

    import androidx.lifecycle.ViewModel
    import androidx.lifecycle.viewModelScope
    import com.vela.app.a2ui.VelaUiParser
    import com.vela.app.ai.AmplifierSession
    import com.vela.app.ai.tools.ToolRegistry
    import com.vela.app.data.repository.ConversationRepository
    import com.vela.app.domain.model.Message
    import com.vela.app.domain.model.MessageRole
    import dagger.hilt.android.lifecycle.HiltViewModel
    import kotlinx.coroutines.flow.MutableStateFlow
    import kotlinx.coroutines.flow.StateFlow
    import kotlinx.coroutines.flow.asStateFlow
    import kotlinx.coroutines.flow.update
    import kotlinx.coroutines.launch
    import org.json.JSONObject
    import java.util.UUID
    import javax.inject.Inject

    // ─── Tool call block model ─────────────────────────────────────────────────

    /**
     * Represents one tool invocation shown persistently in the conversation.
     * Stays visible after the turn completes so the user can see what was looked up.
     */
    data class ToolCallBlock(
        val id: String = UUID.randomUUID().toString(),
        val toolName: String,
        val displayName: String,
        val icon: String,
        val summary: String,          // e.g. the search query or URL
        val status: Status = Status.IN_PROGRESS,
        val resultSnippet: String? = null,
    ) {
        enum class Status { IN_PROGRESS, DONE, ERROR }
    }

    // ─── ViewModel ────────────────────────────────────────────────────────────

    @HiltViewModel
    class ConversationViewModel @Inject constructor(
        private val session: AmplifierSession,
        private val repository: ConversationRepository,
        private val toolRegistry: ToolRegistry,
    ) : ViewModel() {

        private val _messages = MutableStateFlow<List<Message>>(emptyList())
        val messages: StateFlow<List<Message>> = _messages.asStateFlow()

        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        private val _streamingResponse = MutableStateFlow<String?>(null)
        val streamingResponse: StateFlow<String?> = _streamingResponse.asStateFlow()

        /** Persistent tool call blocks accumulated across the current conversation. */
        private val _toolCallBlocks = MutableStateFlow<List<ToolCallBlock>>(emptyList())
        val toolCallBlocks: StateFlow<List<ToolCallBlock>> = _toolCallBlocks.asStateFlow()

        val isConfigured: Boolean get() = session.isConfigured()

        init {
            viewModelScope.launch {
                repository.getMessages().collect { _messages.value = it }
            }
        }

        fun onVoiceInput(text: String) = processInput(text)
        fun onTextInput(text: String)  = processInput(text)
        fun setApiKey(key: String)     = session.setApiKey(key)

        fun clearHistory() {
            session.clearHistory()
            _toolCallBlocks.value = emptyList()
        }

        private fun processInput(input: String) {
            viewModelScope.launch {
                repository.saveMessage(Message(role = MessageRole.USER, content = input))
                _isProcessing.value = true
                val sb = StringBuilder()

                try {
                    session.runTurn(
                        userInput   = input,
                        onToolStart = { name, argsJson ->
                            val tool    = toolRegistry.find(name)
                            val summary = extractSummary(argsJson)
                            val block   = ToolCallBlock(
                                toolName    = name,
                                displayName = tool?.displayName ?: name,
                                icon        = tool?.icon ?: "🔧",
                                summary     = summary,
                            )
                            _toolCallBlocks.update { it + block }
                        },
                        onToolEnd = { name, result ->
                            val snippet = result.take(120).replace("\n", " ")
                            _toolCallBlocks.update { blocks ->
                                // Mark the most recent IN_PROGRESS block for this tool as DONE
                                val idx = blocks.indexOfLast {
                                    it.toolName == name && it.status == ToolCallBlock.Status.IN_PROGRESS
                                }
                                if (idx < 0) blocks
                                else blocks.toMutableList().also { list ->
                                    list[idx] = list[idx].copy(
                                        status        = ToolCallBlock.Status.DONE,
                                        resultSnippet = snippet,
                                    )
                                }
                            }
                        },
                    ).collect { chunk ->
                        sb.append(chunk)
                        _streamingResponse.value = sb.toString()
                    }

                    // ← Fix double-bubble: clear streaming BEFORE saving to DB.
                    // If we clear after saving, Room notifies observers synchronously and the
                    // saved bubble + streaming bubble are both visible for one frame.
                    val finalText = sb.toString().trim()
                    _streamingResponse.value = null
                    if (finalText.isNotEmpty()) {
                        repository.saveMessage(Message(role = MessageRole.ASSISTANT, content = finalText))
                    }

                } catch (e: Exception) {
                    _streamingResponse.value = null
                    repository.saveMessage(
                        Message(role = MessageRole.ASSISTANT,
                                content = "Error: ${e.message?.take(200) ?: "unknown"}"))
                } finally {
                    _isProcessing.value = false
                }
            }
        }

        // ── Helpers ───────────────────────────────────────────────────────────────

        /**
         * Extract the most meaningful string from tool args for the UI label.
         * e.g. {"query":"AI news"} → "AI news"
         *      {"url":"https://..."} → "https://..."
         */
        private fun extractSummary(argsJson: String): String = try {
            val obj = JSONObject(argsJson)
            sequenceOf("query", "url", "location", "expression", "command")
                .mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
                .firstOrNull() ?: argsJson.take(60)
        } catch (e: Exception) {
            argsJson.take(60)
        }
    }

    data class AgentStep(val current: Int, val max: Int)

    fun hasVelaUiPayload(content: String): Boolean =
        content.contains("\"type\":\"vela-ui\"") && VelaUiParser.parse(content) != null
    