package com.vela.app.ui.nodes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodesScreen(
    onBack: () -> Unit,
    viewModel: NodesViewModel = hiltViewModel(),
) {
    val context  = LocalContext.current
    val nodes    by viewModel.nodes.collectAsState()
    val addError by viewModel.addError.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    if (showAddSheet) {
        AddNodeSheet(
            onDismiss       = { showAddSheet = false; viewModel.clearError() },
            onAddSsh        = { label, host, port, user ->
                viewModel.addNode(label, host, port, user)
                showAddSheet = false
            },
            onAddAmplifierd = { label, url, token ->
                viewModel.addAmplifierdNode(label, url, token)
                showAddSheet = false
            },
            error = addError,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Nodes") },
                actions = {
                    FilledTonalIconButton(onClick = { showAddSheet = true }) {
                        Icon(Icons.Default.Add, "Add node")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DeviceKeyCard(publicKey = viewModel.publicKey, context = context) }
            item {
                Text("Connected Nodes",
                     style = MaterialTheme.typography.titleSmall,
                     color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (nodes.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(vertical = 24.dp), Alignment.Center) {
                        Text(
                            "No nodes yet.\nAdd an SSH server or Amplifier daemon with the + button above.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(nodes, key = { it.id }) { node ->
                    NodeCard(
                        node         = node,
                        onDelete     = { viewModel.removeNode(node.id) },
                        onAddHost    = { newHost -> viewModel.addHostToNode(node.id, newHost) },
                        onRemoveHost = { host -> viewModel.removeHostFromNode(node.id, host) },
                    )
                }
            }
        }
    }
}

// ── Device identity key card ────────────────────────────────────────────────

@Composable
internal fun DeviceKeyCard(publicKey: String, context: Context) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Key, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Text("Device Identity Key", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            }
            Text(
                "Add this key to ~/.ssh/authorized_keys on each SSH node, or use it for mutual auth with amplifierd.",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Box(
                Modifier.fillMaxWidth().background(cs.surfaceContainer, RoundedCornerShape(8.dp)).padding(10.dp)
            ) {
                Text(
                    if (publicKey.isBlank()) "Generating key…" else publicKey,
                    style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color    = cs.onSurface,
                    maxLines = 4,
                )
            }
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Vela SSH Public Key", publicKey))
                    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy key")
            }
        }
    }
}

// ── Node card (type-aware) ───────────────────────────────────────────────────

@Composable
internal fun NodeCard(
    node: SshNode,
    onDelete: () -> Unit,
    onAddHost: (String) -> Unit,
    onRemoveHost: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var showAddIp by remember { mutableStateOf(false) }
    var newIp     by remember { mutableStateOf("") }
    val focusReq  = remember { FocusRequester() }
    val keyboard  = LocalSoftwareKeyboardController.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            // ── Header row: type chip + label + delete ──────────────────────
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val (icon, tint, badge) = when (node.type) {
                    NodeType.SSH        -> Triple(Icons.Default.Terminal,    cs.primary,            "SSH")
                    NodeType.AMPLIFIERD -> Triple(Icons.Default.Hub,         cs.tertiary,           "amplifierd")
                }
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
                SuggestionChip(
                    onClick  = {},
                    label    = { Text(badge, style = MaterialTheme.typography.labelSmall) },
                    modifier = Modifier.height(24.dp),
                )
                Text(node.label,
                     style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                     modifier = Modifier.weight(1f))
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Delete, "Delete node",
                         tint = cs.error.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                }
            }

            // ── Type-specific body ──────────────────────────────────────────
            when (node.type) {
                NodeType.SSH -> SshNodeBody(
                    node         = node,
                    showAddIp    = showAddIp,
                    newIp        = newIp,
                    focusReq     = focusReq,
                    onToggleAdd  = { showAddIp = it; if (it) { newIp = "" } },
                    onNewIpChange= { newIp = it },
                    onAddIp      = { onAddHost(newIp); showAddIp = false; newIp = ""; keyboard?.hide() },
                    onRemoveHost = onRemoveHost,
                )
                NodeType.AMPLIFIERD -> AmplifierdNodeBody(node = node)
            }
        }
    }
}

