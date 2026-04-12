package com.vela.app.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.vela.app.ai.ReadinessState

@Composable
fun ModelLoadingScreen(
    state: ReadinessState,
    onDownloadClick: () -> Unit = {},
) {
    when (state) {
        ReadinessState.Available -> {
            // Nothing to show — the conversation screen takes over.
        }

        ReadinessState.Downloadable -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Button(
                    onClick = onDownloadClick,
                    modifier = Modifier.semantics { contentDescription = "Download Gemma 4 model" },
                ) {
                    Text("Download Gemma 4 AI Model")
                }
            }
        }

        is ReadinessState.Downloading -> {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val indicatorModifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp)
                    .semantics { contentDescription = "Gemma 4 downloading" }

                if (state.progress != null) {
                    LinearProgressIndicator(
                        progress = { state.progress },
                        modifier = indicatorModifier,
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = indicatorModifier,
                    )
                }
            }
        }

        ReadinessState.Unavailable -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Gemma 4 requires a compatible device with Android AICore support. Your device is not supported.",
                    modifier = Modifier.semantics {
                        contentDescription = "Device not supported for Gemma 4"
                    },
                )
            }
        }
    }
}
