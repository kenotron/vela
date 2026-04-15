package com.vela.app.ui.conversation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.data.db.ConversationDao
import com.vela.app.data.db.ConversationEntity
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.TurnWithEvents
import com.vela.app.data.db.VaultEntity
import com.vela.app.domain.model.Conversation
import com.vela.app.engine.InferenceEngine
import com.vela.app.vault.VaultRegistry
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
    private val vaultRegistry: VaultRegistry,
) : ViewModel() {

    companion object {
        private const val PREFS         = "amplifier_prefs"
        private const val KEY_ACTIVE_ID = "active_conversation_id"
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _activeConvId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConvId.asStateFlow()

    // Pending input — set by recording/share flows to pre-stage a message
    private val _pendingInput = MutableStateFlow<String?>(null)
    val pendingInput: StateFlow<String?> = _pendingInput.asStateFlow()

    fun setPendingInput(text: String) { _pendingInput.value = text }
    fun consumePendingInput(): String? {
        val text = _pendingInput.value
        _pendingInput.value = null
        return text
    }

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

    /** All currently-enabled vaults — shown as chips in ConversationScreen. */
    val allVaults: StateFlow<List<VaultEntity>> = vaultRegistry.observeAll()
        .map { it.filter { v -> v.isEnabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sessionActiveVaultIds = MutableStateFlow<Set<String>>(emptySet())
    val sessionActiveVaultIds: StateFlow<Set<String>> = _sessionActiveVaultIds.asStateFlow()

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

        // Reset session vault selection when active conversation changes
        viewModelScope.launch {
            _activeConvId.filterNotNull().collect {
                _sessionActiveVaultIds.value = allVaults.value.map { v -> v.id }.toSet()
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

    fun switchToConversation(convId: String) {
        _activeConvId.value = convId
        prefs.edit().putString(KEY_ACTIVE_ID, convId).apply()
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            turnDao.deleteForConversation(id)
            conversationDao.delete(id)
            if (_activeConvId.value == id) newSession()
        }
    }

    fun toggleVaultForSession(vaultId: String) {
        _sessionActiveVaultIds.update { current ->
            if (vaultId in current) current - vaultId else current + vaultId
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
        val turnId = inferenceEngine.startTurn(convId, input, activeVaultIds = _sessionActiveVaultIds.value)
        _activeTurnId.value = turnId
    }

    private fun ConversationEntity.toDomain() = Conversation(id, title, createdAt, updatedAt)
}
