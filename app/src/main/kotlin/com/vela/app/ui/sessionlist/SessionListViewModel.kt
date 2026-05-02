package com.vela.app.ui.sessionlist

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.amplifierd.AmplifierdRepository
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Screen 3: Project Detail — Sessions List.
 *
 * Loads sessions from the vela plugin's project-scoped session store.
 * [activeSessions] surfaces EXECUTING/RESUMING sessions; [recentSessions] surfaces IDLE/ERROR.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
    private val bootstrapper: com.vela.app.ssh.NodeBootstrapper,
) : ViewModel() {

    val nodeId: String      = checkNotNull(savedStateHandle["nodeId"])
    val projectId: String   = checkNotNull(savedStateHandle["projectId"])
    /** Project name passed as a URL-encoded query parameter from NodeDetailScreen. */
    val projectName: String = savedStateHandle["projectName"] ?: ""
    /** Working directory for this project — sessions are launched from this path. */
    val workingDir: String  = savedStateHandle["workingDir"] ?: "~"

    /** Node domain object — used to build the AmplifierdClient without relying on registry.cache. */
    private val node = registry.allFlow()
        .map { list -> list.find { it.id == nodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Non-null immediately after [createSession] succeeds — consumed by the screen to navigate. */
    private val _createdSessionId = MutableStateFlow<String?>(null)
    val createdSessionId: StateFlow<String?> = _createdSessionId

    fun consumeCreatedSession() { _createdSessionId.value = null }

    /** In-memory cache of session transcript previews (first + last user message). */
    private val previewCache = mutableMapOf<String, Pair<String, String>>()

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** All sessions for this project, sorted by last activity. */
    val allSessions: StateFlow<List<SessionSummary>> = _sessions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Keep for any remaining references — both point at the same data
    val activeSessions: StateFlow<List<SessionSummary>> = _sessions
        .map { list -> list.filter { it.status == SessionStatus.EXECUTING || it.status == SessionStatus.RESUMING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val recentSessions: StateFlow<List<SessionSummary>> = _sessions
        .map { list -> list.filter { it.status == SessionStatus.IDLE || it.status == SessionStatus.ERROR } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            node.filterNotNull().first() // wait for node to load
            loadSessions()
        }
    }

    fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = amplifierd.clientForNode(node.value) ?: return@launch

                // 1. Load project-tracked sessions from the vela plugin endpoint.
                val (active, recent) = client.getSessions(projectId)
                val pluginSessions = active + recent

                // 2. Fetch native status for just the plugin sessions (not all amplifierd sessions)
                val nativeById = mutableMapOf<String, com.vela.app.amplifierd.AmplifierdSession>()
                pluginSessions.forEach { s ->
                    try {
                        val native = client.getSessionStatus(s.sessionId)
                        if (native != null) nativeById[s.sessionId] = native
                    } catch (_: Exception) {}
                }

                // Only show Vela-owned sessions (no native merge)
                val summaries = pluginSessions
                    .filter { nativeById.containsKey(it.sessionId) } // skip sessions that no longer exist
                    .map { s ->
                        val realStatus = nativeById[s.sessionId]?.status ?: "completed"
                        SessionSummary(
                            id           = s.sessionId,
                            title        = s.title.ifBlank { "" },
                            status       = when (realStatus) {
                                "executing"        -> SessionStatus.EXECUTING
                                "idle"             -> SessionStatus.IDLE
                                "failed", "error"  -> SessionStatus.ERROR
                                else               -> SessionStatus.IDLE
                            },
                            modelName    = nativeById[s.sessionId]?.bundleName?.ifBlank { "amplifierd" } ?: "amplifierd",
                            stepCount    = 0,
                            lastActiveMs = nativeById[s.sessionId]?.lastActivity?.takeIf { it > 0 } ?: s.lastActivity,
                        )
                    }
                    .sortedByDescending { it.lastActiveMs }
                _sessions.value = summaries

                // Lazy-load first+last user message preview from transcript for each session
                summaries.take(20).forEach { summary ->
                    if (previewCache.containsKey(summary.id)) {
                        val (first, last) = previewCache[summary.id]!!
                        _sessions.value = _sessions.value.map {
                            if (it.id == summary.id) it.copy(preview = first, lastUserMessage = last) else it
                        }
                    } else {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val (first, last) = client.getSessionPreview(summary.id)
                                previewCache[summary.id] = Pair(first, last)
                                _sessions.value = _sessions.value.map {
                                    if (it.id == summary.id) it.copy(preview = first, lastUserMessage = last) else it
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadSessions failed: ${e.message}")
            }
        }
    }

    fun createSession() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nodeObj = registry.cache.find { it.id == nodeId } ?: return@launch
                val client = amplifierd.clientForNode(nodeObj) ?: return@launch
                // mkdir -p + expand ~ on the remote node, get the real absolute path back
                val resolvedDir = bootstrapper.ensureDirectory(nodeObj, workingDir)
                val sessionId = client.createSession(projectId, resolvedDir, title = "")
                _createdSessionId.value = sessionId
                loadSessions() // refresh list
            } catch (e: Exception) {
                Log.w(TAG, "createSession failed: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "SessionListVM"
    }
}
