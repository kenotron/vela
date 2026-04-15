package com.vela.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

// ── Top-level settings nav list ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAi: () -> Unit,
    onNavigateToConnections: () -> Unit,
    onNavigateToVaults: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apiKey by viewModel.apiKey.collectAsState()
    val vaults by viewModel.vaults.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text("Settings") },
            )
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad)) {
            item {
                SettingsNavRow(
                    icon     = Icons.Default.SmartToy,
                    title    = "AI",
                    subtitle = if (apiKey.isBlank()) "API key not set" else "Configured",
                    onClick  = onNavigateToAi,
                )
            }
            item {
                SettingsNavRow(
                    icon     = Icons.Default.Hub,
                    title    = "Connections",
                    subtitle = "SSH nodes",
                    onClick  = onNavigateToConnections,
                )
            }
            item {
                SettingsNavRow(
                    icon     = Icons.Default.Folder,
                    title    = "Vaults",
                    subtitle = if (vaults.isEmpty()) "No vaults"
                               else "${vaults.size} vault${if (vaults.size != 1) "s" else ""}",
                    onClick  = onNavigateToVaults,
                )
            }
            item {
                SettingsNavRow(
                    icon     = Icons.Default.Mic,
                    title    = "Recording",
                    subtitle = "Transcription settings",
                    onClick  = { /* TODO: Phase 2 */ },
                    enabled  = false,
                )
            }
        }
    }
}

