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
    import kotlinx.coroutines.launch
    import org.json.JSONObject
    import javax.inject.Inject

    @HiltViewModel
    class ConversationViewModel @Inject constructor(
        private val session: AmplifierSession,
        private val repository: ConversationRepository,
        private val toolRegistry: ToolRegistry,
    ) : ViewModel() {

        private val _messages     = MutableStateFlow<List<Message>>(emptyList())
        val messages: StateFlow<List<Message>> = _messages.asStateFlow()

        private val _isProcessing = MutableStateFlow(false)
        val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

        private val _streamingResponse = MutableStateFlow<String?>(null)
        val streamingResponse: StateFlow<String?> = _streamingResponse.asStateFlow()

        val isConfigured: Boolean get() = session.isConfigured()

        init {
            viewModelScope.launch { repository.getMessages().collect { _messages.value = it } }
        }

        fun onVoiceInput(text: String) = processInput(text)
        fun onTextInput(text: String)  = processInput(text)
        fun setApiKey(key: String)     = session.setApiKey(key)

        fun clearHistory() {
            session.clearHistory()
            viewModelScope.launch { /* optionally wipe DB too */ }
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
                            // Insert a TOOL_CALL message at this point in the timeline.
                            // It will be updated in-place when the tool finishes.
                            val tool    = toolRegistry.find(name)
                            val summary = extractSummary(name, argsJson)
                            val meta    = toolMeta(
                                displayName = tool?.displayName ?: name,
                                icon        = tool?.icon ?: "🔧",
                                summary     = summary,
                                status      = "in_progress",
                            )
                            val msg = Message(
                                role     = MessageRole.TOOL_CALL,
                                content  = summary,
                                toolMeta = meta,
                            )
                            viewModelScope.launch { repository.saveMessage(msg) }
                                .also { /* keep id for update below — stored by convention: last in_progress for this name */ }
                        },
                        onToolEnd = { name, result ->
                            // Find the latest in_progress TOOL_CALL for this name and flip it to done
                            val existing = _messages.value.lastOrNull {
                                it.role == MessageRole.TOOL_CALL &&
                                metaStatus(it.toolMeta) == "in_progress" &&
                                metaField(it.toolMeta, "displayName").let { dn ->
                                    dn == (toolRegistry.find(name)?.displayName ?: name)
                                }
                            }
                            if (existing != null) {
                                val updated = buildString {
                                    val obj = runCatching { JSONObject(existing.toolMeta ?: "{}") }
                                        .getOrDefault(JSONObject())
                                    obj.put("status", "done")
                                    obj.put("resultSnippet", result.take(120).replace("\n", " "))
                                    append(obj.toString())
                                }
                                viewModelScope.launch { repository.updateToolMeta(existing.id, updated) }
                            }
                        },
                    ).collect { chunk ->
                        sb.append(chunk)
                        _streamingResponse.value = sb.toString()
                    }

                    val finalText = sb.toString().trim()
                    _streamingResponse.value = null     // clear streaming BEFORE DB save → no double-bubble
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

        private fun toolMeta(displayName: String, icon: String, summary: String, status: String): String =
            JSONObject()
                .put("displayName", displayName)
                .put("icon", icon)
                .put("summary", summary)
                .put("status", status)
                .toString()

        private fun metaStatus(toolMeta: String?): String =
            runCatching { JSONObject(toolMeta ?: "{}").optString("status", "") }.getOrDefault("")

        private fun metaField(toolMeta: String?, field: String): String =
            runCatching { JSONObject(toolMeta ?: "{}").optString(field, "") }.getOrDefault("")

        private fun extractSummary(name: String, argsJson: String): String = try {
            val obj = JSONObject(argsJson)
            sequenceOf("query", "url", "location", "expression", "command")
                .mapNotNull { key -> obj.optString(key).takeIf { it.isNotBlank() } }
                .firstOrNull() ?: name
        } catch (e: Exception) { name }
    }

    fun hasVelaUiPayload(content: String): Boolean =
        content.contains("\"type\":\"vela-ui\"") && VelaUiParser.parse(content) != null
    