package com.vela.app.ui.conversation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vela.app.ai.AgentRef

/**
 * Bottom sheet listing available agents for the current vault.
 *
 * Agents are autonomously invoked by the LLM via the `delegate` tool —
 * this sheet is informational, showing what agents the model can use.
 * [onInstall] opens the file-based agent installer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AgentsBottomSheet(
    agents: List<AgentRef>,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Available agents",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                "The LLM can autonomously delegate to these agents. " +
                "Add agents by dropping .md files into vault/.agents/",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (agents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "No agents loaded \u2014 select a vault to load foundation agents",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(agents, key = { it.name }) { agent ->
                        ListItem(
                            headlineContent = { Text(agent.name) },
                            supportingContent = {
                                Text(
                                    agent.description,
                                    maxLines = 2,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            },
                        )
                    }
                }
            }

            HorizontalDivider()

            // Add agent by installing a .md file
            TextButton(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Install agent from file")
            }
        }
    }
}
