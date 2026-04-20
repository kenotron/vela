package com.vela.app.ui.vault

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.data.db.VaultEntity
import com.vela.app.github.GitHubIdentity
import com.vela.app.ui.settings.SettingsViewModel
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass

/**
 * Unified Vault Hub — combines vault management (add, sync, enable/disable, edit,
 * delete) with the file browser into a single screen reachable from the sidebar.
 *
 * Flow:
 *   Vault list  ──[Browse]──▶  File browser (VaultBrowserScreen)
 *                ──[Edit]───▶  Inline expandable settings per card
 *                ──[+]──────▶  Add vault sheet
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun VaultHubScreen(
    windowSizeClass:            WindowSizeClass,
    onFixWithChat:              (prompt: String) -> Unit = {},
    onSetDrawerGesturesEnabled: (Boolean) -> Unit = {},
    modifier:                   Modifier = Modifier,
    settingsVm:                 SettingsViewModel = hiltViewModel(),
    browserVm:                  VaultBrowserViewModel = hiltViewModel(),
) {
    val vaults           by settingsVm.vaults.collectAsState()
    val gitHubIdentities by settingsVm.gitHubIdentities.collectAsState()
    val syncMessage      by settingsVm.syncMessage.collectAsState()
    val syncIsError      by settingsVm.syncIsError.collectAsState()
    val syncVaultName    by settingsVm.syncVaultName.collectAsState()

    // Which vault is currently open in the file browser (null = show vault list)
    var browsingVault by remember { mutableStateOf<VaultEntity?>(null) }
    var showAddSheet  by remember { mutableStateOf(false) }

    // ── File browser mode ─────────────────────────────────────────────────────
    val bv = browsingVault
    if (bv != null) {
        BackHandler { browsingVault = null }
        VaultBrowserScreen(
            vault                      = bv,
            onBack                     = { browsingVault = null },
            onOpenFile                 = { /* MiniAppContainerView handles it internally */ },
            onSetDrawerGesturesEnabled = onSetDrawerGesturesEnabled,
        )
        return
    }

    // ── Vault list mode ───────────────────────────────────────────────────────
    Scaffold(
        modifier = modifier,
        topBar   = {
            TopAppBar(
                title   = { Text("Vaults") },
                actions = {
                    FilledTonalIconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add vault")
                    }
                },
            )
        },
    ) { pad ->
        if (vaults.isEmpty()) {
            // Empty state
            Box(
                modifier         = Modifier.fillMaxSize().padding(pad),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(
                        Icons.Default.FolderOpen, null,
                        modifier = Modifier.size(56.dp),
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("No vaults yet", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "A vault is a folder of notes, files, and data that\nVela reads and writes on your behalf.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Add a vault")
                    }
                }
            }
        } else {
            LazyColumn(
                modifier            = Modifier.fillMaxSize().padding(pad),
                contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(vaults, key = { it.id }) { vault ->
                    VaultCard(
                        vault            = vault,
                        identities       = gitHubIdentities,
                        syncMessage      = syncMessage,
                        isSyncConfigured = settingsVm.isVaultSyncConfigured(vault.id),
                        remoteUrl        = settingsVm.getVaultRemoteUrl(vault.id),
                        branch           = settingsVm.getVaultBranch(vault.id),
                        onToggleEnabled  = { settingsVm.setVaultEnabled(vault.id, it) },
                        onSync           = { settingsVm.syncVault(vault.id) },
                        onBrowse         = { browsingVault = vault },
                        onSaveRemote     = { url, token, br ->
                            settingsVm.setVaultRemote(vault.id, url, token, br)
                        },
                        onDelete         = { settingsVm.deleteVault(vault.id) },
                    )
                }
                // Sync status / error banner
                if (!syncMessage.isNullOrBlank()) {
                    item {
                        SyncStatusBanner(
                            message      = syncMessage!!,
                            isError      = syncIsError,
                            vaultName    = syncVaultName,
                            onDismiss    = { settingsVm.clearSyncMessage() },
                            onFixWithChat = onFixWithChat,
                        )
                    }
                }
            }
        }
    }

    if (showAddSheet) {
        AddVaultSheet(
            identities = gitHubIdentities,
            onDismiss  = { showAddSheet = false },
            onConfirm  = { name, url, pat, branch ->
                settingsVm.addVault(name, url, pat, branch)
                showAddSheet = false
            },
        )
    }
}

// ── Sync status / error banner ────────────────────────────────────────────────

