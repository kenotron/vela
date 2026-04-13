package com.vela.app.ui.conversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.AmplifierSession
import com.vela.app.ai.tools.ToolRegistry
import com.vela.app.data.repository.ConversationRepository
import com.vela.app.domain.model.Conversation
import com.vela.app.domain.model.Message
import com.vela.app.domain.model.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val session: AmplifierSession,
    private val repository: ConversationRepository,
    private val toolRegistry: ToolRegistry,
) : ViewModel() {

    companion object {
        private const val PREFS = "amplifier_prefs"
        private const val KEY_ACTIVE_SESSION = "active_session_id"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Active session ────────────────────────────────────────────────────────

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    /** Messages for the active conversation, live from Room. */
    val messages: StateFlow<List<Message>> = _activeConversationId
        .filterNotNull()
        .flatMapLatest { convId -> repository.getMessages(convId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** All conversations for the session list. */
    val conversations: StateFlow<List<Conversation>> = repository.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Title of the active conversation. */
    val activeTitle: StateFlow<String> = _activeConversationId
        .filterNotNull()
        .flatMapLatest { id ->
            repository.getAllConversations().map { list ->
                list.firstOrNull { it.id == id }?.title ?: "New Chat"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "New Chat")

    private val _isProcessing     = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _streamingResponse = MutableStateFlow<String?>(null)
    val streamingResponse: StateFlow<String?> = _streamingResponse.asStateFlow()

    val isConfigured: Boolean get() = session.isConfigured()

    // ── Init: restore or create the active session ────────────────────────────

    init {
        viewModelScope.launch {
            val savedId = prefs.getString(KEY_ACTIVE_SESSION, null)
            if (savedId != null) {
                _activeConversationId.value = savedId
            } else {
                createAndActivate(Conversation(title = "New Chat"))
            }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    fun onTextInput(text: String)  = processInput(text)
    fun onVoiceInput(text: String) = processInput(text)
    fun setApiKey(key: String)     = session.setApiKey(key)

    fun newSession() {
        viewModelScope.launch { createAndActivate(Conversation(title = "New Chat")) }
    }

    fun switchSession(id: String) {
        _activeConversationId.value = id
        prefs.edit().putString(KEY_ACTIVE_SESSION, id).apply()
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_activeConversationId.value == id) newSession()
        }
    }

    // ── Message processing ────────────────────────────────────────────────────

    private fun processInput(input: String) {
        val convId = _activeConversationId.value ?: return
        viewModelScope.launch {
            // Save user message first
            repository.saveMessage(
                Message(conversationId = convId, role = MessageRole.USER, content = input)
            )
            repository.touchConversation(convId)

            // Auto-title from first user message
            if (messages.value.count { it.role == MessageRole.USER } == 0) {
                val title = input.take(40).trim() + if (input.length > 40) "…" else ""
                repository.updateConversationTitle(convId, title)
            }

            _isProcessing.value = true
            val sb = StringBuilder()

            try {
                // Build history from DB messages — this is the source of truth.
                // AmplifierSession is stateless; we pass the full history each time.
                val prior = messages.value  // already loaded from Room

                session.runTurn(
                    priorMessages = prior,
                    userInput     = input,
                    onToolStart   = { name, argsJson ->
                        val tool    = toolRegistry.find(name)
                        val summary = extractSummary(name, argsJson)
                        val meta    = toolMeta(
                            displayName = tool?.displayName ?: name,
                            icon        = tool?.icon ?: "\uD83D\uDD27",
                            summary     = summary,
                            status      = "in_progress",
                        )
                        viewModelScope.launch {
                            repository.saveMessage(
                                Message(
                                    conversationId = convId,
                                    role     = MessageRole.TOOL_CALL,
                                    content  = summary,
                                    toolMeta = meta,
                                )
                            )
                        }
                    },
                    onToolEnd = { name, result ->
                        val existing = messages.value.lastOrNull {
                            it.role == MessageRole.TOOL_CALL &&
                            metaStatus(it.toolMeta) == "in_progress" &&
                            metaField(it.toolMeta, "displayName") ==
                                (toolRegistry.find(name)?.displayName ?: name)
                        }
                        if (existing != null) {
                            val updated = JSONObject(existing.toolMeta ?: "{}")
                                .put("status", "done")
                                .put("resultSnippet", result.take(120).replace("\n", " "))
                                .toString()
                            viewModelScope.launch { repository.updateToolMeta(existing.id, updated) }
                        }
                    },
                ).collect { chunk ->
                    sb.append(chunk)
                    _streamingResponse.value = sb.toString()
                }

                val finalText = sb.toString().trim()
                _streamingResponse.value = null
                if (finalText.isNotEmpty()) {
                    repository.saveMessage(
                        Message(
                            conversationId = convId,
                            role    = MessageRole.ASSISTANT,
                            content = finalText,
                        )
                    )
                    repository.touchConversation(convId)
                }

            } catch (e: Exception) {
                _streamingResponse.value = null
                repository.saveMessage(
                    Message(
                        conversationId = convId,
                        role    = MessageRole.ASSISTANT,
                        content = "Error: ${e.message?.take(200) ?: "unknown"}",
                    )
                )
            } finally {
                _isProcessing.value = false
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private suspend fun createAndActivate(conv: Conversation) {
        repository.createConversation(conv)
        _activeConversationId.value = conv.id
        prefs.edit().putString(KEY_ACTIVE_SESSION, conv.id).apply()
    }

    private fun toolMeta(displayName: String, icon: String, summary: String, status: String) =
        JSONObject()
            .put("displayName", displayName).put("icon", icon)
            .put("summary", summary).put("status", status)
            .toString()

    private fun metaStatus(toolMeta: String?) =
        runCatching { JSONObject(toolMeta ?: "{}").optString("status", "") }.getOrDefault("")

    private fun metaField(toolMeta: String?, field: String) =
        runCatching { JSONObject(toolMeta ?: "{}").optString(field, "") }.getOrDefault("")

    private fun extractSummary(name: String, argsJson: String) = try {
        val obj = JSONObject(argsJson)
        sequenceOf("query", "url", "location", "expression", "command")
            .mapNotNull { obj.optString(it).takeIf { v -> v.isNotBlank() } }
            .firstOrNull() ?: name
    } catch (e: Exception) { name }
}
