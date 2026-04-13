    package com.vela.app.ui.download

    import androidx.compose.foundation.layout.*
    import androidx.compose.material3.*
    import androidx.compose.runtime.*
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.text.style.TextAlign
    import androidx.compose.ui.unit.dp
    import com.vela.app.ai.llama.DownloadState

    /**
     * Full-screen composable shown on first launch when the local GGUF model hasn't been
     * downloaded yet.
     *
     * States:
     *   1. [ModelDownloadUiState.Prompt]      — Confirm / cancel dialog
     *   2. [ModelDownloadUiState.Downloading] — Progress bar + MB counter
     *   3. [ModelDownloadUiState.Error]       — Error message + retry button
     *
     * The parent (MainActivity / nav graph) handles [onDismiss] (model ready) and
     * [onCancel] (user declined — fall back to ML Kit or show unavailable state).
     */
    @Composable
    fun ModelDownloadScreen(
        uiState: ModelDownloadUiState,
        onConfirmDownload: () -> Unit,
        onCancel: () -> Unit,
        onRetry: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Surface(modifier = modifier.fillMaxSize()) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                when (uiState) {
                    is ModelDownloadUiState.Prompt -> DownloadPromptCard(
                        onConfirm = onConfirmDownload,
                        onCancel  = onCancel,
                    )
                    is ModelDownloadUiState.Downloading -> DownloadProgressCard(state = uiState)
                    is ModelDownloadUiState.Error -> DownloadErrorCard(
                        message = uiState.message,
                        onRetry = onRetry,
                        onCancel = onCancel,
                    )
                    is ModelDownloadUiState.Done -> {
                        // Parent dismisses this screen when Done is observed — nothing to show.
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadPromptCard(onConfirm: () -> Unit, onCancel: () -> Unit) {
        ElevatedCard(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("📦 Download AI Model", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Vela needs to download Gemma 4 E2B (~3.5 GB) to run AI locally on your device. " +
                           "This is a one-time download. Wi-Fi recommended.",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Not Now")
                    }
                    Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                        Text("Download")
                    }
                }
            }
        }
    }

    @Composable
    private fun DownloadProgressCard(state: ModelDownloadUiState.Downloading) {
        ElevatedCard(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Downloading model…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = { state.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "%.1f MB / %.1f MB".format(state.mbRead, state.mbTotal),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun DownloadErrorCard(message: String, onRetry: () -> Unit, onCancel: () -> Unit) {
        ElevatedCard(modifier = Modifier.padding(24.dp).fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("⚠️ Download Failed", style = MaterialTheme.typography.titleMedium)
                Text(message, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = onRetry, modifier = Modifier.weight(1f)) { Text("Retry") }
                }
            }
        }
    }

    /** UI state for [ModelDownloadScreen]. Matches [DownloadState] but is UI-layer only. */
    sealed class ModelDownloadUiState {
        object Prompt : ModelDownloadUiState()
        data class Downloading(val fraction: Float, val mbRead: Float, val mbTotal: Float) : ModelDownloadUiState()
        data class Error(val message: String) : ModelDownloadUiState()
        object Done : ModelDownloadUiState()
    }
    