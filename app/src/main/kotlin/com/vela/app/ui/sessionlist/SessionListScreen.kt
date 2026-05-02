package com.vela.app.ui.sessionlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.theme.VelaColors

/**
 * Screen 3: Project Detail — Sessions List.
 *
 * Design: DESIGN.md §8 (Screen 3)
 * Layout:
 *   - App bar: back chevron (Accent) + project name in titleLarge
 *   - "NEW SESSION" button: Accent fill, Abyss text, 52dp height, 26dp radius
 *   - "ACTIVE" eyebrow + running/waiting session cards
 *   - "RECENT" eyebrow + done/error session cards
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionListScreen(
    navController: NavController,
    viewModel: SessionListViewModel = hiltViewModel(),
) {
    val allSessions       by viewModel.allSessions.collectAsStateWithLifecycle()
    val createdSessionId  by viewModel.createdSessionId.collectAsStateWithLifecycle()

    // Navigate into the new session when createSession() completes
    LaunchedEffect(createdSessionId) {
        val sid = createdSessionId ?: return@LaunchedEffect
        viewModel.consumeCreatedSession()
        navController.navigate(com.vela.app.ui.navigation.Routes.sessionDetail(viewModel.nodeId, sid))
    }

    Scaffold(
        containerColor = VelaColors.Abyss,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text  = viewModel.projectName.ifBlank { "Project" },
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
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = VelaColors.Abyss,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier      = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── NEW SESSION button ──────────────────────────────────────────────
            item {
                Button(
                    onClick  = { viewModel.createSession() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(26.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor = VelaColors.Accent,
                        contentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Text(
                        text       = "NEW SESSION",
                        fontWeight = FontWeight.Bold,
                        fontSize   = 14.sp,
                        letterSpacing = 1.sp,
                    )
                }
            }

            // ── All sessions, sorted by last activity ────────────────────────────────
            if (allSessions.isEmpty()) {
                item {
                    Text(
                        text     = "No sessions yet. Tap NEW SESSION to start one.",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = VelaColors.TextTertiary,
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                }
            } else {
                items(allSessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = {
                            navController.navigate(Routes.sessionDetail(viewModel.nodeId, session.id))
                        },
                    )
                }
            }

            // Bottom padding to clear Voice FAB
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

// ── Private sub-composables ───────────────────────────────────────────────────

/**
 * Section eyebrow label: uppercase Inter 700, TextTertiary, 2sp letter spacing.
 * Used for "ACTIVE" and "RECENT" section dividers.
 */
@Composable
private fun SectionEyebrow(text: String) {
    Text(
        text  = text,
        style = MaterialTheme.typography.labelSmall,
        color = VelaColors.TextTertiary,
    )
}
