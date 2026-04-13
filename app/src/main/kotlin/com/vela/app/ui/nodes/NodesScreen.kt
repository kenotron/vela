package com.vela.app.ui.nodes

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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
    val context = LocalContext.current
    val nodes   by viewModel.nodes.collectAsState()
    val addError by viewModel.addError.collectAsState()

    var showAddSheet by remember { mutableStateOf(false) }

    if (showAddSheet) {
        AddNodeSheet(
            onDismiss = { showAddSheet = false; viewModel.clearError() },
            onAdd     = { label, host, port, user ->
                viewModel.addNode(label, host, port, user)
            },
            error     = addError,
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
            // ── Device identity key ────────────────────────────────────────────
            item {
                DeviceKeyCard(publicKey = viewModel.publicKey, context = context)
            }

            item {
                Text(
                    "Connected Nodes",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── Node list ──────────────────────────────────────────────────────
            if (nodes.isEmpty()) {
                item {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No nodes yet. Add one and paste the key above into\n~/.ssh/authorized_keys on the remote machine.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                items(nodes, key = { it.id }) { node ->
                    NodeCard(node = node, onDelete = { viewModel.removeNode(node.id) })
                }
            }
        }
    }
}

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
            // Public key display
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            ) {
                Text(
                    if (publicKey.isBlank()) "Generating key\u2026" else publicKey,
                    style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color    = cs.onSurface,
                    maxLines = 5,
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

@Composable
private fun NodeCard(node: SshNode, onDelete: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(14.dp),
        colors   = CardDefaults.cardColors(containerColor = cs.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(cs.secondaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("\uD83D\uDCBB", fontSize = 18.sp)
            }
            Column(Modifier.weight(1f)) {
                Text(node.label, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold))
                Text(
                    "${node.username}@${node.host}:${node.port}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = cs.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, "Remove node", tint = cs.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add Node", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            OutlinedTextField(value = label,    onValueChange = { label = it },    label = { Text("Label") }, placeholder = { Text("MacBook Pro") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            OutlinedTextField(value = host,     onValueChange = { host = it },     label = { Text("Host") },  placeholder = { Text("192.168.1.50 or hostname") }, modifier = Modifier.fillMaxWidth(), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, modifier = Modifier.weight(2f), singleLine = true)
                OutlinedTextField(value = port,     onValueChange = { port = it },     label = { Text("Port") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = {
                    onAdd(label, host, port, username)
                    if (error == null) onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Add Node") }
        }
    }
}
