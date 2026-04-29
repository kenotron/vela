package com.vela.app.ui.connectors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vela.app.ssh.BootstrapStep
import com.vela.app.ui.nodes.BootstrapUiState

/**
 * Bottom-sheet content showing live amplifierd bootstrap progress.
 *
 * Hosted by ConnectorsScreen — it appears whenever
 * `bootstrapState.isBootstrapping || bootstrapState.isComplete`.
 */
@Composable
fun NodeBootstrapSheet(
    state: BootstrapUiState,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        color    = cs.surfaceContainerHigh,
    ) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Drag handle: 36×4dp, onSurfaceVariant
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(cs.onSurfaceVariant),
            )

            StepIndicatorRow(state)

            BootstrapLogArea(state.logLines)

            // Error state
            if (state.errorMessage != null) {
                Surface(
                    color    = cs.errorContainer,
                    shape    = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Bootstrap failed",
                            style      = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color      = cs.onErrorContainer,
                        )
                        Text(state.errorMessage, style = MaterialTheme.typography.bodySmall, color = cs.onErrorContainer)
                        OutlinedButton(
                            onClick  = onDismiss,
                            modifier = Modifier.align(Alignment.End),
                        ) { Text("Retry") }
                    }
                }
            }

            // Complete state
            if (state.isComplete) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF34C759))
                    Text("Done!", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                Button(
                    onClick  = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Close") }
            }
        }
    }
}

// ── Step indicator row ─────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepIndicatorRow(state: BootstrapUiState) {
    val cs = MaterialTheme.colorScheme
    FlowRow(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement   = Arrangement.spacedBy(8.dp),
    ) {
        BootstrapStep.entries.forEach { step ->
            val isCurrent   = state.currentStep == step && !state.completedSteps.contains(step)
            val isCompleted = state.completedSteps.contains(step)
            Row(
                modifier              = Modifier
                    .background(cs.surfaceContainer, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                when {
                    isCompleted -> Text("✓", color = Color(0xFF34C759), fontWeight = FontWeight.Bold)
                    isCurrent   -> CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    else        -> Text("○", color = cs.onSurfaceVariant.copy(alpha = 0.5f))
                }
                Text(
                    text  = stepLabel(step),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isCompleted || isCurrent) cs.onSurface
                            else cs.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

// ── Log area ───────────────────────────────────────────────────────────────

@Composable
private fun BootstrapLogArea(logLines: List<String>) {
    val cs = MaterialTheme.colorScheme
    val listState = rememberLazyListState()

    // Auto-scroll to the bottom whenever a new line arrives
    LaunchedEffect(logLines.size) {
        if (logLines.isNotEmpty()) {
            listState.animateScrollToItem(logLines.size - 1)
        }
    }

    LazyColumn(
        state    = listState,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp)
            .background(cs.surfaceContainer, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(logLines) { line ->
            Text(
                text  = line,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurface,
            )
        }
    }
}

// ── Step label ─────────────────────────────────────────────────────────────

private fun stepLabel(step: BootstrapStep): String = when (step) {
    BootstrapStep.CONNECT             -> "Connect"
    BootstrapStep.DETECT              -> "Detect"
    BootstrapStep.INSTALL_UV          -> "Install uv"
    BootstrapStep.INSTALL_AMPLIFIERD  -> "Install amplifierd"
    BootstrapStep.WRITE_CONFIG        -> "Config"
    BootstrapStep.INSTALL_SERVICE     -> "Service"
    BootstrapStep.HEALTH_CHECK        -> "Health check"
    BootstrapStep.PROMOTE             -> "Promote"
}
