package com.vela.app.ui.activity

import com.vela.events.ActivityFeed
import com.vela.events.C2Event
import com.vela.events.C2EventClient
import com.vela.events.ToolCallCorrelator.AttributedActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * One row of live activity, keyed by (agentName, toolCallId) so parallel
 * delegation renders each sub-agent's tool activity distinctly (item 2/5).
 */
data class ActivityRow(
    val agentName: String?,
    val toolCallId: String?,
    val toolName: String?,
    val status: String,
    val detail: String,
)

data class PendingApproval(
    val approvalId: String,
    val toolName: String,
    val kind: String,
)

data class LiveActivityUiState(
    val rows: List<ActivityRow> = emptyList(),
    val pendingApprovals: List<PendingApproval> = emptyList(),
    val connectionState: C2EventClient.ConnectionState = C2EventClient.ConnectionState.DISCONNECTED,
)

/**
 * Wires [C2EventClient] + [ActivityFeed] into a `StateFlow` the Compose
 * screen renders. Production wiring of a real `baseUrl`/`bearerToken` is a
 * residual for this lane — see the goal file's Task 2 note; an app-level
 * config/DI source for those values doesn't exist yet.
 */
class LiveActivityViewModel(
    private val client: C2EventClient,
    private val activityFeed: ActivityFeed = ActivityFeed(),
) {
    private val _uiState = MutableStateFlow(LiveActivityUiState())
    val uiState: StateFlow<LiveActivityUiState> = _uiState.asStateFlow()

    private val rowsByKey = linkedMapOf<Pair<String?, String?>, ActivityRow>()
    private val pendingByApprovalId = linkedMapOf<String, PendingApproval>()

    fun start(scope: CoroutineScope, baseUrl: String, bearerToken: String) {
        scope.launch { client.connect(baseUrl, bearerToken) }
        scope.launch {
            client.connectionState.collect { state ->
                _uiState.value = _uiState.value.copy(connectionState = state)
            }
        }
        scope.launch {
            activityFeed.process(client.events).collect { activity -> onActivity(activity) }
        }
    }

    private fun onActivity(activity: AttributedActivity) {
        when (val event = activity.event) {
            is C2Event.ToolStarted -> upsertRow(activity, status = "started", detail = event.name)
            is C2Event.ToolCompleted -> upsertRow(
                activity,
                status = "completed",
                detail = "${event.name} (${event.durationMs}ms)",
            )
            is C2Event.Progress -> upsertRow(activity, status = "progress", detail = event.message)
            is C2Event.ThinkingFinal -> upsertRow(activity, status = "thinking", detail = event.text)
            is C2Event.ApprovalRequested -> {
                pendingByApprovalId[event.approvalId] = PendingApproval(
                    approvalId = event.approvalId,
                    toolName = event.toolName,
                    kind = event.kind,
                )
                emit()
            }
            else -> Unit
        }
    }

    private fun upsertRow(activity: AttributedActivity, status: String, detail: String) {
        val key = activity.agentName to activity.toolCallId
        rowsByKey[key] = ActivityRow(
            agentName = activity.agentName,
            toolCallId = activity.toolCallId,
            toolName = activity.toolName,
            status = status,
            detail = detail,
        )
        emit()
    }

    fun onApprovalResolved(approvalId: String) {
        pendingByApprovalId.remove(approvalId)
        emit()
    }

    private fun emit() {
        _uiState.value = _uiState.value.copy(
            rows = rowsByKey.values.toList(),
            pendingApprovals = pendingByApprovalId.values.toList(),
        )
    }
}
