package com.vela.app.ui.settings

    import androidx.compose.foundation.layout.*
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.unit.dp
    import androidx.hilt.navigation.compose.hiltViewModel
    import com.vela.app.github.GitHubIdentity

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun VaultDetailScreen(
        vaultId: String,
        onNavigateBack: () -> Unit,
        viewModel: SettingsViewModel = hiltViewModel(),
    ) {
        val vaults           by viewModel.vaults.collectAsState()
        val syncMessage      by viewModel.syncMessage.collectAsState()
        val gitHubIdentities by viewModel.gitHubIdentities.collectAsState()
        val vault = vaults.firstOrNull { it.id == vaultId }

        if (vault == null) {
            LaunchedEffect(Unit) { onNavigateBack() }
            return
        }

        var remoteUrl         by remember(vaultId) { mutableStateOf(viewModel.getVaultRemoteUrl(vaultId)) }
        var branch            by remember(vaultId) { mutableStateOf(viewModel.getVaultBranch(vaultId)) }
        var showDeleteConfirm by remember { mutableStateOf(false) }

        // Identity picker — null means "keep existing token, don't overwrite"
        var expanded         by remember { mutableStateOf(false) }
        var selectedIdentity by remember { mutableStateOf<GitHubIdentity?>(null) }

        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text(vault.name, style = MaterialTheme.typography.titleLarge) },
                )
            },
        ) { pad ->
            Column(
                modifier            = Modifier.fillMaxSize().padding(pad).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value         = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label         = { Text("GitHub remote URL") },
                    placeholder   = { Text("https://github.com/user/vault.git") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )

                // ── Identity picker ─────────────────────────────────────────────
                if (gitHubIdentities.isEmpty()) {
                    Text(
                        "No GitHub accounts connected — add one in Settings → GitHub.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    ExposedDropdownMenuBox(
                        expanded         = expanded,
                        onExpandedChange = { expanded = it },
                    ) {
                        OutlinedTextField(
                            value = selectedIdentity
                                ?.let { "${it.label}  (@${it.username})" }
                                ?: "Keep existing auth",
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
                            // "keep existing" option — don't overwrite stored PAT
                            DropdownMenuItem(
                                text    = {
                                    Column {
                                        Text("Keep existing auth",
                                             style = MaterialTheme.typography.bodyMedium)
                                        Text("Token already stored for this vault",
                                             style = MaterialTheme.typography.bodySmall,
                                             color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                },
                                onClick = { selectedIdentity = null; expanded = false },
                                contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                            )
                            HorizontalDivider()
                            gitHubIdentities.forEach { identity ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                Text(identity.label,
                                                     style = MaterialTheme.typography.bodyMedium)
                                                if (identity.isDefault) {
                                                    Text("default",
                                                         style = MaterialTheme.typography.labelSmall,
                                                         color = MaterialTheme.colorScheme.primary)
                                                }
                                            }
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

                Button(
                    onClick = {
                        // If a new identity was selected, update the stored token.
                        // If null ("keep existing"), pass blank so SettingsViewModel
                        // preserves whatever is already stored.
                        val token = selectedIdentity?.token ?: ""
                        viewModel.setVaultRemote(vaultId, remoteUrl.trim(), token, branch.trim())
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Save Changes") }

                OutlinedButton(
                    onClick  = { viewModel.syncVault(vaultId) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Sync Now") }

                syncMessage?.let { msg ->
                    Text(msg,
                         style    = MaterialTheme.typography.bodySmall,
                         color    = MaterialTheme.colorScheme.onSurfaceVariant,
                         modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.weight(1f))

                OutlinedButton(
                    onClick  = { showDeleteConfirm = true },
                    colors   = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Delete Vault") }
            }
        }

        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title   = { Text("Delete vault?") },
                text    = { Text("This permanently deletes the local vault files and all contents. This cannot be undone.") },
                confirmButton = {
                    TextButton(
                        onClick = { viewModel.deleteVault(vaultId); onNavigateBack() },
                        colors  = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error),
                    ) { Text("Delete") }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
                },
            )
        }
    }
