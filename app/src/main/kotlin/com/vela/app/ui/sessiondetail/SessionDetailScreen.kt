package com.vela.app.ui.sessiondetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.vela.app.ui.theme.VelaColors

/**
 * Screen 4: Session Detail — Turn History.
 *
 * Design: DESIGN.md §8 (Screen 4)
 * Layout:
 *   - App bar: back + session title in titleLarge + running dot (8dp amber) if RUNNING
 *   - Title area: session name in displayMedium (Instrument Serif 36sp) + status pill
 *   - Turn list: LazyColumn of UserTurnItem / AgentTurnItem, 16dp spacing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailScreen(
    navController: NavController,
    viewModel: SessionDetailViewModel = hiltViewModel(),
) {
    val turns by viewModel.turns.collectAsStateWithLifecycle()

    // Derive session status from turn tool calls for placeholder display
    val isRunning = turns.any { !it.isUser && it.toolCalls.any { tc -> tc.isRunning } }

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = "Session",  // Placeholder — session title from API in future phase
                        style = MaterialTheme.typography.titleLarge,
                        color = VelaColors.TextPrimary,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = VelaColors.Accent,
                        )
                    }
                },
                actions = {
                    // Running dot indicator: 8dp amber circle
                    if (isRunning) {
                        RunningDotIndicator()
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {

            // ── Session title hero area ──────────────────────────────────────
            item {
                SessionTitleArea(
                    // Placeholder title — replace with real session.title in future phase
                    title     = turns.firstOrNull { it.isUser }?.text?.take(60) ?: "Session",
                    isRunning = isRunning,
                )
            }

            // ── Turn list ────────────────────────────────────────────────────
            items(turns) { turn ->
                if (turn.isUser) {
                    UserTurnItem(text = turn.text)
                } else {
                    AgentTurnItem(content = turn)
                }
            }

            // Bottom padding to clear Voice FAB
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ── Private sub-composables ───────────────────────────────────────────────────

/**
 * Session title area displayed below the app bar.
 * Title uses displayMedium (Instrument Serif 36sp) — the key serif moment on this screen.
 * Status pill: 8dp radius, 26dp height, Inter 10sp 700 uppercase.
 */
@Composable
private fun SessionTitleArea(
    title: String,
    isRunning: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text  = title,
            style = MaterialTheme.typography.displayMedium,
            color = VelaColors.TextPrimary,
        )

        // Status pill
        val (pillColor, pillText, pillTextColor) = if (isRunning) {
            Triple(VelaColors.RunningContainer, "RUNNING", VelaColors.RunningOnContainer)
        } else {
            Triple(VelaColors.DoneContainer, "DONE", VelaColors.DoneOnContainer)
        }

        Surface(
            shape    = RoundedCornerShape(8.dp),
            color    = pillColor,
            modifier = Modifier.height(26.dp),
        ) {
            Box(
                modifier         = Modifier.padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text  = pillText,
                    style = MaterialTheme.typography.labelSmall,
                    color = pillTextColor,
                )
            }
        }
    }
}

/**
 * Amber breathing dot shown in the app bar when session is RUNNING.
 * 8dp circle, VelaColors.Running fill.
 */
@Composable
private fun RunningDotIndicator(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(end = 16.dp)
            .size(8.dp)
            .background(VelaColors.Running, CircleShape),
    )
}