@Composable
private fun SshNodeBody(
    node: SshNode,
    showAddIp: Boolean,
    newIp: String,
    focusReq: FocusRequester,
    onToggleAdd: (Boolean) -> Unit,
    onNewIpChange: (String) -> Unit,
    onAddIp: () -> Unit,
    onRemoveHost: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // Host chips
    node.hosts.forEachIndexed { idx, host ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (idx == 0) "Primary" else "Fallback",
                style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.width(54.dp),
            )
            Box(
                Modifier.weight(1f).background(cs.surfaceContainer, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text("$host:${node.port}",
                     style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                     color = cs.onSurface)
            }
            if (idx > 0) {
                IconButton(onClick = { onRemoveHost(host) }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, "Remove", tint = cs.error.copy(alpha = 0.6f), modifier = Modifier.size(14.dp))
                }
            }
        }
    }
    Text("User: ${node.username}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)

    // Add fallback IP
    AnimatedVisibility(showAddIp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value         = newIp,
                onValueChange = onNewIpChange,
                placeholder   = { Text("IP or hostname") },
                singleLine    = true,
                modifier      = Modifier.weight(1f).focusRequester(focusReq),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onAddIp() }),
                textStyle = MaterialTheme.typography.bodySmall,
            )
            IconButton(onClick = onAddIp) { Icon(Icons.Default.Check, "Add") }
        }
    }
    LaunchedEffect(showAddIp) { if (showAddIp) focusReq.requestFocus() }

    TextButton(
        onClick  = { onToggleAdd(!showAddIp) },
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Icon(if (showAddIp) Icons.Default.Close else Icons.Default.Add,
             null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (showAddIp) "Cancel" else "+ Add fallback IP",
             style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun AmplifierdNodeBody(node: SshNode) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("URL", style = MaterialTheme.typography.labelSmall,
                 color = cs.onSurfaceVariant, modifier = Modifier.width(40.dp))
            Box(
                Modifier.weight(1f).background(cs.surfaceContainer, RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(node.url.ifBlank { "(not set)" },
                     style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                     color = cs.onSurface)
            }
        }
        if (node.token.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Token", style = MaterialTheme.typography.labelSmall,
                     color = cs.onSurfaceVariant, modifier = Modifier.width(40.dp))
                Text("••••${node.token.takeLast(4)}",
                     style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                     color = cs.onSurfaceVariant)
            }
        }
    }
}

// ── Add node sheet (type-aware) ─────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddNodeSheet(
    onDismiss: () -> Unit,
    onAddSsh: (label: String, host: String, port: String, user: String) -> Unit,
    onAddAmplifierd: (label: String, url: String, token: String) -> Unit,
    error: String?,
) {
    var selectedType by remember { mutableStateOf(NodeType.SSH) }

    // SSH fields
    var label    by remember { mutableStateOf("") }
    var host     by remember { mutableStateOf("") }
    var port     by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }

    // Amplifierd fields
    var ampLabel by remember { mutableStateOf("") }
    var ampUrl   by remember { mutableStateOf("http://") }
    var ampToken by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Add Node", style = MaterialTheme.typography.titleMedium)

            // ── Type picker ───────────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NodeType.entries.forEach { type ->
                    FilterChip(
                        selected = selectedType == type,
                        onClick  = { selectedType = type },
                        label    = { Text(if (type == NodeType.SSH) "SSH Server" else "Amplifier Daemon") },
                        leadingIcon = {
                            Icon(
                                if (type == NodeType.SSH) Icons.Default.Terminal else Icons.Default.Hub,
                                null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }

            // ── Type-specific fields ──────────────────────────────────────
            if (selectedType == NodeType.SSH) {
                OutlinedTextField(label.let { v -> v }, { label = it },
                    label = { Text("Label") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(host, { host = it },
                    label = { Text("Host / IP") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(port, { port = it },
                        label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(100.dp))
                    OutlinedTextField(username, { username = it },
                        label = { Text("Username") }, singleLine = true,
                        modifier = Modifier.weight(1f))
                }
                if (!error.isNullOrBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { onAddSsh(label, host, port, username) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = label.isNotBlank() && host.isNotBlank() && username.isNotBlank(),
                ) { Text("Add SSH Node") }
            } else {
                OutlinedTextField(ampLabel, { ampLabel = it },
                    label = { Text("Label") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ampUrl, { ampUrl = it },
                    label = { Text("URL (e.g. http://10.0.0.1:8410)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(ampToken, { ampToken = it },
                    label = { Text("Token (x-amplifier-token)") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                if (!error.isNullOrBlank()) {
                    Text(error, style = MaterialTheme.typography.bodySmall,
                         color = MaterialTheme.colorScheme.error)
                }
                Button(
                    onClick = { onAddAmplifierd(ampLabel, ampUrl, ampToken) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = ampLabel.isNotBlank() && ampUrl.length > 7,
                ) { Text("Add Amplifierd Node") }
            }
        }
    }
}


