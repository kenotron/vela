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
 * [activeSessions] surfaces RUNNING/WAITING sessions; [recentSessions] surfaces DONE/ERROR.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
) : ViewModel() {

    val nodeId: String      = checkNotNull(savedStateHandle["nodeId"])
    val projectId: String   = checkNotNull(savedStateHandle["projectId"])
    /** Project name passed as a URL-encoded query parameter from NodeDetailScreen. */
    val projectName: String = savedStateHandle["projectName"] ?: ""

    /** Node domain object — used to build the AmplifierdClient without relying on registry.cache. */
    private val node = registry.allFlow()
        .map { list -> list.find { it.id == nodeId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Non-null immediately after [createSession] succeeds — consumed by the screen to navigate. */
    private val _createdSessionId = MutableStateFlow<String?>(null)
    val createdSessionId: StateFlow<String?> = _createdSessionId

    fun consumeCreatedSession() { _createdSessionId.value = null }

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** Sessions currently RUNNING or WAITING. */
    val activeSessions: StateFlow<List<SessionSummary>> = _sessions
        .map { list -> list.filter { it.status == SessionStatus.RUNNING || it.status == SessionStatus.WAITING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sessions with status DONE or ERROR. */
    val recentSessions: StateFlow<List<SessionSummary>> = _sessions
        .map { list -> list.filter { it.status == SessionStatus.DONE || it.status == SessionStatus.ERROR } }
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
                val pluginSessions: List<com.vela.app.amplifierd.AmplifierdSession> = try {
                    val (active, recent) = client.getSessions(projectId)
                    active + recent
                } catch (_: Exception) { emptyList() }

                // 2. Load recent sessions from amplifierd's native /sessions endpoint.
                //    This surfaces sessions created from the CLI, other apps, etc.
                val nativeSessions: List<com.vela.app.amplifierd.AmplifierdSession> = try {
                    client.getNativeSessions()
                } catch (_: Exception) { emptyList() }

                // 3. Merge and deduplicate by session_id (plugin record wins for title).
                val seen = mutableSetOf<String>()
                val merged = (pluginSessions + nativeSessions).filter { seen.add(it.sessionId) }

                _sessions.value = merged.map { s ->
                    SessionSummary(
                        id           = s.sessionId,
                        title        = s.title.ifBlank { s.sessionId.take(8) },
                        status       = when (s.status) {
                            "running"         -> SessionStatus.RUNNING
                            "waiting"         -> SessionStatus.WAITING
                            "error", "failed" -> SessionStatus.ERROR
                            else              -> SessionStatus.DONE
                        },
                        modelName    = s.bundleName.ifBlank { "amplifierd" },
                        stepCount    = 0,
                        lastActiveMs = s.lastActivity,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "loadSessions failed: ${e.message}")
            }
        }
    }

    /** Workspace directory for this node — used as cwd when creating sessions. */
    val workspaceDir: String
        get() = registry.cache.find { it.id == nodeId }?.workspaceDir ?: "~"

    fun createSession() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val nodeObj = registry.cache.find { it.id == nodeId } ?: return@launch
                val client = amplifierd.clientForNode(nodeObj) ?: return@launch
                val sessionId = client.createSession(projectId, nodeObj.workspaceDir)
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
