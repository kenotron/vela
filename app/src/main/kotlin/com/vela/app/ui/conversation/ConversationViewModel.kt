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
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
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

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    val messages: StateFlow<List<Message>> = _activeConversationId
        .filterNotNull()
        .flatMapLatest { convId -> repository.getMessages(convId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val conversations: StateFlow<List<Conversation>> = repository.getAllConversations()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    init {
        viewModelScope.launch {
            val savedId = prefs.getString(KEY_ACTIVE_SESSION, null)
            if (savedId != null) _activeConversationId.value = savedId
            else createAndActivate(Conversation(title = "New Chat"))
        }
    }

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

    private fun processInput(input: String) {
        val convId = _activeConversationId.value ?: return
        viewModelScope.launch {
            // Save user message
            val turnStart = System.currentTimeMillis()
            repository.saveMessage(
                Message(conversationId = convId, role = MessageRole.USER, content = input,
                        timestamp = turnStart)
            )
            repository.touchConversation(convId)

            if (messages.value.count { it.role == MessageRole.USER } == 0) {
                val title = input.take(40).trim() + if (input.length > 40) "…" else ""
                repository.updateConversationTitle(convId, title)
            }

            _isProcessing.value = true
            val sb = StringBuilder()

            // Monotonic counter so tool calls and assistant message have
            // strictly increasing timestamps even if all arrive within 1ms.
            val clock = AtomicLong(turnStart + 1)

            // Track pending tool calls: toolName → (messageId, metaJson)
            // Carry the metaJson from onToolStart so onToolEnd never needs to
            // read from messages.value (which may not have the row yet).
            val pendingToolIds = mutableMapOf<String, Pair<String, String>>()

            try {
                val prior = messages.value

                session.runTurn(
                    priorMessages = prior,
                    userInput     = input,
                    onToolStart   = { name, argsJson ->
                        val tool    = toolRegistry.find(name)
                        val summary = extractSummary(name, argsJson)
                        val msgId   = UUID.randomUUID().toString()
                        val meta    = toolMeta(
                            displayName = tool?.displayName ?: name,
                            icon        = tool?.icon ?: "🔧",
                            summary     = summary,
                            status      = "in_progress",
                        )
                        // Store (id, meta) so onToolEnd never touches messages.value
                        pendingToolIds[name] = Pair(msgId, meta)

                        repository.saveMessage(
                            Message(
                                id             = msgId,
                                conversationId = convId,
                                role           = MessageRole.TOOL_CALL,
                                content        = summary,
                                // Strictly-increasing timestamp — tool calls always before assistant
                                timestamp      = clock.getAndIncrement(),
                                toolMeta       = meta,
                            )
                        )
                    },
                    onToolEnd = { name, result ->
                        val (msgId, existingMeta) = pendingToolIds.remove(name) ?: return@runTurn
                        val updated = JSONObject(existingMeta)
                            .put("status", "done")
                            .put("resultSnippet", result.take(120).replace("\n", " "))
                            .toString()
                        repository.updateToolMeta(msgId, updated)
                    },
                ).collect { chunk ->
                    sb.append(chunk)
                    _streamingResponse.value = sb.toString()
                }

                val finalText = sb.toString().trim()
                _streamingResponse.value = null
                if (finalText.isNotEmpty()) {
                    // Assistant message always has a timestamp AFTER all tool calls
                    repository.saveMessage(
                        Message(
                            conversationId = convId,
                            role           = MessageRole.ASSISTANT,
                            content        = finalText,
                            timestamp      = clock.getAndIncrement(),
                        )
                    )
                    repository.touchConversation(convId)
                }

            } catch (e: Exception) {
                _streamingResponse.value = null
                repository.saveMessage(
                    Message(
                        conversationId = convId,
                        role           = MessageRole.ASSISTANT,
                        content        = "Error: ${e.message?.take(200) ?: "unknown"}",
                        timestamp      = clock.getAndIncrement(),
                    )
                )
            } finally {
                _isProcessing.value = false
            }
        }
    }

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

    private fun extractSummary(name: String, argsJson: String) = try {
        val obj = JSONObject(argsJson)
        sequenceOf("query", "url", "location", "expression", "command")
            .mapNotNull { obj.optString(it).takeIf { v -> v.isNotBlank() } }
            .firstOrNull() ?: name
    } catch (e: Exception) { name }
}