@Composable
private fun SyncStatusBanner(
    message:      String,
    isError:      Boolean,
    vaultName:    String?,
    onDismiss:    () -> Unit,
    onFixWithChat: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val containerColor = if (isError) cs.errorContainer else cs.secondaryContainer
    val contentColor   = if (isError) cs.onErrorContainer else cs.onSecondaryContainer

    Surface(
        color    = containerColor,
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment     = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = if (isError) Icons.Default.SyncProblem else Icons.Default.Sync,
                    contentDescription = null,
                    tint     = contentColor,
                    modifier = Modifier.size(16.dp).padding(top = 2.dp),
                )
                Text(
                    text     = message,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = contentColor,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                if (isError) {
                    // Offer to open a chat session to fix the problem
                    val name = vaultName ?: "vault"
                    TextButton(
                        onClick = {
                            onFixWithChat(buildSyncErrorPrompt(name, message))
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = contentColor),
                    ) {
                        Icon(Icons.Default.Chat, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Fix with Vela", style = MaterialTheme.typography.labelSmall)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                TextButton(
                    onClick = onDismiss,
                    colors  = ButtonDefaults.textButtonColors(contentColor = contentColor),
                ) {
                    Text("Dismiss", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun buildSyncErrorPrompt(vaultName: String, errorMessage: String) =
    """
    My vault "$vaultName" hit an error while syncing with GitHub. Here's what happened:

    $errorMessage

    Can you help me fix this? Please check the git status of the vault, identify what's wrong, and walk me through resolving it — or just fix it directly if you can.
    """.trimIndent()

// ── Vault card ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultCard(
    vault:            VaultEntity,
    identities:       List<GitHubIdentity>,
    syncMessage:      String?,
    isSyncConfigured: Boolean,
    remoteUrl:        String,
    branch:           String,
    onToggleEnabled:  (Boolean) -> Unit,
    onSync:           () -> Unit,
    onBrowse:         () -> Unit,
    onSaveRemote:     (url: String, token: String, branch: String) -> Unit,
    onDelete:         () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var expanded         by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Edit fields (only materialised when expanded)
    var editUrl      by remember(remoteUrl) { mutableStateOf(remoteUrl) }
    var editBranch   by remember(branch)    { mutableStateOf(branch) }
    var selIdentity  by remember { mutableStateOf<GitHubIdentity?>(null) }
    var idExpanded   by remember { mutableStateOf(false) }

    Surface(
        shape  = RoundedCornerShape(16.dp),
        color  = cs.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // ── Header ────────────────────────────────────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Vault icon badge
                Box(
                    modifier         = Modifier
                        .size(44.dp)
                        .background(
                            if (vault.isEnabled) cs.primaryContainer else cs.surfaceContainerHigh,
                            RoundedCornerShape(10.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isSyncConfigured) Icons.Default.CloudDone else Icons.Default.Folder,
                        contentDescription = null,
                        tint               = if (vault.isEnabled) cs.onPrimaryContainer
                                             else cs.onSurfaceVariant,
                        modifier           = Modifier.size(22.dp),
                    )
                }

                // Name + status
                Column(Modifier.weight(1f)) {
                    Text(vault.name, style = MaterialTheme.typography.titleSmall,
                         fontWeight = FontWeight.SemiBold)
                    Text(
                        if (isSyncConfigured) "GitHub sync · ${if (vault.isEnabled) "active" else "disabled"}"
                        else "Local · ${if (vault.isEnabled) "active" else "disabled"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        fontSize = 11.sp,
                    )
                }

                // Enable toggle
                Switch(
                    checked         = vault.isEnabled,
                    onCheckedChange = onToggleEnabled,
                    modifier        = Modifier.size(width = 48.dp, height = 28.dp),
                )
            }

            // ── Action row ────────────────────────────────────────────────
            Row(
                modifier              = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Sync — only shown when sync is configured
                if (isSyncConfigured) {
                    OutlinedButton(
                        onClick  = onSync,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Sync, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Sync", style = MaterialTheme.typography.labelMedium)
                    }
                }
                // Browse
                Button(
                    onClick  = onBrowse,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(Icons.Default.FolderOpen, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Browse", style = MaterialTheme.typography.labelMedium)
                }
                // Edit settings toggle
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.Settings,
                        contentDescription = "Edit vault settings",
                        tint = cs.onSurfaceVariant,
                    )
                }
            }

            // ── Expandable settings ───────────────────────────────────────
            AnimatedVisibility(
                visible = expanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column(
                    modifier            = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))

                    Text("GitHub Sync", style = MaterialTheme.typography.labelMedium,
                         color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold)

                    OutlinedTextField(
                        value         = editUrl,
                        onValueChange = { editUrl = it },
                        label         = { Text("Repo URL") },
                        placeholder   = { Text("https://github.com/user/vault.git") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )

                    if (identities.isNotEmpty()) {
                        ExposedDropdownMenuBox(
                            expanded         = idExpanded,
                            onExpandedChange = { idExpanded = it },
                        ) {
                            OutlinedTextField(
                                value         = selIdentity?.let { "${it.label} (@${it.username})" }
                                                ?: "Keep existing auth",
                                onValueChange = {},
                                readOnly      = true,
                                label         = { Text("GitHub account") },
                                trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(idExpanded) },
                                modifier      = Modifier.fillMaxWidth().menuAnchor(),
                            )
                            ExposedDropdownMenu(
                                expanded         = idExpanded,
                                onDismissRequest = { idExpanded = false },
                            ) {
                                DropdownMenuItem(
                                    text    = { Text("Keep existing auth") },
                                    onClick = { selIdentity = null; idExpanded = false },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                )
                                HorizontalDivider()
                                identities.forEach { id ->
                                    DropdownMenuItem(
                                        text    = {
                                            Column {
                                                Text(id.label)
                                                Text("@${id.username}",
                                                     style = MaterialTheme.typography.bodySmall,
                                                     color = cs.onSurfaceVariant)
                                            }
                                        },
                                        onClick = { selIdentity = id; idExpanded = false },
                                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                                    )
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value         = editBranch,
                        onValueChange = { editBranch = it },
                        label         = { Text("Branch (optional)") },
                        placeholder   = { Text("main — leave blank to auto-detect") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth(),
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick  = { onSaveRemote(editUrl, selIdentity?.token ?: "", editBranch); expanded = false },
                            modifier = Modifier.weight(1f),
                        ) { Text("Save") }
                    }

                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))

                    OutlinedButton(
                        onClick  = { showDeleteDialog = true },
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = cs.error),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Delete, null, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Delete vault")
                    }
                }
            }
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title   = { Text("Delete \"${vault.name}\"?") },
            text    = { Text("This permanently removes the local vault files. Content synced to GitHub is not affected.") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(); showDeleteDialog = false },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            },
        )
    }
}

