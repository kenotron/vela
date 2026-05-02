package com.vela.app.streaming

import android.util.Log
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.sessiondetail.SessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Application-scoped owner of all SSE streaming connections and session state.
 *
 * Uses a [SupervisorJob]-backed [CoroutineScope] so that one failed stream does not
 * cancel the others.  State is stored in [ConcurrentHashMap] for thread safety.
 *
 * ViewModels receive a reference via Hilt injection and subscribe to the [StateFlow]s
 * exposed by [SessionStreamingManager]; they never open SSE connections directly.
 */
@Singleton
class SessionStreamingManagerImpl @Inject constructor(
    private val amplifierd: AmplifierdRepository,
    private val nodeRegistry: SshNodeRegistry,
    private val transcriptNormalizer: SessionTranscriptNormalizer,
    private val sseNormalizer: SessionSseNormalizer,
) : SessionStreamingManager {

    // One failed stream must not cancel the others → SupervisorJob
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Aggregate flow consumed by SessionListViewModel and the foreground service
    private val _allFlows = MutableStateFlow<Map<String, SessionState>>(emptyMap())

    // Per-session nullable state flows; null = not yet started
    private val sessionFlows = ConcurrentHashMap<String, MutableStateFlow<SessionState?>>()

    // Active streaming jobs keyed by sessionId
    private val streamJobs = ConcurrentHashMap<String, Job>()

    // ── SessionStreamingManager ───────────────────────────────────────────────

    override fun getSessionFlow(sessionId: String): StateFlow<SessionState?> =
        sessionFlows.getOrPut(sessionId) { MutableStateFlow(null) }.asStateFlow()

    override fun getAllSessionFlows(): StateFlow<Map<String, SessionState>> =
        _allFlows.asStateFlow()

    override suspend fun startStreaming(sessionId: String, nodeId: String, projectName: String?) {
        // Idempotent: cancel any existing stream before starting a fresh one
        stopStreaming(sessionId)

        val node = nodeRegistry.cache.find { it.id == nodeId }
        if (node == null) {
            Log.w(TAG, "startStreaming: node $nodeId not found in registry, aborting")
            return
        }

        val client = amplifierd.clientForNode(node)
        val streamClient = amplifierd.streamClientForNode(node)
        if (client == null || streamClient == null) {
            Log.w(TAG, "startStreaming: could not build clients for node ${node.label}")
            return
        }

        // Load initial transcript (best-effort; stream carries any missing turns)
        val transcriptJson = try {
            client.getTranscriptJson(sessionId)
        } catch (e: Exception) {
            Log.w(TAG, "startStreaming: transcript fetch failed for $sessionId: ${e.message}")
            null
        }

        val initialTurns = transcriptJson?.let { transcriptNormalizer.normalize(it) } ?: emptyList()
        val lastUserMessage = initialTurns.lastOrNull { it.isUser }?.text

        updateState(
            sessionId,
            SessionState(
                sessionId = sessionId,
                nodeId = nodeId,
                status = SessionStatus.IDLE,
                turns = initialTurns,
                activeTurnIndex = null,
                pendingApproval = null,
                lastUserMessage = lastUserMessage,
                currentTodoActiveForm = null,
                projectName = projectName,
            ),
        )

        val job = scope.launch {
            try {
                streamClient.subscribeEvents(sessionId).collect { event ->
                    val current = sessionFlows[sessionId]?.value ?: return@collect
                    val updated = sseNormalizer.applyEvent(current, event)
                    updateState(sessionId, updated)
                }
            } catch (e: Exception) {
                Log.e(TAG, "startStreaming: stream error for session $sessionId", e)
                val current = sessionFlows[sessionId]?.value ?: return@launch
                updateState(sessionId, current.copy(status = SessionStatus.ERROR))
            }
        }
        streamJobs[sessionId] = job
    }

    override fun stopStreaming(sessionId: String) {
        streamJobs.remove(sessionId)?.cancel()
        Log.d(TAG, "stopStreaming: cancelled stream for $sessionId")
    }

    override suspend fun resumeSession(sessionId: String): Boolean {
        val nodeId = sessionFlows[sessionId]?.value?.nodeId ?: return false
        val node = nodeRegistry.cache.find { it.id == nodeId } ?: return false
        val client = amplifierd.clientForNode(node) ?: return false
        val success = client.resumeSession(sessionId)
        Log.d(TAG, "resumeSession: $sessionId success=$success")
        return success
    }

    override suspend fun sendMessage(sessionId: String, message: String): Boolean {
        val state = sessionFlows[sessionId]?.value ?: return false
        val node = nodeRegistry.cache.find { it.id == state.nodeId } ?: return false
        val client = amplifierd.clientForNode(node) ?: return false

        // Optimistic update: persist the user message so retry is possible even on failure
        updateState(sessionId, state.copy(lastUserMessage = message, status = SessionStatus.EXECUTING))

        return try {
            client.executeStream(sessionId, message)
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendMessage: failed for session $sessionId: ${e.message}")
            val current = sessionFlows[sessionId]?.value
            if (current != null) {
                updateState(sessionId, current.copy(status = SessionStatus.ERROR))
            }
            false
        }
    }

    override suspend fun retryLastMessage(sessionId: String): Boolean {
        val message = sessionFlows[sessionId]?.value?.lastUserMessage
        if (message.isNullOrBlank()) {
            Log.w(TAG, "retryLastMessage: no lastUserMessage stored for $sessionId")
            return false
        }
        return sendMessage(sessionId, message)
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun updateState(sessionId: String, state: SessionState) {
        sessionFlows.getOrPut(sessionId) { MutableStateFlow(null) }.value = state
        _allFlows.update { it + (sessionId to state) }
    }

    companion object {
        private const val TAG = "SessionStreamingMgr"
    }
}
