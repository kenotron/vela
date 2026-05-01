package com.vela.app.ui.sessiondetail

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.amplifierd.StreamEvent
import com.vela.app.settings.ApiKeyStore
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.voice.AudioRecorder
import com.vela.app.voice.WhisperClient
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Screen 4: Session Detail — Turn History + Input.
 *
 * Responsibilities:
 *  - Load existing session transcript on init (Phase 7)
 *  - Send messages via SSE streaming (Phase 5)
 *  - Accumulate streaming tokens and tool calls into [turns]
 *  - Voice recording via AudioRecorder + Whisper transcription (Phase 3)
 *  - Image attachment state (Phase 4)
 *  - Approval request state (Phase 6)
 */
@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val ctx: Context,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
    private val apiKeyStore: ApiKeyStore,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    val nodeId: String    = savedStateHandle["nodeId"] ?: ""

    // ── Turn list ─────────────────────────────────────────────────────────────

    private val _turns = MutableStateFlow<List<TurnContent>>(emptyList())
    val turns: StateFlow<List<TurnContent>> = _turns

    // ── Streaming / loading ───────────────────────────────────────────────────

    private val _isStreaming = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isStreaming

    // ── Input text ────────────────────────────────────────────────────────────

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    fun updateInputText(v: String) { _inputText.value = v }
    fun clearInputText() { _inputText.value = "" }

    // ── Image attachments ─────────────────────────────────────────────────────

    private val _attachments = MutableStateFlow<List<Uri>>(emptyList())
    val attachments: StateFlow<List<Uri>> = _attachments

    fun addAttachment(uri: Uri) { _attachments.update { it + uri } }
    fun removeAttachment(uri: Uri) { _attachments.update { it - uri } }
    fun clearAttachments() { _attachments.value = emptyList() }

    // ── Approval request ──────────────────────────────────────────────────────

    /** Pair of (approvalId, question). Non-null while waiting for user approval. */
    private val _approvalRequest = MutableStateFlow<Pair<String, String>?>(null)
    val approvalRequest: StateFlow<Pair<String, String>?> = _approvalRequest

    fun dismissApproval() { _approvalRequest.value = null }

    // ── Voice recording ───────────────────────────────────────────────────────

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val audioRecorder = AudioRecorder(ctx)

    fun startVoiceRecording() {
        audioRecorder.start()
        _isRecording.value = true
    }

    fun stopVoiceRecording() {
        val file = audioRecorder.stop()
        _isRecording.value = false
        if (file == null || !file.exists()) return
        val openAiKey = apiKeyStore.openAiKey
        if (openAiKey.isBlank()) {
            // No key — append a placeholder hint
            _inputText.update { it + "[Set OPENAI_API_KEY in Settings to transcribe]" }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = WhisperClient(openAiKey).transcribe(file)
                _inputText.update { existing ->
                    if (existing.isBlank()) text else "$existing $text"
                }
            } catch (e: Exception) {
                Log.w(TAG, "Whisper transcription failed: ${e.message}")
            }
        }
    }

    // ── Init: load transcript ─────────────────────────────────────────────────

    init {
        if (sessionId.isNotBlank() && nodeId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                loadTranscript()
            }
        }
    }

    private suspend fun loadTranscript() {
        try {
            val node   = registry.cache.find { it.id == nodeId } ?: return
            val client = amplifierd.clientForNode(node) ?: return
            val turns  = client.getTranscript(sessionId)
            if (turns.isNotEmpty()) {
                _turns.value = turns
            }
        } catch (e: Exception) {
            Log.d(TAG, "Transcript not yet available: ${e.message}")
        }
    }

    // ── Send message + SSE streaming ──────────────────────────────────────────

    fun sendMessage(message: String = _inputText.value, uris: List<Uri> = _attachments.value) {
        if (_isStreaming.value || message.isBlank()) return
        clearInputText()
        clearAttachments()

        viewModelScope.launch(Dispatchers.IO) {
            _isStreaming.value = true

            // Add user turn immediately
            _turns.update { it + TurnContent(text = message, isUser = true) }

            val node = registry.cache.find { it.id == nodeId }
            if (node == null) {
                _isStreaming.value = false
                return@launch
            }
            val streamClient = amplifierd.streamClientForNode(node)
            if (streamClient == null) {
                _isStreaming.value = false
                return@launch
            }

            var currentAssistantText  = StringBuilder()
            val currentToolCalls      = mutableListOf<ToolCall>()
            var assistantTurnStarted  = false

            try {
                streamClient.stream(sessionId, message).collect { event ->
                    when (event) {
                        is StreamEvent.Token -> {
                            currentAssistantText.append(event.content)
                            if (!assistantTurnStarted) {
                                assistantTurnStarted = true
                                _turns.update {
                                    it + TurnContent(
                                        text      = currentAssistantText.toString(),
                                        isUser    = false,
                                        toolCalls = currentToolCalls.toList(),
                                    )
                                }
                            } else {
                                _turns.update { turns ->
                                    if (turns.isEmpty()) turns
                                    else turns.dropLast(1) + turns.last().copy(
                                        text = currentAssistantText.toString()
                                    )
                                }
                            }
                        }

                        is StreamEvent.ToolStart -> {
                            val tc = ToolCall(
                                name      = event.name,
                                result    = null,
                                isDone    = false,
                                isRunning = true,
                                durationMs = null,
                            )
                            currentToolCalls.add(tc)
                            if (!assistantTurnStarted) {
                                assistantTurnStarted = true
                                _turns.update {
                                    it + TurnContent(
                                        text      = currentAssistantText.toString(),
                                        isUser    = false,
                                        toolCalls = currentToolCalls.toList(),
                                    )
                                }
                            } else {
                                _turns.update { turns ->
                                    if (turns.isEmpty()) turns
                                    else turns.dropLast(1) + turns.last().copy(
                                        toolCalls = currentToolCalls.toList()
                                    )
                                }
                            }
                        }

                        is StreamEvent.ToolResult -> {
                            val idx = currentToolCalls.indexOfLast { !it.isDone }
                            if (idx >= 0) {
                                currentToolCalls[idx] = currentToolCalls[idx].copy(
                                    result    = event.output.take(400),
                                    isDone    = true,
                                    isRunning = false,
                                )
                                _turns.update { turns ->
                                    if (turns.isEmpty()) turns
                                    else turns.dropLast(1) + turns.last().copy(
                                        toolCalls = currentToolCalls.toList()
                                    )
                                }
                            }
                        }

                        is StreamEvent.ApprovalRequest -> {
                            _approvalRequest.value = Pair(event.id, event.question)
                        }

                        is StreamEvent.Done  -> { /* handled after loop */ }
                        is StreamEvent.Error -> {
                            Log.w(TAG, "Stream error: ${event.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Stream collection error: ${e.message}")
            } finally {
                _isStreaming.value = false
            }
        }
    }

    // ── Approval gate ─────────────────────────────────────────────────────────

    fun approveRequest(approvalId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val node = registry.cache.find { it.id == nodeId } ?: return@launch
                val client = amplifierd.clientForNode(node) ?: return@launch
                client.approveSession(sessionId, approvalId, approved = true)
            } catch (e: Exception) {
                Log.w(TAG, "Approve failed: ${e.message}")
            }
            _approvalRequest.value = null
        }
    }

    fun denyRequest(approvalId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val node = registry.cache.find { it.id == nodeId } ?: return@launch
                val client = amplifierd.clientForNode(node) ?: return@launch
                client.approveSession(sessionId, approvalId, approved = false)
            } catch (e: Exception) {
                Log.w(TAG, "Deny failed: ${e.message}")
            }
            _approvalRequest.value = null
        }
    }

    companion object { private const val TAG = "SessionDetailVM" }
}
