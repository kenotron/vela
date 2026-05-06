package com.vela.app.ui.sessionlist

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    val allSessions        by viewModel.allSessions.collectAsStateWithLifecycle()
    val createdSessionId   by viewModel.createdSessionId.collectAsStateWithLifecycle()
    val isCreatingSession  by viewModel.isCreatingSession.collectAsStateWithLifecycle()

    var showGearMenu      by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showEditSheet     by remember { mutableStateOf(false) }
    var editName          by remember { mutableStateOf(viewModel.projectName) }
    var editDir           by remember { mutableStateOf(viewModel.workingDir) }

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
                actions = {
                    Box {
                        IconButton(onClick = { showGearMenu = true }) {
                            Icon(
                                imageVector        = Icons.Default.Settings,
                                contentDescription = "Project settings",
                                tint               = VelaColors.TextSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded         = showGearMenu,
                            onDismissRequest = { showGearMenu = false },
                        ) {
                            DropdownMenuItem(
                                text    = { Text("Settings") },
                                onClick = {
                                    editName = viewModel.projectName
                                    editDir  = viewModel.workingDir
                                    showGearMenu  = false
                                    showEditSheet = true
                                },
                            )
                            DropdownMenuItem(
                                text    = { Text("Delete project", color = Color(0xFFF38BA8)) },
                                onClick = {
                                    showGearMenu      = false
                                    showDeleteConfirm = true
                                },
                            )
                        }
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
                    enabled  = !isCreatingSession,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = RoundedCornerShape(26.dp),
                    colors   = ButtonDefaults.buttonColors(
                        containerColor         = VelaColors.Accent,
                        contentColor           = VelaColors.Abyss,
                        disabledContainerColor = VelaColors.Accent,
                        disabledContentColor   = VelaColors.Abyss,
                    ),
                ) {
                    if (isCreatingSession) {
                        CircularProgressIndicator(
                            modifier    = Modifier.size(20.dp),
                            color       = VelaColors.Abyss,
                            trackColor  = VelaColors.Abyss.copy(alpha = 0.25f),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text          = "NEW SESSION",
                            fontWeight    = FontWeight.Bold,
                            fontSize      = 14.sp,
                            letterSpacing = 1.sp,
                        )
                    }
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

        // ── Edit project settings ──────────────────────────────────────────
        if (showEditSheet) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { showEditSheet = false },
            ) {
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text  = "Project Settings",
                        style = MaterialTheme.typography.titleMedium,
                        color = VelaColors.TextPrimary,
                    )
                    OutlinedTextField(
                        value         = editName,
                        onValueChange = { editName = it },
                        label         = { Text("Project name") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = VelaColors.Accent,
                            unfocusedBorderColor = VelaColors.TextTertiary,
                            focusedLabelColor    = VelaColors.Accent,
                            unfocusedLabelColor  = VelaColors.TextTertiary,
                            cursorColor          = VelaColors.Accent,
                            focusedTextColor     = VelaColors.TextPrimary,
                            unfocusedTextColor   = VelaColors.TextPrimary,
                        ),
                    )
                    OutlinedTextField(
                        value         = editDir,
                        onValueChange = { editDir = it },
                        label         = { Text("Working directory") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                        colors        = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = VelaColors.Accent,
                            unfocusedBorderColor = VelaColors.TextTertiary,
                            focusedLabelColor    = VelaColors.Accent,
                            unfocusedLabelColor  = VelaColors.TextTertiary,
                            cursorColor          = VelaColors.Accent,
                            focusedTextColor     = VelaColors.TextPrimary,
                            unfocusedTextColor   = VelaColors.TextPrimary,
                        ),
                    )
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        TextButton(
                            onClick  = { showEditSheet = false },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel", color = VelaColors.TextSecondary)
                        }
                        Button(
                            onClick  = {
                                viewModel.updateProject(editName.trim(), editDir.trim()) {
                                    showEditSheet = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape    = RoundedCornerShape(12.dp),
                            colors   = ButtonDefaults.buttonColors(
                                containerColor = VelaColors.Accent,
                                contentColor   = VelaColors.Abyss,
                            ),
                            enabled  = editName.isNotBlank(),
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }

        // ── Delete project confirm ─────────────────────────────────────────
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                containerColor   = VelaColors.SurfacePeak,
                title = {
                    Text("Delete project?", style = MaterialTheme.typography.titleMedium, color = VelaColors.TextPrimary)
                },
                text = {
                    Text(
                        "\"${viewModel.projectName}\" and all its session history will be removed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = VelaColors.TextSecondary,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteProject { navController.popBackStack() }
                    }) {
                        Text("Delete", color = Color(0xFFF38BA8))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) {
                        Text("Cancel", color = VelaColors.TextSecondary)
                    }
                },
            )
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
