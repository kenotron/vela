package com.vela.app.ui.nodedetail

import androidx.compose.foundation.border
import com.vela.app.ui.navigation.Routes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors

/**
 * Node detail screen — projects list for a single SshNode.
 *
 * The hero block (node name at displayLarge / Instrument Serif 48sp) is the
 * design's signature moment. The rest of the screen is a list of project cards
 * (empty in Phase 2 — project data comes from the amplifierd HTTP API in Phase 3).
 *
 * Design spec: DESIGN.md §8 (Screen 2)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    navController: NavController,
    viewModel: NodeDetailViewModel = hiltViewModel(),
) {
    val node by viewModel.node.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    // Back chevron in accent color (DESIGN.md §8)
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint               = VelaColors.Accent,
                        )
                    }
                },
                title  = {},
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
        containerColor = VelaColors.Abyss,
    ) { paddingValues ->
        LazyColumn(
            contentPadding = PaddingValues(
                start  = 24.dp,
                end    = 24.dp,
                top    = paddingValues.calculateTopPadding() + 32.dp,
                bottom = 96.dp, // clear Voice FAB
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {

            // ── Hero block ────────────────────────────────────────────────────
            item(key = "hero") {
                // Node name — Instrument Serif 48sp (displayLarge). This is the
                // design's signature moment. (DESIGN.md §3, §8)
                Text(
                    text  = node?.label ?: "",
                    style = MaterialTheme.typography.displayLarge,
                    color = VelaColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Telemetry meta — Inter 12sp, TextSecondary
                Text(
                    text  = buildNodeTelemetry(node),
                    style = MaterialTheme.typography.labelMedium,
                    color = VelaColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(32.dp))
                // Section eyebrow — Inter 700 uppercase, 2dp letter-spacing, TextTertiary
                Text(
                    text          = "PROJECTS",
                    style         = MaterialTheme.typography.labelSmall,
                    color         = VelaColors.TextTertiary,
                    letterSpacing = 2.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Project list ──────────────────────────────────────────────────
            // Phase 2: no project data yet. A single "New project" add-card is shown.
            // Phase 3 will inject a projects StateFlow and map it into ProjectCard items.

            item(key = "new-project-placeholder") {
                NewProjectPlaceholder(
                    onTap = { navController.navigate(Routes.CONNECT_NODE) }
                )
            }
        }
    }
}

// ── Private composables ───────────────────────────────────────────────────────

/**
 * Dashed-border placeholder card for "New project".
 * Replaced in Phase 3 when real project creation is wired.
 */
@Composable
private fun NewProjectPlaceholder(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .border(
                width = 1.dp,
                color = VelaColors.StrokeEdge,
                shape = RoundedCornerShape(20.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = "+ New project",
            style = MaterialTheme.typography.labelLarge,
            color = VelaColors.TextTertiary,
        )
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun buildNodeTelemetry(node: SshNode?): String {
    if (node == null) return ""
    val status = if (node.bootstrapStatus == BootstrapStatus.RUNNING) "online" else "offline"
    return "$status · 0 active sessions · connected"
}
