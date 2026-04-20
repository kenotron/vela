package com.vela.app.ui.connectors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.vela.app.ui.nodes.NodesViewModel

// ── Connector catalog model ───────────────────────────────────────────────────

private enum class ConnectorType(
    val displayName: String,
    val tagline: String,
    val icon: ImageVector,
    val iconTint: Color,
    val available: Boolean,
) {
    SSH(
        displayName = "SSH Server",
        tagline     = "Connect to a Linux or macOS machine over SSH",
        icon        = Icons.Default.Terminal,
        iconTint    = Color(0xFF4A90D9),
        available   = true,
    ),
    AMPLIFIER(
        displayName = "Amplifier Server",
        tagline     = "Connect to a self-hosted Amplifier daemon",
        icon        = Icons.Default.Hub,
        iconTint    = Color(0xFF7C4DFF),
        available   = true,
    ),
    GITHUB(
        displayName = "GitHub",
        tagline     = "Browse repos, pull requests, and issues",
        icon        = Icons.Default.Code,
        iconTint    = Color(0xFF24292F),
        available   = false,
    ),
    GMAIL(
        displayName = "Gmail",
        tagline     = "Read, search, and summarise your inbox",
        icon        = Icons.Default.Mail,
        iconTint    = Color(0xFFEA4335),
        available   = false,
    ),
}

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    viewModel: NodesViewModel = hiltViewModel(),
    modifier: Modifier = Modifier,
) {
    val nodes       by viewModel.nodes.collectAsState()
    val addError    by viewModel.addError.collectAsState()
    val context     = LocalContext.current

    var expanded    by remember { mutableStateOf<ConnectorType?>(null) }
    var showSshForm by remember { mutableStateOf(false) }
    var showAmpForm by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar   = {
            TopAppBar(title = { Text("Connectors") })
        },
    ) { pad ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {

            // ── Connector catalog ─────────────────────────────────────────
            items(ConnectorType.entries.toList()) { connector ->
                val connectedNodes = when (connector) {
                    ConnectorType.SSH       -> nodes.filter { it.type == NodeType.SSH }
                    ConnectorType.AMPLIFIER -> nodes.filter { it.type == NodeType.AMPLIFIERD }
                    else                    -> emptyList()
                }
                val isExpanded = expanded == connector

                ConnectorCard(
                    connector      = connector,
                    connectedCount = connectedNodes.size,
                    isExpanded     = isExpanded,
                    onClick        = {
                        expanded = if (isExpanded) null else connector
                        showSshForm = false
                        showAmpForm = false
                        viewModel.clearError()
                    },
                ) {
                    // ── Expanded detail ───────────────────────────────────
                    when (connector) {

                        ConnectorType.SSH -> SshDetail(
                            nodes        = connectedNodes,
                            publicKey    = viewModel.publicKey,
                            context      = context,
                            showForm     = showSshForm,
                            addError     = addError,
                            onToggleForm = { showSshForm = it; viewModel.clearError() },
                            onAdd        = { label, host, port, user ->
                                viewModel.addNode(label, host, port, user)
                                showSshForm = false
                            },
                            onAddHost    = { id, h -> viewModel.addHostToNode(id, h) },
                            onRemoveHost = { id, h -> viewModel.removeHostFromNode(id, h) },
                            onDelete     = { viewModel.removeNode(it) },
                        )

                        ConnectorType.AMPLIFIER -> AmplifierDetail(
                            nodes        = connectedNodes,
                            showForm     = showAmpForm,
                            addError     = addError,
                            onToggleForm = { showAmpForm = it; viewModel.clearError() },
                            onAdd        = { label, url, token ->
                                viewModel.addAmplifierdNode(label, url, token)
                                showAmpForm = false
                            },
                            onDelete     = { viewModel.removeNode(it) },
                        )

                        else -> ComingSoonDetail(connector.displayName)
                    }
                }
            }

            item { Spacer(Modifier.height(40.dp)) }
        }
    }
}

// ── Connector card ────────────────────────────────────────────────────────────

