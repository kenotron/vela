package com.vela.app.ui.sessiondetail

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.notifications.ApprovalNotificationHelper
import com.vela.app.settings.ApiKeyStore
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.streaming.SessionStreamingManager
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

@HiltViewModel
class SessionDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val ctx: Context,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
    private val apiKeyStore: ApiKeyStore,
    private val streamingManager: SessionStreamingManager,
) : ViewModel() {

    val sessionId: String = checkNotNull(savedStateHandle["sessionId"])
    val nodeId: String    = savedStateHandle["nodeId"] ?: ""

    val hasOpenAiKey: Boolean get() = apiKeyStore.openAiKey.isNotBlank()

    // ── Turn list ──────────────────────────────────────────────────────────

    private val _turns = MutableStateFlow<List<TurnContent>>(emptyList())
    val turns: StateFlow<List<TurnContent>> = _turns

    // ── Session status ─────────────────────────────────────────────────────

    private val _sessionStatus = MutableStateFlow(SessionStatus.IDLE)
    val sessionStatus: StateFlow<SessionStatus> = _sessionStatus

    /** isLoading = EXECUTING or RESUMING. Drives TypingIndicator and SessionInputBar. */
    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    // ── Session name ───────────────────────────────────────────────────────

    private val _sessionName = MutableStateFlow("")
    val sessionName: StateFlow<String> = _sessionName

    // ── Status message ─────────────────────────────────────────────────────

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage

    // ── Input text ─────────────────────────────────────────────────────────

    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    fun updateInputText(v: String) { _inputText.value = v }
    fun clearInputText() { _inputText.value = "" }

    // ── Image attachments ──────────────────────────────────────────────────

    private val _attachments = MutableStateFlow<List<Uri>>(emptyList())
    val attachments: StateFlow<List<Uri>> = _attachments

    fun addAttachment(uri: Uri) { _attachments.update { it + uri } }
    fun removeAttachment(uri: Uri) { _attachments.update { it - uri } }
    fun clearAttachments() { _attachments.value = emptyList() }

    // ── Approval request ───────────────────────────────────────────────────

    /** Pair of (approvalId, question). Non-null while waiting for user approval. */
    private val _approvalRequest = MutableStateFlow<Pair<String, String>?>(null)
    val approvalRequest: StateFlow<Pair<String, String>?> = _approvalRequest

    fun dismissApproval() { _approvalRequest.value = null }

    // ── Voice recording ────────────────────────────────────────────────────

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

    // ── Init: start streaming + subscribe to session state ─────────────────

    init {
        if (sessionId.isNotBlank() && nodeId.isNotBlank()) {
            viewModelScope.launch(Dispatchers.IO) {
                streamingManager.startStreaming(sessionId, nodeId, projectName = null)
            }
            viewModelScope.launch {
                streamingManager.getSessionFlow(sessionId).collect { state ->
                    state ?: return@collect

                    _turns.value = state.turns
                    _sessionStatus.value = state.status
                    _isLoading.value = state.status == SessionStatus.EXECUTING ||
                            state.status == SessionStatus.RESUMING

                    val pending = state.pendingApproval
                    val prevApproval = _approvalRequest.value
                    if (pending != null &&
                        (prevApproval == null || prevApproval.first != pending.id)
                    ) {
                        _approvalRequest.value = Pair(pending.id, pending.question)
                        ApprovalNotificationHelper.notify(ctx, sessionId, nodeId, pending.question)
                    } else if (pending == null && state.status == SessionStatus.IDLE) {
                        _approvalRequest.value = null
                    }

                    val prevName = _sessionName.value
                    if (state.sessionName != null && state.sessionName != prevName) {
                        _sessionName.value = state.sessionName
                        viewModelScope.launch(Dispatchers.IO) {
                            val node = registry.cache.find { it.id == nodeId } ?: return@launch
                            val client = amplifierd.clientForNode(node) ?: return@launch
                            try {
                                client.updateSessionName(sessionId, state.sessionName)
                            } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

    // ── Send message ───────────────────────────────────────────────────────

    fun sendMessage(message: String = _inputText.value, uris: List<Uri> = _attachments.value) {
        if (_sessionStatus.value != SessionStatus.IDLE || message.isBlank()) return
        clearInputText()
        clearAttachments()
        viewModelScope.launch(Dispatchers.IO) {
            streamingManager.sendMessage(sessionId, message)
        }
    }

    // ── Retry ──────────────────────────────────────────────────────────────

    fun retry() {
        viewModelScope.launch(Dispatchers.IO) {
            streamingManager.retryLastMessage(sessionId)
        }
    }

    // ── Approval gate ──────────────────────────────────────────────────────

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

    // ── Steer ──────────────────────────────────────────────────────────────

    fun steer(message: String) {
        if (message.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            val node = registry.cache.find { it.id == nodeId } ?: return@launch
            val client = amplifierd.clientForNode(node) ?: return@launch
            try {
                val queued = client.steer(sessionId, message)
                if (queued) {
                    _statusMessage.value = "Steering: \"${message.take(40)}${if (message.length > 40) "…" else ""}\""
                }
            } catch (e: Exception) {
                Log.w(TAG, "Steer failed: ${e.message}")
            }
        }
    }

    companion object { private const val TAG = "SessionDetailVM" }
}
