package com.vela.app.ui.settings

    import androidx.compose.foundation.layout.*
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.unit.dp
    import androidx.hilt.navigation.compose.hiltViewModel

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun VaultDetailScreen(
        vaultId: String,
        onNavigateBack: () -> Unit,
        viewModel: SettingsViewModel = hiltViewModel(),
    ) {
        val vaults      by viewModel.vaults.collectAsState()
        val syncMessage by viewModel.syncMessage.collectAsState()
        val vault = vaults.firstOrNull { it.id == vaultId }

        if (vault == null) {
            LaunchedEffect(Unit) { onNavigateBack() }
            return
        }

        var remoteUrl         by remember(vaultId) { mutableStateOf(viewModel.getVaultRemoteUrl(vaultId)) }
        var pat               by remember { mutableStateOf("") }
        var branch            by remember(vaultId) { mutableStateOf(viewModel.getVaultBranch(vaultId)) }
        var showDeleteConfirm by remember { mutableStateOf(false) }

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
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = remoteUrl, onValueChange = { remoteUrl = it }, label = { Text("GitHub remote URL") }, placeholder = { Text("https://github.com/user/vault.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = pat, onValueChange = { pat = it }, label = { Text("Personal Access Token (leave blank to keep existing)") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value         = branch,
                    onValueChange = { branch = it },
                    label         = { Text("Branch (optional)") },
                    placeholder   = { Text("main, master — leave blank to auto-detect") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))

                Button(onClick = { viewModel.setVaultRemote(vaultId, remoteUrl.trim(), pat.trim(), branch.trim()) }, modifier = Modifier.fillMaxWidth()) { Text("Save Changes") }
                OutlinedButton(onClick = { viewModel.syncVault(vaultId) }, modifier = Modifier.fillMaxWidth()) { Text("Sync Now") }

                syncMessage?.let { msg ->
                    Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.weight(1f))

                OutlinedButton(
                    onClick  = { showDeleteConfirm = true },
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
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
                    TextButton(onClick = { viewModel.deleteVault(vaultId); onNavigateBack() }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
                },
                dismissButton = { TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") } },
            )
        }
    }
    