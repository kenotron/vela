package com.vela.app.ui.nodedetail

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.vela.app.ssh.BootstrapStatus
import com.vela.app.ssh.SshNode
import com.vela.app.ui.connectors.NodeBootstrapSheet
import com.vela.app.ui.navigation.Routes
import com.vela.app.ui.theme.VelaColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Node detail screen — projects list for a single SshNode.
 *
 * The hero block (node name at displayLarge / Instrument Serif 48sp) is the
 * design's signature moment. The rest of the screen is a list of project cards
 * loaded from the amplifierd HTTP API.
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
    val projects by viewModel.projects.collectAsState()
    val repairState by viewModel.repairState.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Snackbar for transient error / success messages
    val snackbarHostState = remember { SnackbarHostState() }

    // "New project" dialog state
    var showNewProjectDialog by remember { mutableStateOf(false) }
    var newProjectName by remember { mutableStateOf("") }

    // "Delete node" confirm dialog state
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Auto-dismiss repair sheet after brief "done" beat
    LaunchedEffect(repairState.isComplete) {
        if (repairState.isComplete) {
            delay(1500)
            viewModel.clearRepairState()
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor   = VelaColors.SurfacePeak,
            title = {
                Text("Remove node?", style = MaterialTheme.typography.titleLarge, color = VelaColors.TextPrimary)
            },
            text = {
                Text(
                    text  = "\"${node?.label}\" will be removed from Vela. The amplifierd daemon on the remote machine is not affected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = VelaColors.TextSecondary,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    coroutineScope.launch {
                        viewModel.removeNode()
                        navController.popBackStack()
                    }
                }) {
                    Text("Remove", color = VelaColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = VelaColors.TextSecondary)
                }
            },
        )
    }

    if (showNewProjectDialog) {
        AlertDialog(
            onDismissRequest = { showNewProjectDialog = false; newProjectName = "" },
            containerColor   = VelaColors.SurfacePeak,
            title = {
                Text(
                    text  = "New project",
                    style = MaterialTheme.typography.titleLarge,
                    color = VelaColors.TextPrimary,
                )
            },
            text = {
                OutlinedTextField(
                    value         = newProjectName,
                    onValueChange = { newProjectName = it },
                    placeholder   = { Text("Project name", color = VelaColors.TextTertiary) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = VelaColors.Accent,
                        unfocusedBorderColor = VelaColors.StrokeEdge,
                        focusedTextColor     = VelaColors.TextPrimary,
                        unfocusedTextColor   = VelaColors.TextPrimary,
                        cursorColor          = VelaColors.Accent,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newProjectName
                        showNewProjectDialog = false
                        newProjectName = ""
                        coroutineScope.launch {
                            val ok = viewModel.createProject(name)
                            if (!ok) {
                                snackbarHostState.showSnackbar(
                                    "Couldn't create project — is the node reachable?"
                                )
                            }
                        }
                    },
                    enabled = newProjectName.isNotBlank(),
                ) {
                    Text("Create", color = VelaColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewProjectDialog = false; newProjectName = "" }) {
                    Text("Cancel", color = VelaColors.TextSecondary)
                }
            },
        )
    }

    if (repairState.isBootstrapping || repairState.isComplete || repairState.errorMessage != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.clearRepairState() },
            containerColor   = VelaColors.SurfaceRaised,
            sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            NodeBootstrapSheet(
                state     = repairState,
                onDismiss = { viewModel.clearRepairState() },
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                title   = {},
                actions = {
                    // Settings — navigates to node config (edit connection, bundle, tools)
                    IconButton(onClick = { navController.navigate(Routes.nodeConfig(viewModel.nodeId)) }) {
                        Icon(
                            imageVector        = Icons.Default.Settings,
                            contentDescription = "Node settings",
                            tint               = VelaColors.TextTertiary,
                        )
                    }

                    var menuExpanded by remember { mutableStateOf(false) }

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = VelaColors.TextTertiary)
                        }
                        DropdownMenu(
                            expanded         = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor   = VelaColors.SurfaceRaised,
                        ) {
                            DropdownMenuItem(
                                text    = { Text("Repair connection", color = VelaColors.TextPrimary) },
                                onClick = { menuExpanded = false; viewModel.startRepair() },
                                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null, tint = VelaColors.Accent) },
                            )
                            DropdownMenuItem(
                                text    = { Text("Remove node", color = VelaColors.Error) },
                                onClick = { menuExpanded = false; showDeleteDialog = true },
                                leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null, tint = VelaColors.Error) },
                            )
                        }
                    }
                },
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

            // ── Hero block ──────────────────────────────────────────────────────
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

            // ── Project list ────────────────────────────────────────────────────
            items(projects, key = { it.id }) { project ->
                ProjectCard(
                    projectName = project.name,
                    bundleTag   = "project",
                    onTap       = {
                        navController.navigate(
                            Routes.sessionList(viewModel.nodeId, project.id, project.name)
                        )
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            item(key = "new-project-placeholder") {
                NewProjectPlaceholder(
                    onTap = { showNewProjectDialog = true }
                )
            }
        }
    }
}

// ── Private composables ─────────────────────────────────────────────────────

/**
 * Dashed-border placeholder card for "New project".
 */
@Composable
private fun NewProjectPlaceholder(onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .clickable { onTap() }
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

// ── Helpers ─────────────────────────────────────────────────────────────────

private fun buildNodeTelemetry(node: SshNode?): String {
    if (node == null) return ""
    val status = if (node.bootstrapStatus == BootstrapStatus.RUNNING) "online" else "offline"
    val workspace = node.workspaceDir.ifBlank { "~" }
        return "$status · 0 active sessions · $workspace"
}
