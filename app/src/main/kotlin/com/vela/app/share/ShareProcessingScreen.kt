package com.vela.app.share

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ShareProcessingScreen(
    onDone: (conversationId: String) -> Unit,
    onDismiss: () -> Unit,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (val s = state) {
                is ShareState.Idle -> {
                    CircularProgressIndicator()
                    Text("Preparing…")
                }
                is ShareState.Preview -> {
                    Text(s.label, style = MaterialTheme.typography.titleMedium)
                    Text(
                        s.contentSummary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    ) {
                        TextButton(onClick = { viewModel.reset(); onDismiss() }) {
                            Icon(Icons.Default.Close, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Cancel")
                        }
                        Button(onClick = { viewModel.processIntoVault() }) {
                            Text("Process into vault")
                        }
                    }
                }
                is ShareState.Processing -> {
                    CircularProgressIndicator()
                    Text("Creating vault session…")
                }
                is ShareState.Done -> {
                    Icon(Icons.Default.Check, null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp))
                    Text("Ready!", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { onDone(s.conversationId) }) {
                        Text("Open conversation")
                    }
                }
                is ShareState.Error -> {
                    Text("Error: ${s.message}",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.reset(); onDismiss() }) { Text("Close") }
                }
            }
        }
    }
}
