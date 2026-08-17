package com.vela.app.ui.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * Renders live C2 tool activity with per-agent attribution, plus any
 * pending approval-gate prompts (lane 3.1's F2 gate) with touch
 * Accept/Decline controls. Voice confirmation (`ApprovalVoiceBridge`) is a
 * secondary channel wired outside this composable — touch is the primary
 * path rendered here.
 *
 * Every row and approval control carries a stable `testTag` so the item-5
 * five-way parallel delegation stress test (and any future instrumented
 * test) can assert zero cross-assignment by tag.
 */
@Composable
fun LiveActivityScreen(
    state: LiveActivityUiState,
    onApprove: (approvalId: String) -> Unit,
    onDecline: (approvalId: String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
        state.pendingApprovals.forEach { approval ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .testTag("approval_card_${approval.approvalId}"),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Approval requested: ${approval.toolName} (${approval.kind})")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = { onApprove(approval.approvalId) },
                            modifier = Modifier.testTag("approval_accept_${approval.approvalId}"),
                        ) {
                            Text("Accept")
                        }
                        Button(
                            onClick = { onDecline(approval.approvalId) },
                            modifier = Modifier.testTag("approval_decline_${approval.approvalId}"),
                        ) {
                            Text("Decline")
                        }
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(state.rows) { row ->
                val agentLabel = row.agentName ?: "main"
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .testTag("activity_row_${agentLabel}_${row.toolCallId}"),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("$agentLabel · ${row.toolName ?: "(unattributed)"}")
                        Text("${row.status}: ${row.detail}")
                    }
                }
            }
        }
    }
}
