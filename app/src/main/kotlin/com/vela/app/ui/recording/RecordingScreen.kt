package com.vela.app.ui.recording

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.recording.RecordingStatus
import com.vela.app.recording.RecordingViewModel

/**
 * Record audio → auto-transcribe with AI → pass transcript text back to the
 * conversation as a ready-to-send attachment.
 *
 * Flow:
 *   1. Tap mic → recording starts
 *   2. Tap stop → transcription begins immediately (Gemini or Whisper)
 *   3. Transcript ready → [onTranscriptReady] fires, screen navigates away
 *
 * The caller is responsible for staging the transcript in the composer
 * (e.g. as a pre-filled attachment or text input).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    onNavigateBack: () -> Unit,
    onTranscriptReady: (transcript: String) -> Unit,
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

    // Auto-navigate as soon as the transcript is ready
    LaunchedEffect(state.status, state.transcript) {
        if (state.status == RecordingStatus.DONE && !state.transcript.isNullOrBlank()) {
            onTranscriptReady(state.transcript!!)
            viewModel.reset()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { viewModel.reset(); onNavigateBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                title = { Text("Transcribe Audio") },
            )
        }
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {

            // ── Timer ──────────────────────────────────────────────────────────
            Text(
                text = viewModel.repository.formatElapsed(state.elapsedSeconds),
                style = MaterialTheme.typography.displayMedium,
                color = when (state.status) {
                    RecordingStatus.RECORDING    -> MaterialTheme.colorScheme.error
                    RecordingStatus.TRANSCRIBING -> MaterialTheme.colorScheme.primary
                    else                         -> MaterialTheme.colorScheme.onSurface
                }
            )

            // ── Status label ──────────────────────────────────────────────────
            val statusText = when (state.status) {
                RecordingStatus.IDLE         ->
                    if (state.outputFile != null) "Recording saved"
                    else "Tap to start recording"
                RecordingStatus.RECORDING    -> "Recording…"
                RecordingStatus.TRANSCRIBING -> "Transcribing with AI…"
                RecordingStatus.DONE         -> "Transcript ready"
                RecordingStatus.ERROR        -> state.error ?: "Something went wrong"
            }
            Text(
                text  = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            // ── Transcribing spinner ───────────────────────────────────────────
            if (state.status == RecordingStatus.TRANSCRIBING) {
                CircularProgressIndicator()
            }

            // ── Big mic / stop button ─────────────────────────────────────────
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale by pulse.animateFloat(
                initialValue = 1f, targetValue = 1.08f,
                animationSpec = infiniteRepeatable(tween(800)),
                label = "scale",
            )
            val buttonScale = if (state.status == RecordingStatus.RECORDING) scale else 1f
            val showButton = state.status in listOf(RecordingStatus.IDLE, RecordingStatus.RECORDING)

            if (showButton) {
                FilledIconButton(
                    onClick = {
                        when (state.status) {
                            RecordingStatus.IDLE -> {
                                if (viewModel.hasMicPermission()) viewModel.startRecording()
                                else permissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                            }
                            // Stop → immediately kick off AI transcription
                            RecordingStatus.RECORDING -> viewModel.stopAndTranscribe()
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
                ) {
                    Icon(
                        imageVector = if (state.status == RecordingStatus.RECORDING)
                            Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (state.status == RecordingStatus.RECORDING)
                            "Stop and transcribe" else "Start recording",
                        modifier = Modifier.size(40.dp),
                    )
                }

                // Hint label beneath button
                val hint = if (state.status == RecordingStatus.RECORDING)
                    "Tap to stop and transcribe" else ""
                if (hint.isNotEmpty()) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            // ── Error recovery ─────────────────────────────────────────────────
            if (state.status == RecordingStatus.ERROR) {
                Button(onClick = { viewModel.reset() }) { Text("Try again") }
            }
        }
    }

    if (showPermissionRationale) {
        AlertDialog(
            onDismissRequest = { showPermissionRationale = false },
            title   = { Text("Microphone access needed") },
            text    = { Text("Vela needs microphone access to record audio for transcription.") },
            confirmButton = {
                TextButton(onClick = { showPermissionRationale = false }) { Text("OK") }
            }
        )
    }
}
