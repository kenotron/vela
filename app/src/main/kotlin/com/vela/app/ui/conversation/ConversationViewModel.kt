package com.vela.app.ui.conversation

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.data.db.ConversationDao
import com.vela.app.data.db.ConversationEntity
import com.vela.app.data.db.TurnDao
import com.vela.app.data.db.TurnWithEvents
import com.vela.app.data.db.VaultEntity
import com.vela.app.domain.model.Conversation
import com.vela.app.engine.ContentBlock
import com.vela.app.engine.ContentBlockRef
import com.vela.app.engine.InferenceEngine
import com.vela.app.engine.buildContentBlockRefs
import com.vela.app.engine.resolveContentBlockRefs
import com.vela.app.engine.toApiJsonString
import com.vela.app.engine.toRefJsonString
import com.vela.app.vault.VaultRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
        private fun vaultSelKey(convId: String) = "vault_sel_$convId"
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

    // Pending transcript — set by RecordingScreen after AI transcription completes.
    // ConversationScreen saves this to a temp file and stages it as a composer attachment.
    private val _pendingTranscript = MutableStateFlow<String?>(null)
    val pendingTranscript: StateFlow<String?> = _pendingTranscript.asStateFlow()

    fun setPendingTranscript(text: String) { _pendingTranscript.value = text }
    fun consumePendingTranscript(): String? {
        val text = _pendingTranscript.value
        _pendingTranscript.value = null
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

    // ── Agent picker state ──
    private val _availableAgents = MutableStateFlow<List<com.vela.app.ai.AgentRef>>(emptyList())
    val availableAgents: StateFlow<List<com.vela.app.ai.AgentRef>> = _availableAgents.asStateFlow()

    private val _activeAgentName = MutableStateFlow<String?>(null)
    val activeAgentName: StateFlow<String?> = _activeAgentName.asStateFlow()

    /** Toggle: tapping the active agent clears it; tapping a different one selects it. */
    fun setActiveAgent(name: String?) {
        _activeAgentName.value = if (_activeAgentName.value == name) null else name
    }

    /**
     * Refresh the agent registry from the current active vault.
     * Called on session start and after the user installs a new agent file.
     */
    fun refreshAgents() {
        viewModelScope.launch(Dispatchers.IO) {
            val path = activeVaultPath() ?: run {
                _availableAgents.value = emptyList()
                return@launch
            }
            val json = com.vela.app.ai.AmplifierBridge.nativeListAgents(path)
            _availableAgents.value = com.vela.app.ai.AgentRef.parseJsonArray(json)
        }
    }

    /** Active vault filesystem root, or null if no vault is selected for the session. */
    fun activeVaultPath(): String? {
        val activeId = _sessionActiveVaultIds.value.firstOrNull() ?: return null
        return allVaults.value.firstOrNull { it.id == activeId }?.localPath
    }

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

        // Restore per-conversation vault selection when the active conversation changes.
        // Falls back to all enabled vaults for conversations with no saved preference.
        viewModelScope.launch {
            _activeConvId.filterNotNull().collect { convId ->
                _sessionActiveVaultIds.value = loadVaultSelection(convId)
                refreshAgents()
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
        // Persist the new selection so it survives conversation switches
        _activeConvId.value?.let { saveVaultSelection(it, _sessionActiveVaultIds.value) }
        refreshAgents()
    }

    // ── Vault selection persistence ───────────────────────────────────────────

    /**
     * Load the saved vault selection for [convId].
     * Falls back to all currently-enabled vaults for new/unseen conversations.
     */
    private fun loadVaultSelection(convId: String): Set<String> {
        val saved = prefs.getString(vaultSelKey(convId), null)
        if (saved != null) {
            // Return exactly what was saved, even if it's an empty set (user turned all off)
            return if (saved.isBlank()) emptySet()
                   else saved.split(",").filter { it.isNotBlank() }.toSet()
        }
        // No saved preference → sensible default: all currently-enabled vaults
        return allVaults.value.map { it.id }.toSet()
    }

    private fun saveVaultSelection(convId: String, ids: Set<String>) {
        prefs.edit().putString(vaultSelKey(convId), ids.joinToString(",")).apply()
    }

    /**
     * Send a message with optional file/image attachments.
     *
     * Stores only lightweight ContentBlockRef JSON in the DB (no base64) to prevent
     * SQLiteBlobTooBigException on large images/PDFs.  Base64 is resolved on-demand
     * at API call time — for the current turn and lazily when rebuilding history.
     */
    fun sendMessage(
        text: String,
        attachments: List<Pair<android.net.Uri, String>> = emptyList(),  // uri + mimeType
    ) {
        val convId = _activeConvId.value ?: return

        viewModelScope.launch(Dispatchers.IO) {
            // Update conversation title on the first turn
            val isFirst = turnDao.getTurnsWithEvents(convId).first().isEmpty()
            if (isFirst) {
                val title = text.take(40).trim() + if (text.length > 40) "…" else ""
                conversationDao.updateTitle(convId, title, System.currentTimeMillis())
            }

            // Build compact refs (no file bytes read) → safe to store in DB
            val refs = if (attachments.isNotEmpty()) {
                buildContentBlockRefs(context, text, attachments)
            } else emptyList()

            val refsJson: String? = if (refs.size > 1 || refs.any { it !is ContentBlockRef.Text }) {
                refs.toRefJsonString()
            } else null

            // Resolve refs → actual base64 blocks → only used for the API call, never stored
            val resolvedBlocks = if (refs.isNotEmpty()) resolveContentBlockRefs(context, refs) else emptyList()
            val apiContentJson: String? = if (resolvedBlocks.size > 1 ||
                    resolvedBlocks.any { it !is ContentBlock.Text }) {
                resolvedBlocks.toApiJsonString()
            } else null

            Log.d("ConversationViewModel",
                "sendMessage: attachments=${attachments.size} refs=${refs.size} " +
                "resolvedBlocks=${resolvedBlocks.size} " +
                "apiContentJson=${if (apiContentJson != null) "${apiContentJson.length}B" else "null"}")

            val effectiveInput = buildAgentScopedInput(_activeAgentName.value, text)
            val turnId = inferenceEngine.startTurn(
                conversationId  = convId,
                userMessage     = effectiveInput,
                userContentJson = refsJson,        // compact refs → stored in DB
                apiContentJson  = apiContentJson,  // resolved base64 → sent to API
                activeVaultIds  = _sessionActiveVaultIds.value,
            )
            _activeTurnId.value = turnId
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
