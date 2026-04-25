package com.vela.app.ui.conversation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lists every `.md` file in `<vaultPath>/.agents/`. Tapping a file dismisses
 * the dialog and triggers [onInstalled] — which the parent uses to refresh the
 * agent registry. ("Install" = the file is already on disk; this exposes it.)
 *
 * Renders an empty-state message if the directory is missing or empty.
 */
@Composable
internal fun AgentInstallerDialog(
    vaultPath: String,
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    var files by remember { mutableStateOf<List<File>>(emptyList()) }

    LaunchedEffect(vaultPath) {
        files = withContext(Dispatchers.IO) {
            listAgentFiles(vaultPath)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install agent") },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Drop `.md` agent bundles into $vaultPath/.agents/ — they'll appear here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (files.isEmpty()) {
                    Text(
                        "No agent files found in .agents/",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(items = files, key = { it.absolutePath }) { file ->
                            ListItem(
                                headlineContent = { Text(file.name) },
                                modifier        = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                supportingContent = {
                                    Text(
                                        text  = "${file.length()} bytes",
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstalled) { Text("Refresh") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss)   { Text("Close") }
        },
    )
}

/**
 * Returns the sorted list of `.md` files inside `<vaultPath>/.agents/`.
 * Returns an empty list if the directory does not exist or contains no `.md` files.
 * Only immediate children are considered (no recursion into sub-directories).
 */
internal fun listAgentFiles(vaultPath: String): List<File> {
    val dir = File(vaultPath, ".agents")
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
        ?.toList()
        ?.sortedBy { it.name }
        ?: emptyList()
}