@Composable
private fun SettingsNavRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    ListItem(
        leadingContent = {
            Icon(
                icon, null,
                tint = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                       else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        },
        headlineContent = {
            Text(
                title,
                color = if (enabled) LocalContentColor.current
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        },
        supportingContent = {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = if (enabled) Modifier.clickable(onClick = onClick) else Modifier,
    )
    HorizontalDivider()
}

// ── AI sub-screen ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiSettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val apiKey        by viewModel.apiKey.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val googleApiKey  by viewModel.googleApiKey.collectAsState()
    val openAiApiKey  by viewModel.openAiApiKey.collectAsState()

    var showAnthropicDialog by remember { mutableStateOf(false) }
    var showGoogleDialog    by remember { mutableStateOf(false) }
    var showOpenAiDialog    by remember { mutableStateOf(false) }
    var showModelPicker     by remember { mutableStateOf(false) }

    fun String.masked() = if (isBlank()) "(not set)" else "…${takeLast(4)}"

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text("AI") },
            )
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad)) {
            item { SectionHeader("Anthropic") }
            item {
                ListItem(
                    headlineContent   = { Text("API Key") },
                    supportingContent = {
                        Text(
                            apiKey.masked(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showAnthropicDialog = true }) { Text("Edit") }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent   = { Text("Model") },
                    supportingContent = {
                        Text(
                            selectedModel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable { showModelPicker = true },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            item { SectionHeader("Google") }
            item {
                ListItem(
                    headlineContent   = { Text("API Key") },
                    supportingContent = {
                        Text(
                            googleApiKey.masked(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showGoogleDialog = true }) { Text("Edit") }
                    },
                )
            }
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            item { SectionHeader("OpenAI") }
            item {
                ListItem(
                    headlineContent   = { Text("API Key") },
                    supportingContent = {
                        Text(
                            openAiApiKey.masked(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        TextButton(onClick = { showOpenAiDialog = true }) { Text("Edit") }
                    },
                )
            }
        }
    }

    if (showAnthropicDialog) {
        ApiKeyDialog(
            title      = "Anthropic API Key",
            currentKey = apiKey,
            onDismiss  = { showAnthropicDialog = false },
            onConfirm  = { viewModel.setApiKey(it); showAnthropicDialog = false },
        )
    }
    if (showGoogleDialog) {
        ApiKeyDialog(
            title      = "Google API Key",
            currentKey = googleApiKey,
            onDismiss  = { showGoogleDialog = false },
            onConfirm  = { viewModel.setGoogleApiKey(it); showGoogleDialog = false },
        )
    }
    if (showOpenAiDialog) {
        ApiKeyDialog(
            title      = "OpenAI API Key",
            currentKey = openAiApiKey,
            onDismiss  = { showOpenAiDialog = false },
            onConfirm  = { viewModel.setOpenAiApiKey(it); showOpenAiDialog = false },
        )
    }
    if (showModelPicker) {
        ModelPickerDialog(
            currentModel = selectedModel,
            onDismiss    = { showModelPicker = false },
            onSelect     = { viewModel.setModel(it); showModelPicker = false },
        )
    }
}

// ── Connections sub-screen ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsSettingsScreen(
    onNavigateBack: () -> Unit,
    nodesViewModel: com.vela.app.ui.nodes.NodesViewModel = hiltViewModel(),
) {
    val nodes    by nodesViewModel.nodes.collectAsState()
    val addError by nodesViewModel.addError.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text("Connections") },
                actions = {
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, "Add node")
                    }
                },
            )
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad)) {
            item {
                com.vela.app.ui.nodes.DeviceKeyCard(
                    publicKey = nodesViewModel.publicKey,
                    context   = context,
                )
            }
            if (nodes.isEmpty()) {
                item {
                    Text(
                        "No nodes yet. Add one with the + button above.",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            } else {
                items(nodes, key = { it.id }) { node ->
                    com.vela.app.ui.nodes.NodeCard(
                        node         = node,
                        onDelete     = { nodesViewModel.removeNode(node.id) },
                        onAddHost    = { nodesViewModel.addHostToNode(node.id, it) },
                        onRemoveHost = { nodesViewModel.removeHostFromNode(node.id, it) },
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        com.vela.app.ui.nodes.AddNodeSheet(
            onDismiss = { showAddSheet = false; nodesViewModel.clearError() },
            onAdd     = { label, host, port, user ->
                nodesViewModel.addNode(label, host, port, user)
                showAddSheet = false
            },
            error = addError,
        )
    }
}

// ── Vaults sub-screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultsSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToVaultDetail: (vaultId: String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val vaults by viewModel.vaults.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text("Vaults") },
                actions = {
                    IconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, "Add vault")
                    }
                },
            )
        }
    ) { pad ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(pad)) {
            if (vaults.isEmpty()) {
                item {
                    Text(
                        "No vaults yet. Tap + to add one.",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    )
                }
            } else {
                items(vaults, key = { it.id }) { vault ->
                    ListItem(
                        headlineContent   = { Text(vault.name) },
                        supportingContent = {
                            Text(
                                if (viewModel.isVaultSyncConfigured(vault.id)) "Synced" else "Local only",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked         = vault.isEnabled,
                                onCheckedChange = { viewModel.setVaultEnabled(vault.id, it) },
                            )
                        },
                        modifier = Modifier.clickable { onNavigateToVaultDetail(vault.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddSheet) {
        AddVaultSheet(
            onDismiss = { showAddSheet = false },
            onConfirm = { name, remoteUrl, pat, branch ->
                viewModel.addVault(name, remoteUrl, pat, branch)
                showAddSheet = false
            },
        )
    }
}

// ── Shared dialogs ────────────────────────────────────────────────────────────

@Composable
private fun ApiKeyDialog(
    title: String,
    currentKey: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var key by remember { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        text             = {
            OutlinedTextField(
                value         = key,
                onValueChange = { key = it },
                label         = { Text("Key") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
        },
        confirmButton  = {
            Button(
                onClick  = { if (key.isNotBlank()) onConfirm(key.trim()) },
                enabled  = key.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton  = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ModelPickerDialog(currentModel: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("Select Model") },
        text             = {
            Column {
                SettingsViewModel.AVAILABLE_MODELS.forEach { model ->
                    Row(
                        modifier          = Modifier.fillMaxWidth().clickable { onSelect(model) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = model == currentModel, onClick = { onSelect(model) })
                        Spacer(Modifier.width(8.dp))
                        Text(model, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

// ── Shared composables ────────────────────────────────────────────────────────

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text     = title,
        style    = MaterialTheme.typography.titleSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

// ── AddVaultSheet ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddVaultSheet(
    onDismiss: () -> Unit,
    onConfirm: (name: String, remoteUrl: String, pat: String, branch: String) -> Unit,
) {
    var name       by remember { mutableStateOf("") }
    var remoteUrl  by remember { mutableStateOf("") }
    var pat        by remember { mutableStateOf("") }
    var branch     by remember { mutableStateOf("") }
    var showGitHub by remember { mutableStateOf(false) }
    var nameError  by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
            Text("Add Vault", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value          = name,
                onValueChange  = { name = it; nameError = false },
                label          = { Text("Vault name") },
                isError        = nameError,
                supportingText = if (nameError) {{ Text("Name is required") }} else null,
                singleLine     = true,
                modifier       = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            TextButton(onClick = { showGitHub = !showGitHub }) {
                Text(if (showGitHub) "Hide GitHub sync" else "Connect GitHub (optional)")
            }

            if (showGitHub) {
                OutlinedTextField(
                    value         = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label         = { Text("GitHub repo URL") },
                    placeholder   = { Text("https://github.com/user/vault.git") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value                  = pat,
                    onValueChange          = { pat = it },
                    label                  = { Text("Personal Access Token") },
                    visualTransformation   = PasswordVisualTransformation(),
                    singleLine             = true,
                    modifier               = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = branch,
                    onValueChange = { branch = it },
                    label         = { Text("Branch (optional)") },
                    placeholder   = { Text("main, master — leave blank to auto-detect") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    onConfirm(name.trim(), remoteUrl.trim(), pat.trim(), branch.trim())
                }) { Text("Create Vault") }
            }
        }
    }
}