@Composable
private fun ConnectorCard(
    connector:      ConnectorType,
    connectedCount: Int,
    isExpanded:     Boolean,
    onClick:        () -> Unit,
    expandedContent: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape  = RoundedCornerShape(16.dp),
        color  = cs.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (isExpanded) Modifier.border(1.dp, cs.primary.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
                else Modifier
            ),
    ) {
        Column {
            // ── Header row ────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onClick)
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Icon badge
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(connector.iconTint.copy(alpha = 0.12f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(connector.icon, null, tint = connector.iconTint, modifier = Modifier.size(24.dp))
                }

                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(connector.displayName, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        if (!connector.available) {
                            Surface(
                                color = cs.secondaryContainer,
                                shape = RoundedCornerShape(4.dp),
                            ) {
                                Text(
                                    "Soon",
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = cs.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                    Text(connector.tagline, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }

                // Connected badge
                if (connectedCount > 0) {
                    Box(
                        modifier = Modifier
                            .size(22.dp)
                            .background(cs.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "$connectedCount",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = cs.onPrimary,
                            fontSize = 10.sp,
                        )
                    }
                }

                Icon(
                    if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = cs.onSurfaceVariant,
                )
            }

            // ── Expanded panel ────────────────────────────────────────────
            AnimatedVisibility(
                visible = isExpanded,
                enter   = expandVertically(),
                exit    = shrinkVertically(),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                    HorizontalDivider(color = cs.outlineVariant.copy(alpha = 0.4f))
                    Spacer(Modifier.height(12.dp))
                    expandedContent()
                }
            }
        }
    }
}

// ── SSH detail panel ──────────────────────────────────────────────────────────

@Composable
private fun SshDetail(
    nodes:        List<SshNode>,
    publicKey:    String,
    context:      Context,
    showForm:     Boolean,
    addError:     String?,
    onToggleForm: (Boolean) -> Unit,
    onAdd:        (label: String, host: String, port: String, user: String) -> Unit,
    onAddHost:    (nodeId: String, host: String) -> Unit,
    onRemoveHost: (nodeId: String, host: String) -> Unit,
    onDelete:     (id: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme

    // Connected SSH nodes
    if (nodes.isNotEmpty()) {
        nodes.forEach { node ->
            ConnectedNodeRow(
                label      = node.label,
                detail     = "${node.hosts.firstOrNull() ?: ""}:${node.port}  (${node.username})",
                typeColor  = Color(0xFF4A90D9),
                onDelete   = { onDelete(node.id) },
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(8.dp))
    }

    // Add new SSH node form
    AnimatedVisibility(showForm) {
        SshAddForm(
            error       = addError,
            onAdd       = onAdd,
            onCancel    = { onToggleForm(false) },
        )
    }

    if (!showForm) {
        OutlinedButton(
            onClick  = { onToggleForm(true) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add SSH Server")
        }
    }

    // Device key (folded away since it's advanced)
    var showKey by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    TextButton(
        onClick  = { showKey = !showKey },
        contentPadding = PaddingValues(horizontal = 0.dp),
    ) {
        Icon(Icons.Default.Key, null, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(4.dp))
        Text(if (showKey) "Hide device key" else "Show device SSH key",
             style = MaterialTheme.typography.labelSmall)
    }
    AnimatedVisibility(showKey) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Add this key to ~/.ssh/authorized_keys on your server:",
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer, RoundedCornerShape(8.dp))
                    .padding(10.dp)
            ) {
                Text(
                    if (publicKey.isBlank()) "Generating…" else publicKey,
                    style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                    color    = cs.onSurface,
                    maxLines = 4,
                )
            }
            OutlinedButton(
                onClick  = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Vela SSH Key", publicKey))
                    Toast.makeText(context, "Key copied", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.align(Alignment.End),
            ) {
                Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Copy key")
            }
        }
    }
}

@Composable
private fun SshAddForm(
    error:    String?,
    onAdd:    (String, String, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var label    by remember { mutableStateOf("") }
    var host     by remember { mutableStateOf("") }
    var port     by remember { mutableStateOf("22") }
    var username by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Text("New SSH Server", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(label, { label = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(host, { host = it }, label = { Text("Host / IP address") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(port, { port = it }, label = { Text("Port") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(90.dp))
            OutlinedTextField(username, { username = it }, label = { Text("Username") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        if (!error.isNullOrBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick  = { onAdd(label, host, port, username) },
                modifier = Modifier.weight(1f),
                enabled  = label.isNotBlank() && host.isNotBlank() && username.isNotBlank(),
            ) { Text("Connect") }
        }
    }
}

// ── Amplifier Server detail panel ─────────────────────────────────────────────

@Composable
private fun AmplifierDetail(
    nodes:        List<SshNode>,
    showForm:     Boolean,
    addError:     String?,
    onToggleForm: (Boolean) -> Unit,
    onAdd:        (label: String, url: String, token: String) -> Unit,
    onDelete:     (id: String) -> Unit,
) {
    if (nodes.isNotEmpty()) {
        nodes.forEach { node ->
            ConnectedNodeRow(
                label     = node.label,
                detail    = node.url,
                typeColor = Color(0xFF7C4DFF),
                onDelete  = { onDelete(node.id) },
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(8.dp))
    }

    AnimatedVisibility(showForm) {
        AmplifierAddForm(
            error    = addError,
            onAdd    = onAdd,
            onCancel = { onToggleForm(false) },
        )
    }

    if (!showForm) {
        OutlinedButton(
            onClick  = { onToggleForm(true) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add Amplifier Server")
        }
    }
}

@Composable
private fun AmplifierAddForm(
    error:    String?,
    onAdd:    (String, String, String) -> Unit,
    onCancel: () -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var url   by remember { mutableStateOf("http://") }
    var token by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
        Text("New Amplifier Server", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(label, { label = it }, label = { Text("Name") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(url, { url = it }, label = { Text("URL  (e.g. http://10.0.0.1:8410)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth())
        OutlinedTextField(token, { token = it }, label = { Text("Token (optional)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth())
        if (!error.isNullOrBlank()) {
            Text(error, style = MaterialTheme.typography.bodySmall,
                 color = MaterialTheme.colorScheme.error)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(
                onClick  = { onAdd(label, url, token) },
                modifier = Modifier.weight(1f),
                enabled  = label.isNotBlank() && url.length > 7,
            ) { Text("Connect") }
        }
    }
}

// ── Coming soon panel ─────────────────────────────────────────────────────────

@Composable
private fun ComingSoonDetail(name: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier            = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Schedule, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
        Text(
            "$name is coming soon",
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurfaceVariant,
        )
        Text(
            "We're working on it. Stay tuned!",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

// ── Connected node row ────────────────────────────────────────────────────────

@Composable
private fun ConnectedNodeRow(
    label:     String,
    detail:    String,
    typeColor: Color,
    onDelete:  () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier             = Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment    = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier.size(8.dp).background(typeColor, CircleShape),
        )
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(detail, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                 color = cs.onSurfaceVariant, maxLines = 1)
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, "Remove", tint = cs.error.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
        }
    }
}
