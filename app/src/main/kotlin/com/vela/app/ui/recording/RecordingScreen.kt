package com.vela.app.ui.recording

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.recording.RecordingStatus
import com.vela.app.recording.RecordingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onNavigateBack: () -> Unit,
    onVaultSessionCreated: (conversationId: String, stagedMessage: String) -> Unit,
    viewModel: RecordingViewModel = hiltViewModel(),
) {
    val state by viewModel.repository.state.collectAsState()
    var showPermissionRationale by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
        else showPermissionRationale = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.reset(); onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text("Recording") },
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            // Timer
            Text(
                text = viewModel.repository.formatElapsed(state.elapsedSeconds),
                style = MaterialTheme.typography.displayMedium,
                color = when (state.status) {
                    RecordingStatus.RECORDING    -> MaterialTheme.colorScheme.error
                    RecordingStatus.TRANSCRIBING -> MaterialTheme.colorScheme.primary
                    else                         -> MaterialTheme.colorScheme.onSurface
                }
            )

            // Status text
            Text(
                text = when (state.status) {
                    RecordingStatus.IDLE         -> "Ready to record"
                    RecordingStatus.RECORDING    -> "Recording…"
                    RecordingStatus.TRANSCRIBING -> "Transcribing…"
                    RecordingStatus.DONE         -> "Transcript ready"
                    RecordingStatus.ERROR        -> "Error: ${state.error}"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Big record / stop button
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(
                initialValue = 1f, targetValue = 1.08f,
                animationSpec = infiniteRepeatable(tween(800)),
                label = "scale",
            )
            val buttonScale = if (state.status == RecordingStatus.RECORDING) scale else 1f

            FilledIconButton(
                onClick = {
                    when (state.status) {
                        RecordingStatus.IDLE -> {
                            if (viewModel.hasMicPermission()) viewModel.startRecording()
                            else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                        RecordingStatus.RECORDING -> viewModel.stopRecording()
                        else -> Unit
                    }
                },
                modifier = Modifier.size(96.dp).scale(buttonScale),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = if (state.status == RecordingStatus.RECORDING)
                        MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                ),
                enabled = state.status in listOf(RecordingStatus.IDLE, RecordingStatus.RECORDING),
            ) {
                Icon(
                    imageVector = if (state.status == RecordingStatus.RECORDING) Icons.Default.Stop else Icons.Default.Mic,
                    contentDescription = if (state.status == RecordingStatus.RECORDING) "Stop" else "Record",
                    modifier = Modifier.size(40.dp),
                )
            }

            // Ready to send — shown after recording stops
            if (state.status == RecordingStatus.IDLE && state.outputFile != null && state.transcript == null) {
                HorizontalDivider()
                // File info
                Text(
                    text = "Saved: ${state.outputFile!!.name}  (${viewModel.repository.formatElapsed(state.elapsedSeconds)})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        viewModel.createVaultSession { convId, message ->
                            onVaultSessionCreated(convId, message)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Send to vault")
                }
                TextButton(onClick = { viewModel.reset() }) { Text("Discard") }
            }
        }
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title = { Text("Microphone permission needed") },
            text  = { Text("Vela needs microphone access to record audio for transcription.") },
            confirmButton = {
                TextButton(onClick = { showPermissionRationale = false }) { Text("OK") }
            }
        )
    }
}
