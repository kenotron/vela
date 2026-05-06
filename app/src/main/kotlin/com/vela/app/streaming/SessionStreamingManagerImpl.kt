package com.vela.app.streaming

import android.util.Log
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TurnContent
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
        // If a stream job is already running for this session, leave it alone.
        // The ViewModel re-subscribes to the existing StateFlow and gets live state immediately.
        // Killing the job here would destroy the in-progress streaming turn and reload a
        // stale transcript that is missing the current turn's content.
        if (streamJobs[sessionId]?.isActive == true) {
            Log.d(TAG, "startStreaming: stream already active for $sessionId — skipping restart")
            return
        }

        // No active stream — clean up any completed/cancelled job
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

        // Only open SSE if the session is actively executing right now.
        // For IDLE sessions the transcript already has complete state.
        // sendMessage() will open a fresh stream() per execution.
        val serverStatus = try {
            client.getSessionStatus(sessionId)?.status
        } catch (_: Exception) { null }

        updateState(
            sessionId,
            SessionState(
                sessionId = sessionId,
                nodeId = nodeId,
                status = if (serverStatus == "executing") SessionStatus.EXECUTING else SessionStatus.IDLE,
                turns = initialTurns,
                activeTurnIndex = null,
                pendingApproval = null,
                lastUserMessage = lastUserMessage,
                currentTodoActiveForm = null,
                projectName = projectName,
            ),
        )

        if (serverStatus == "executing") {
            val job = scope.launch {
                try {
                    streamClient.subscribeEvents(sessionId).collect { event ->
                        val current = sessionFlows[sessionId]?.value ?: return@collect
                        val updated = sseNormalizer.applyEvent(current, event)
                        updateState(sessionId, updated)
                    }
                    // subscribeEvents ended (session finished) — reload transcript so
                    // ToolResult blocks (tool output) are paired with their ToolUse blocks.
                    reloadTranscriptAfterCompletion(sessionId, nodeId)
                } catch (e: Exception) {
                    Log.e(TAG, "startStreaming: stream error for $sessionId", e)
                    val cur = sessionFlows[sessionId]?.value
                    if (cur != null) updateState(sessionId, cur.copy(status = SessionStatus.ERROR))
                }
            }
            streamJobs[sessionId] = job
        }
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
        val streamClient = amplifierd.streamClientForNode(node) ?: return false
        val client = amplifierd.clientForNode(node)

        // Ensure the session is loaded into amplifierd memory before streaming.
        // After a server restart sessions exist on disk but not in memory;
        // POST /sessions/{id}/resume reloads them so /execute/stream won't 404.
        // This is a no-op for already-active sessions and safe to call every time.
        client?.resumeSession(sessionId)

        // Optimistic update: add user turn to turns list + persist message for retry
        val userTurn = TurnContent(text = message, isUser = true)
        updateState(
            sessionId,
            state.copy(
                lastUserMessage = message,
                status = SessionStatus.EXECUTING,
                turns = state.turns + userTurn,
            ),
        )

        // Cancel any existing stream job (e.g. subscribeEvents from startStreaming)
        // and start a fresh stream() for this execution.
        // stream() opens SSE + POST + correlation_id filtering — no historical replay.
        stopStreaming(sessionId)

        val job = scope.launch {
            try {
                streamClient.stream(sessionId, message).collect { event ->
                    val current = sessionFlows[sessionId]?.value ?: return@collect
                    val updated = sseNormalizer.applyEvent(current, event)
                    updateState(sessionId, updated)
                }
                // stream() terminated normally (orchestrator:complete received).
                // Reload transcript to pair ToolUse blocks with their ToolResults.
                reloadTranscriptAfterCompletion(sessionId, state.nodeId)
            } catch (e: Exception) {
                Log.e(TAG, "sendMessage: stream error for $sessionId", e)
                val cur = sessionFlows[sessionId]?.value
                if (cur != null) updateState(sessionId, cur.copy(status = SessionStatus.ERROR))
            }
        }
        streamJobs[sessionId] = job
        return true
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

    /**
     * Reloads the transcript after an execution completes so that
     * [ContentBlock.ToolUse] entries are paired with their [ContentBlock.ToolResult]s.
     * Tool results live only in the transcript (role="tool" messages), never in SSE.
     */
    private suspend fun reloadTranscriptAfterCompletion(sessionId: String, nodeId: String) {
        val node = nodeRegistry.cache.find { it.id == nodeId } ?: return
        val client = amplifierd.clientForNode(node) ?: return
        val transcriptJson = try {
            client.getTranscriptJson(sessionId)
        } catch (_: Exception) { return }
        val freshTurns = transcriptNormalizer.normalize(transcriptJson)
        val current = sessionFlows[sessionId]?.value ?: return
        // Replace turns with transcript version — preserves status (IDLE already set by Done handler)
        updateState(sessionId, current.copy(turns = freshTurns))
    }

    companion object {
        private const val TAG = "SessionStreamingMgr"
    }
}
