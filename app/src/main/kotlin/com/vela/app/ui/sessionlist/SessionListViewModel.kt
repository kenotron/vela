package com.vela.app.ui.sessionlist

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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for Screen 3: Project Detail — Sessions List.
 *
 * Loads sessions from the amplifierd HTTP API for the given node and project.
 * [activeSessions] surfaces RUNNING/WAITING sessions; [recentSessions] surfaces DONE/ERROR.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
    private val amplifierd: AmplifierdRepository,
) : ViewModel() {

    val nodeId: String    = checkNotNull(savedStateHandle["nodeId"])
    val projectId: String = checkNotNull(savedStateHandle["projectId"])

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())

    /** Sessions currently RUNNING or WAITING. */
    val activeSessions: StateFlow<List<SessionSummary>> = _sessions
        .map { list -> list.filter { it.status == SessionStatus.RUNNING || it.status == SessionStatus.WAITING } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Sessions with status DONE or ERROR. */
    val recentSessions: StateFlow<List<SessionSummary>> = _sessions
        .map { list -> list.filter { it.status == SessionStatus.DONE || it.status == SessionStatus.ERROR } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { loadSessions() }

    fun loadSessions() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = amplifierd.clientFor(nodeId) ?: return@launch
                val raw = client.getSessions(projectId)
                _sessions.value = raw.map { s ->
                    SessionSummary(
                        id          = s.sessionId,
                        title       = s.sessionId.take(8),  // placeholder title
                        status      = when (s.status) {
                            "running" -> SessionStatus.RUNNING
                            "waiting" -> SessionStatus.WAITING
                            "error"   -> SessionStatus.ERROR
                            else      -> SessionStatus.DONE
                        },
                        modelName    = s.bundleName,
                        stepCount    = 0,
                        lastActiveMs = s.createdAt,
                    )
                }
            } catch (e: Exception) { /* silently fail — node might not be reachable */ }
        }
    }
}
