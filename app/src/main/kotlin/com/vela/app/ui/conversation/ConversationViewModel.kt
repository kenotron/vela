package com.vela.app.ui.conversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.data.db.ConversationDao
import com.vela.app.data.db.ConversationEntity
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.TurnEventDao
import com.vela.app.data.db.TurnEventEntity
import com.vela.app.data.db.TurnEntity
import com.vela.app.domain.model.Conversation
import com.vela.app.engine.InferenceEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceEngine: InferenceEngine,
    private val conversationDao: ConversationDao,
    private val turnDao: TurnDao,
    private val turnEventDao: TurnEventDao,
) : ViewModel() {

    companion object {
        private const val PREFS          = "amplifier_prefs"
        private const val KEY_ACTIVE_ID  = "active_conversation_id"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Active conversation ───────────────────────────────────────────────────

    private val _activeConvId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConvId.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = conversationDao.getAllConversations()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeTitle: StateFlow<String> = _activeConvId
        .filterNotNull()
        .flatMapLatest { id ->
            conversationDao.getAllConversations().map { list ->
                list.firstOrNull { it.id == id }?.title ?: "New Chat"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "New Chat")

    // ── Turns for the active conversation ─────────────────────────────────────

    /** All turns for the active conversation, live from Room. */
    val turns: StateFlow<List<TurnEntity>> = _activeConvId
        .filterNotNull()
        .flatMapLatest { convId -> turnDao.getTurnsForConversation(convId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Events for the most recent running turn — shown as the "live" turn. */
    private val _activeTurnId = MutableStateFlow<String?>(null)
    val activeTurnId: StateFlow<String?> = _activeTurnId.asStateFlow()

    val activeTurnEvents: StateFlow<List<TurnEventEntity>> = _activeTurnId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList())
            else turnEventDao.getEventsForTurn(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Streaming text (in-memory, from InferenceEngine) ─────────────────────

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    val isProcessing: StateFlow<Boolean> = _activeTurnId
        .map { id -> id != null && inferenceEngine.isRunning(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val isConfigured: Boolean get() = context
        .getSharedPreferences("amplifier_prefs", Context.MODE_PRIVATE)
        .getString("anthropic_api_key", "").orEmpty().isNotBlank()

    init {
        // Restore active conversation or create first one
        viewModelScope.launch {
            val savedId = prefs.getString(KEY_ACTIVE_ID, null)
            if (savedId != null) _activeConvId.value = savedId
            else newSession()
        }

        // Collect streaming tokens from the engine's SharedFlow
        viewModelScope.launch {
            inferenceEngine.streamingText.collect { (turnId, token) ->
                if (turnId == _activeTurnId.value) {
                    _streamingText.value = (_streamingText.value ?: "") + token
                }
            }
        }

        // Clear streaming indicator when a turn completes
        viewModelScope.launch {
            inferenceEngine.turnComplete.collect { turnId ->
                if (turnId == _activeTurnId.value) {
                    _streamingText.value = null
                    _activeTurnId.value  = null
                }
            }
        }
    }

    // ── Public actions ────────────────────────────────────────────────────────

    fun onTextInput(text: String)  = processInput(text)
    fun onVoiceInput(text: String) = processInput(text)

    fun setApiKey(key: String) {
        context.getSharedPreferences("amplifier_prefs", Context.MODE_PRIVATE)
            .edit().putString("anthropic_api_key", key).apply()
    }

    fun newSession() {
        viewModelScope.launch {
            val conv = ConversationEntity(
                id        = UUID.randomUUID().toString(),
                title     = "New Chat",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            conversationDao.insert(conv)
            _activeConvId.value = conv.id
            prefs.edit().putString(KEY_ACTIVE_ID, conv.id).apply()
        }
    }

    fun switchSession(id: String) {
        _activeConvId.value = id
        _streamingText.value = null
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            turnDao.deleteForConversation(id)
            conversationDao.delete(id)
            if (_activeConvId.value == id) newSession()
        }
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    private fun processInput(input: String) {
        val convId = _activeConvId.value ?: return

        // Auto-title from first message
        viewModelScope.launch {
            val isFirst = turnDao.getTurnsForConversation(convId).first().isEmpty()
            if (isFirst) {
                val title = input.take(40).trim() + if (input.length > 40) "…" else ""
                conversationDao.updateTitle(convId, title, System.currentTimeMillis())
            }
        }

        // Reset streaming state
        _streamingText.value = null

        // Delegate to InferenceEngine — runs in its own scope, survives ViewModel
        val turnId = inferenceEngine.startTurn(convId, input)
        _activeTurnId.value = turnId
    }

    // ── Mappers ───────────────────────────────────────────────────────────────

    private fun ConversationEntity.toDomain() = Conversation(id, title, createdAt, updatedAt)
}
