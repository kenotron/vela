package com.vela.app.ui.conversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.data.db.ConversationDao
import com.vela.app.data.db.ConversationEntity
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.TurnWithEvents
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
) : ViewModel() {

    companion object {
        private const val PREFS         = "amplifier_prefs"
        private const val KEY_ACTIVE_ID = "active_conversation_id"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _activeConvId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConvId.asStateFlow()

    val conversations: StateFlow<List<Conversation>> = conversationDao.getAllConversations()
        .map { it.map { e -> e.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val activeTitle: StateFlow<String> = _activeConvId
        .filterNotNull()
        .flatMapLatest { id ->
            conversationDao.getAllConversations().map { list ->
                list.firstOrNull { it.id == id }?.title ?: "New Chat"
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "New Chat")

    val turnsWithEvents: StateFlow<List<TurnWithEvents>> = _activeConvId
        .filterNotNull()
        .flatMapLatest { convId -> turnDao.getTurnsWithEvents(convId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * In-flight text not yet committed to a TurnEvent row.
     * Sourced directly from InferenceEngine — cleared when text is flushed to DB.
     * The streaming bubble shows this. It goes empty when text becomes a DB row.
     */
    val streamingText: StateFlow<Map<String, String>> = inferenceEngine.streamingText

    private val _activeTurnId = MutableStateFlow<String?>(null)
    val activeTurnId: StateFlow<String?> = _activeTurnId.asStateFlow()

    val isConfigured: Boolean
        get() = prefs.getString("anthropic_api_key", "").orEmpty().isNotBlank()

    init {
        viewModelScope.launch {
            val savedId = prefs.getString(KEY_ACTIVE_ID, null)
            if (savedId != null) _activeConvId.value = savedId else newSession()
        }

        viewModelScope.launch {
            inferenceEngine.turnComplete.collect { turnId ->
                if (_activeTurnId.value == turnId) _activeTurnId.value = null
            }
        }
    }

    fun onTextInput(text: String)  = processInput(text)
    fun onVoiceInput(text: String) = processInput(text)

    fun setApiKey(key: String) { prefs.edit().putString("anthropic_api_key", key).apply() }

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
        prefs.edit().putString(KEY_ACTIVE_ID, id).apply()
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            turnDao.deleteForConversation(id)
            conversationDao.delete(id)
            if (_activeConvId.value == id) newSession()
        }
    }

    private fun processInput(input: String) {
        val convId = _activeConvId.value ?: return
        viewModelScope.launch {
            val isFirst = turnDao.getTurnsWithEvents(convId).first().isEmpty()
            if (isFirst) {
                val title = input.take(40).trim() + if (input.length > 40) "…" else ""
                conversationDao.updateTitle(convId, title, System.currentTimeMillis())
            }
        }
        val turnId = inferenceEngine.startTurn(convId, input)
        _activeTurnId.value = turnId
    }

    private fun ConversationEntity.toDomain() = Conversation(id, title, createdAt, updatedAt)
}
