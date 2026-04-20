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
import com.vela.app.github.GitHubIdentity

// ── Top-level settings nav list ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToAi: () -> Unit,
    onNavigateToVaults: () -> Unit,
    onNavigateToRecording: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val apiKey by viewModel.apiKey.collectAsState()
    val vaults by viewModel.vaults.collectAsState()

    Scaffold(
        modifier = modifier,
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
                    onClick  = onNavigateToRecording,
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

// ── Vaults sub-screen ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultsSettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToVaultDetail: (vaultId: String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val vaults           by viewModel.vaults.collectAsState()
    val gitHubIdentities by viewModel.gitHubIdentities.collectAsState()
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
                }
            }
        }
    }

    if (showAddSheet) {
        AddVaultSheet(
            identities = gitHubIdentities,
            onDismiss  = { showAddSheet = false },
            onConfirm  = { name, remoteUrl, pat, branch ->
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
    identities: List<GitHubIdentity>,
    onDismiss:  () -> Unit,
    onConfirm:  (name: String, remoteUrl: String, pat: String, branch: String) -> Unit,
) {
    var name      by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    var branch    by remember { mutableStateOf("") }
    var showSync  by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }

    // Identity picker state
    var expanded         by remember { mutableStateOf(false) }
    var selectedIdentity by remember(identities) {
        mutableStateOf(identities.firstOrNull { it.isDefault } ?: identities.firstOrNull())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp),
               verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Text("Add Vault", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value          = name,
                onValueChange  = { name = it; nameError = false },
                label          = { Text("Vault name") },
                isError        = nameError,
                supportingText = if (nameError) {{ Text("Name is required") }} else null,
                singleLine     = true,
                modifier       = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = { showSync = !showSync },
                       contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
                Text(if (showSync) "Remove GitHub sync" else "Connect to GitHub repo (optional)",
                     style = MaterialTheme.typography.labelMedium)
            }

            if (showSync) {
                OutlinedTextField(
                    value         = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label         = { Text("Repo URL") },
                    placeholder   = { Text("https://github.com/user/vault or user/vault") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )

                // ── Identity picker ────────────────────────────────────────────
                if (identities.isEmpty()) {
                    Text(
                        "No GitHub accounts connected — add one in Settings → GitHub to enable sync.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded         = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value       = selectedIdentity
                                ?.let { "${it.label}  (@${it.username})" }
                                ?: "Select GitHub account",
                            onValueChange = {},
                            readOnly    = true,
                            label       = { Text("GitHub account") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier    = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded         = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            identities.forEach { identity ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(identity.label,
                                                 style = MaterialTheme.typography.bodyMedium)
                                            Text("@${identity.username}",
                                                 style = MaterialTheme.typography.bodySmall,
                                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { selectedIdentity = identity; expanded = false },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value         = branch,
                    onValueChange = { branch = it },
                    label         = { Text("Branch (optional)") },
                    placeholder   = { Text("main — leave blank to auto-detect") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (name.isBlank()) { nameError = true; return@Button }
                        // Resolve token from selected identity; fall through empty if no sync
                        val token = if (showSync) selectedIdentity?.token ?: "" else ""
                        onConfirm(name.trim(), remoteUrl.trim(), token, branch.trim())
                    },
                    enabled = name.isNotBlank() &&
                              (!showSync || (remoteUrl.isNotBlank() && (identities.isEmpty() || selectedIdentity != null))),
                ) { Text("Create Vault") }
            }
        }
    }
}