// ── Add vault sheet ───────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVaultSheet(
    identities: List<GitHubIdentity>,
    onDismiss:  () -> Unit,
    onConfirm:  (name: String, remoteUrl: String, pat: String, branch: String) -> Unit,
) {
    var name      by remember { mutableStateOf("") }
    var remoteUrl by remember { mutableStateOf("") }
    var branch    by remember { mutableStateOf("") }
    var showSync  by remember { mutableStateOf(false) }
    var nameError by remember { mutableStateOf(false) }
    var expanded  by remember { mutableStateOf(false) }
    var selId     by remember(identities) {
        mutableStateOf(identities.firstOrNull { it.isDefault } ?: identities.firstOrNull())
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier            = Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add vault", style = MaterialTheme.typography.titleLarge)

            OutlinedTextField(
                value          = name,
                onValueChange  = { name = it; nameError = false },
                label          = { Text("Vault name") },
                isError        = nameError,
                supportingText = if (nameError) {{ Text("Name is required") }} else null,
                singleLine     = true,
                modifier       = Modifier.fillMaxWidth(),
            )

            TextButton(
                onClick        = { showSync = !showSync },
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Text(
                    if (showSync) "Remove GitHub sync" else "+ Connect to a GitHub repo (optional)",
                    style = MaterialTheme.typography.labelMedium,
                )
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

                if (identities.isEmpty()) {
                    Text(
                        "No GitHub accounts connected yet. You can add one through Connectors.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded         = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value         = selId?.let { "${it.label} (@${it.username})" }
                                            ?: "Select GitHub account",
                            onValueChange = {},
                            readOnly      = true,
                            label         = { Text("GitHub account") },
                            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier      = Modifier.fillMaxWidth().menuAnchor(),
                        )
                        ExposedDropdownMenu(
                            expanded         = expanded,
                            onDismissRequest = { expanded = false },
                        ) {
                            identities.forEach { id ->
                                DropdownMenuItem(
                                    text    = {
                                        Column {
                                            Text(id.label)
                                            Text("@${id.username}",
                                                 style = MaterialTheme.typography.bodySmall,
                                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    },
                                    onClick = { selId = id; expanded = false },
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

            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (name.isBlank()) { nameError = true; return@Button }
                        val token = if (showSync) selId?.token ?: "" else ""
                        onConfirm(name.trim(), remoteUrl.trim(), token, branch.trim())
                    },
                    enabled = name.isNotBlank() &&
                              (!showSync || (remoteUrl.isNotBlank() &&
                               (identities.isEmpty() || selId != null))),
                ) { Text("Create vault") }
            }
        }
    }
}
