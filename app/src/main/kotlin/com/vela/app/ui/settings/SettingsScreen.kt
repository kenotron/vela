package com.vela.app.ui.settings

    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.*
    import androidx.compose.foundation.lazy.LazyColumn
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.automirrored.filled.ArrowBack
    import androidx.compose.material.icons.filled.Add
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.unit.dp
    import androidx.hilt.navigation.compose.hiltViewModel

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsScreen(
        onNavigateBack: () -> Unit,
        onNavigateToNodes: () -> Unit,
        onNavigateToVaultDetail: (vaultId: String) -> Unit,
        viewModel: SettingsViewModel = hiltViewModel(),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
                )
            },
        ) { pad ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(pad),
            ) {
                item { SectionHeader("AI") }
                item { AiSection(viewModel) }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

                item { SectionHeader("Connections") }
                item { ConnectionsSection(onNavigateToNodes = onNavigateToNodes) }
                item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

                item { SectionHeader("Vaults") }
                item {
                    VaultsSection(
                        viewModel               = viewModel,
                        onNavigateToVaultDetail = onNavigateToVaultDetail,
                    )
                }
            }
        }
    }

    @Composable
    internal fun SectionHeader(title: String) {
        Text(
            text     = title,
            style    = MaterialTheme.typography.titleSmall,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }

    // ── AI Section ────────────────────────────────────────────────────────────────────────────────

    @Composable
    private fun AiSection(viewModel: SettingsViewModel) {
        val apiKey        by viewModel.apiKey.collectAsState()
        val selectedModel by viewModel.selectedModel.collectAsState()

        var showApiKeyDialog by remember { mutableStateOf(false) }
        var showModelPicker  by remember { mutableStateOf(false) }

        val maskedKey = when {
            apiKey.isBlank()   -> "(not set)"
            apiKey.length > 4  -> "sk-ant-…\${apiKey.takeLast(4)}"
            else               -> "sk-ant-…"
        }

        ListItem(
            headlineContent   = { Text("Anthropic API Key") },
            supportingContent = { Text(maskedKey, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            trailingContent   = { TextButton(onClick = { showApiKeyDialog = true }) { Text("Edit") } },
        )
        ListItem(
            headlineContent   = { Text("Model") },
            supportingContent = { Text(selectedModel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
            modifier          = Modifier.clickable { showModelPicker = true },
        )

        if (showApiKeyDialog) {
            ApiKeyEditDialog(
                currentKey = apiKey,
                onDismiss  = { showApiKeyDialog = false },
                onConfirm  = { key -> viewModel.setApiKey(key); showApiKeyDialog = false },
            )
        }
        if (showModelPicker) {
            ModelPickerDialog(
                currentModel = selectedModel,
                onDismiss    = { showModelPicker = false },
                onSelect     = { model -> viewModel.setModel(model); showModelPicker = false },
            )
        }
    }

    @Composable
    private fun ApiKeyEditDialog(currentKey: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
        var key by remember { mutableStateOf(currentKey) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title            = { Text("Anthropic API Key") },
            text             = {
                OutlinedTextField(
                    value         = key,
                    onValueChange = { key = it },
                    label         = { Text("sk-ant-…") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
            },
            confirmButton  = { Button(onClick = { if (key.isNotBlank()) onConfirm(key.trim()) }, enabled = key.isNotBlank()) { Text("Save") } },
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

    // ── Connections Section ────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConnectionsSection(
        onNavigateToNodes: () -> Unit,
        nodesViewModel: com.vela.app.ui.nodes.NodesViewModel = hiltViewModel(),
    ) {
        val nodes    by nodesViewModel.nodes.collectAsState()
        val addError by nodesViewModel.addError.collectAsState()
        var showAddSheet by remember { mutableStateOf(false) }
        val context = androidx.compose.ui.platform.LocalContext.current

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

        com.vela.app.ui.nodes.DeviceKeyCard(publicKey = nodesViewModel.publicKey, context = context)

        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Connected Nodes", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            FilledTonalIconButton(onClick = { showAddSheet = true }) { Icon(Icons.Default.Add, "Add node") }
        }

        if (nodes.isEmpty()) {
            Text(
                "No nodes yet.\nAdd one and paste the key above into ~/.ssh/authorized_keys on the remote machine.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
        } else {
            nodes.forEach { node ->
                com.vela.app.ui.nodes.NodeCard(
                    node         = node,
                    onDelete     = { nodesViewModel.removeNode(node.id) },
                    onAddHost    = { newHost -> nodesViewModel.addHostToNode(node.id, newHost) },
                    onRemoveHost = { host -> nodesViewModel.removeHostFromNode(node.id, host) },
                )
            }
        }
    }

    // ── Vaults Section ────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun VaultsSection(
        viewModel: SettingsViewModel,
        onNavigateToVaultDetail: (vaultId: String) -> Unit,
    ) {
        val vaults by viewModel.vaults.collectAsState()
        var showAddSheet by remember { mutableStateOf(false) }

        if (showAddSheet) {
            AddVaultSheet(
                onDismiss = { showAddSheet = false },
                onConfirm = { name, remoteUrl, pat ->
                    viewModel.addVault(name, remoteUrl, pat)
                    showAddSheet = false
                },
            )
        }

        if (vaults.isEmpty()) {
            Text(
                "No vaults yet.",
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        } else {
            vaults.forEach { vault ->
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
                        Switch(checked = vault.isEnabled, onCheckedChange = { viewModel.setVaultEnabled(vault.id, it) })
                    },
                    modifier = Modifier.clickable { onNavigateToVaultDetail(vault.id) },
                )
            }
        }

        OutlinedButton(
            onClick  = { showAddSheet = true },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Add Vault")
        }
    }

    // ── AddVaultSheet ─────────────────────────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    internal fun AddVaultSheet(
        onDismiss: () -> Unit,
        onConfirm: (name: String, remoteUrl: String, pat: String) -> Unit,
    ) {
        var name       by remember { mutableStateOf("") }
        var remoteUrl  by remember { mutableStateOf("") }
        var pat        by remember { mutableStateOf("") }
        var showGitHub by remember { mutableStateOf(false) }
        var nameError  by remember { mutableStateOf(false) }

        ModalBottomSheet(onDismissRequest = onDismiss) {
            Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 32.dp)) {
                Text("Add Vault", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value           = name,
                    onValueChange   = { name = it; nameError = false },
                    label           = { Text("Vault name") },
                    isError         = nameError,
                    supportingText  = if (nameError) {{ Text("Name is required") }} else null,
                    singleLine      = true,
                    modifier        = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                TextButton(onClick = { showGitHub = !showGitHub }) {
                    Text(if (showGitHub) "Hide GitHub sync" else "Connect GitHub (optional)")
                }

                if (showGitHub) {
                    OutlinedTextField(value = remoteUrl, onValueChange = { remoteUrl = it }, label = { Text("GitHub repo URL") }, placeholder = { Text("https://github.com/user/vault.git") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = pat, onValueChange = { pat = it }, label = { Text("Personal Access Token") }, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        if (name.isBlank()) { nameError = true; return@Button }
                        onConfirm(name.trim(), remoteUrl.trim(), pat.trim())
                    }) { Text("Create Vault") }
                }
            }
        }
    }
    