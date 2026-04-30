package com.vela.app.ui.sessionlist

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vela.app.ssh.SshNodeRegistry
import com.vela.app.ui.sessiondetail.SessionSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * ViewModel for Screen 3: Project Detail — Sessions List.
 *
 * Sessions are served by the amplifierd HTTP API which does not exist yet.
 * [activeSessions] and [recentSessions] are empty placeholder StateFlows until
 * the API client is wired in a future phase.
 */
@HiltViewModel
class SessionListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SshNodeRegistry,
) : ViewModel() {

    val nodeId: String    = checkNotNull(savedStateHandle["nodeId"])
    val projectId: String = checkNotNull(savedStateHandle["projectId"])

    /** Sessions currently RUNNING or WAITING — placeholder: empty until HTTP API exists. */
    val activeSessions: StateFlow<List<SessionSummary>> = MutableStateFlow(emptyList())

    /** Sessions with status DONE or ERROR — placeholder: empty until HTTP API exists. */
    val recentSessions: StateFlow<List<SessionSummary>> = MutableStateFlow(emptyList())
}
