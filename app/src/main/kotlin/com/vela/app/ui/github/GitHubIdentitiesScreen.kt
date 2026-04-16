package com.vela.app.ui.github

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.github.GitHubConnectResult
import com.vela.app.github.GitHubIdentity
import com.vela.app.github.GitHubIdentityManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

@HiltViewModel
class GitHubIdentitiesViewModel @Inject constructor(
    private val manager: GitHubIdentityManager,
) : ViewModel() {

    val identities: StateFlow<List<GitHubIdentity>> = manager.allFlow()
        .map { list -> list.map { e ->
            GitHubIdentity(e.id, e.label, e.username, e.avatarUrl, e.token, e.tokenType, e.scopes, e.addedAt, e.isDefault)
        }}
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _status = androidx.compose.runtime.mutableStateOf<String?>(null)
    val status: String? get() = _status.value

    private val _loading = androidx.compose.runtime.mutableStateOf(false)
    val loading: Boolean get() = _loading.value

    fun connectPat(label: String, token: String) {
        _loading.value = true
        _status.value = null
        viewModelScope.launch {
            when (val result = manager.connectWithPat(label, token)) {
                is GitHubConnectResult.Success -> _status.value = "Connected as @${result.identity.username}"
                is GitHubConnectResult.Error   -> _status.value = "Error: ${result.message}"
                else -> {}
            }
            _loading.value = false
        }
    }

    fun setDefault(id: String) = viewModelScope.launch { manager.setDefault(id) }
    fun delete(id: String)     = viewModelScope.launch { manager.delete(id) }
    fun clearStatus()           { _status.value = null }
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitHubIdentitiesScreen(
    onBack: () -> Unit,
    viewModel: GitHubIdentitiesViewModel = hiltViewModel(),
) {
    val identities by viewModel.identities.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    val status = viewModel.status

    if (showAddSheet) {
        AddIdentitySheet(
            loading  = viewModel.loading,
            onDismiss = { showAddSheet = false; viewModel.clearStatus() },
            onAdd     = { label, token ->
                viewModel.connectPat(label, token)
            },
            statusMessage = status,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title  = { Text("GitHub Accounts") },
                actions = {
                    FilledTonalIconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, "Add account")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (identities.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.AccountCircle, null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                            Text("No GitHub accounts connected.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tap + to add a PAT or connect via GitHub.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            } else {
                items(identities, key = { it.id }) { identity ->
                    IdentityCard(
                        identity  = identity,
                        onSetDefault = { viewModel.setDefault(identity.id) },
                        onDelete     = { viewModel.delete(identity.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun IdentityCard(
    identity: GitHubIdentity,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.AccountCircle, null,
                modifier = Modifier.size(40.dp),
                tint = if (identity.isDefault) cs.primary else cs.onSurfaceVariant)

            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(identity.label, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
                    if (identity.isDefault) {
                        SuggestionChip(onClick = {}, label = { Text("default", style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.height(22.dp))
                    }
                }
                Text("@${identity.username}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text(identity.tokenType.uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
            }

            if (!identity.isDefault) {
                IconButton(onClick = onSetDefault, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Star, "Set default",
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "Remove",
                    tint = cs.error.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddIdentitySheet(
    loading: Boolean,
    onDismiss: () -> Unit,
    onAdd: (label: String, token: String) -> Unit,
    statusMessage: String?,
) {
    var label by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme

    // Auto-close on success
    LaunchedEffect(statusMessage) {
        if (statusMessage?.startsWith("Connected") == true) {
            kotlinx.coroutines.delay(1200)
            onDismiss()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Connect GitHub Account", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enter a Personal Access Token (PAT) with repo, read:org, and read:project scopes.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )

            OutlinedTextField(label, { label = it },
                label = { Text("Label (e.g. Work, Personal)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())

            OutlinedTextField(token, { token = it },
                label = { Text("Personal Access Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())

            if (!statusMessage.isNullOrBlank()) {
                val isError = statusMessage.startsWith("Error")
                Text(statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isError) cs.error else cs.primary)
            }

            Button(
                onClick = { onAdd(label, token) },
                modifier = Modifier.fillMaxWidth(),
                enabled = !loading && token.isNotBlank(),
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = cs.onPrimary)
                    Spacer(Modifier.width(8.dp))
                    Text("Connecting…")
                } else {
                    Text("Connect")
                }
            }
        }
    }
}
