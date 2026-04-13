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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Terminal
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
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
            onDismiss = { showAddSheet = false; viewModel.clearError() },
            onAdd     = { label, host, port, user ->
                viewModel.addNode(label, host, port, user)
                showAddSheet = false
            },
            error = addError,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text("Vela Nodes") },
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
                            "No nodes yet.\nAdd one and paste the key above into ~/.ssh/authorized_keys on the remote machine.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(nodes, key = { it.id }) { node ->
                    NodeCard(
                        node      = node,
                        onDelete  = { viewModel.removeNode(node.id) },
                        onAddHost = { newHost -> viewModel.addHostToNode(node.id, newHost) },
                        onRemoveHost = { host -> viewModel.removeHostFromNode(node.id, host) },
                    )
                }
            }
        }
    }
}

// ── Device identity key card ──────────────────────────────────────────────────

@Composable
private fun DeviceKeyCard(publicKey: String, context: Context) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Terminal, null, tint = cs.primary, modifier = Modifier.size(20.dp))
                Text("Device Identity Key", style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold))
            }
            Text(
                "Add this key to ~/.ssh/authorized_keys on each machine you want Vela to control.",
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

// ── Node card with multiple IPs ───────────────────────────────────────────────

@Composable
private fun NodeCard(
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

            // Header row: icon + label + delete
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(40.dp).background(cs.secondaryContainer, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) { Text("💻", fontSize = 18.sp) }

                Column(Modifier.weight(1f)) {
                    Text(node.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                    Text(
                        "${node.username}  ·  port ${node.port}",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = cs.onSurfaceVariant,
                    )
                }

                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, "Remove node", tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }

            // IP address chips
            Text("Addresses (tried in order)",
                 style = MaterialTheme.typography.labelSmall,
                 color = cs.onSurfaceVariant)

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement   = Arrangement.spacedBy(6.dp),
            ) {
                node.hosts.forEachIndexed { idx, host ->
                    IpChip(
                        host        = host,
                        isPrimary   = idx == 0,
                        canRemove   = node.hosts.size > 1,
                        onRemove    = { onRemoveHost(host) },
                    )
                }

                // "+ Add IP" chip
                if (!showAddIp) {
                    AssistChip(
                        onClick = { showAddIp = true },
                        label   = { Text("+ Add address", style = MaterialTheme.typography.labelSmall) },
                        colors  = AssistChipDefaults.assistChipColors(
                            containerColor = cs.surfaceContainerHigh,
                            labelColor     = cs.primary,
                        ),
                    )
                }
            }

            // Inline add-IP field
            AnimatedVisibility(visible = showAddIp) {
                LaunchedEffect(showAddIp) {
                    if (showAddIp) focusReq.requestFocus()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value         = newIp,
                        onValueChange = { newIp = it },
                        modifier      = Modifier.weight(1f).focusRequester(focusReq),
                        placeholder   = { Text("192.168.x.x or hostname") },
                        singleLine    = true,
                        textStyle     = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Uri,
                            imeAction    = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newIp.isNotBlank()) { onAddHost(newIp.trim()); newIp = "" }
                            showAddIp = false
                            keyboard?.hide()
                        }),
                    )
                    IconButton(onClick = {
                        if (newIp.isNotBlank()) { onAddHost(newIp.trim()); newIp = "" }
                        showAddIp = false
                        keyboard?.hide()
                    }) { Icon(Icons.Default.Add, "Add", tint = cs.primary) }
                    IconButton(onClick = { showAddIp = false; newIp = "" }) {
                        Icon(Icons.Default.Close, "Cancel", tint = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun IpChip(host: String, isPrimary: Boolean, canRemove: Boolean, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    InputChip(
        selected  = isPrimary,
        onClick   = {},
        label     = {
            Text(
                if (isPrimary) "$host  (primary)" else host,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            )
        },
        trailingIcon = if (canRemove) {
            {
                IconButton(onClick = onRemove, modifier = Modifier.size(16.dp)) {
                    Icon(Icons.Default.Close, "Remove", modifier = Modifier.size(12.dp))
                }
            }
        } else null,
        colors = InputChipDefaults.inputChipColors(
            selectedContainerColor = cs.primaryContainer,
            selectedLabelColor     = cs.onPrimaryContainer,
        ),
    )
}

// ── Add node sheet ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddNodeSheet(
    onDismiss: () -> Unit,
    onAdd:     (label: String, host: String, port: String, username: String) -> Unit,
    error:     String?,
) {
    var label    by remember { mutableStateOf("") }
    var host     by remember { mutableStateOf("") }
    var port     by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Node", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            Text("You can add more IP addresses per node later — for when the same machine is on different networks.",
                 style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("Label") }, placeholder = { Text("MacBook Pro") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Primary IP / hostname") }, placeholder = { Text("192.168.1.50") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.weight(2f), singleLine = true)
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(onClick = { onAdd(label, host, port, username) }, modifier = Modifier.fillMaxWidth()) { Text("Add Node") }
        }
    }
}
