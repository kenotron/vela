package com.vela.app.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vela.app.ai.DownloadState

@Composable
fun ModelLoadingScreen(
    downloadState: DownloadState,
    onDownloadClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (downloadState) {
            is DownloadState.NotDownloaded -> {
                Text(
                    text = "Vela needs an AI model to work",
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    text = "The model is approximately 600MB and will be downloaded once.",
                    modifier = Modifier.padding(top = 8.dp),
                )
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .semantics { contentDescription = "Download AI model" },
                ) {
                    Text("Download")
                }
            }

            is DownloadState.Downloading -> {
                CircularProgressIndicator(
                    progress = { downloadState.percent / 100f },
                    modifier = Modifier.semantics {
                        contentDescription = "Model download progress"
                    },
                )
                Text(
                    text = "Downloading model... ${downloadState.percent}%",
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            is DownloadState.Downloaded -> {
                Text(text = "Model ready")
            }

            is DownloadState.Error -> {
                Text(
                    text = "Download failed",
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = downloadState.cause,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .semantics { contentDescription = "Download AI model" },
                ) {
                    Text("Retry")
                }
            }
        }
    }
}
