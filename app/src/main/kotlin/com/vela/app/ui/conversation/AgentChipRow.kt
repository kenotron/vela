package com.vela.app.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.vela.app.ai.AgentRef

/**
 * Horizontal chip row showing every available agent for the active vault, plus
 * a trailing `[+]` chip that opens the agent install browser.
 *
 * Tapping a chip selects that agent for the next message; tapping the active
 * chip again clears the selection.
 *
 * Renders nothing when [agents] is empty (no vault active or no agents loaded).
 */
@Composable
internal fun AgentChipRow(
    agents: List<AgentRef>,
    activeAgent: String?,
    onAgentClick: (String?) -> Unit,
    onInstallClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (agents.isEmpty()) return

    LazyRow(
        modifier            = modifier,
        contentPadding      = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(items = agents, key = { it.name }) { agent ->
            FilterChip(
                selected = agent.name == activeAgent,
                onClick  = { onAgentClick(agent.name) },
                label    = {
                    Text(
                        text     = agent.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor     = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            )
        }
        // Trailing [+] chip — opens the install picker.
        item(key = "__install__") {
            FilterChip(
                selected = false,
                onClick  = onInstallClick,
                label    = { Icon(Icons.Default.Add, contentDescription = "Install agent") },
            )
        }
    }
}

@Preview(showBackground = true, name = "AgentChipRow — foundation agents")
@Composable
private fun AgentChipRowPreview() {
    val foundation = listOf(
        AgentRef("explorer",         "recon", emptyList()),
        AgentRef("zen-architect",    "design", emptyList()),
        AgentRef("bug-hunter",       "debug", emptyList()),
        AgentRef("git-ops",          "git",   emptyList()),
        AgentRef("modular-builder",  "build", emptyList()),
        AgentRef("security-guardian","sec",   emptyList()),
    )
    MaterialTheme {
        AgentChipRow(
            agents         = foundation,
            activeAgent    = "explorer",
            onAgentClick   = {},
            onInstallClick = {},
        )
    }
}
