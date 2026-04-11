package com.vela.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

@Composable
fun VoiceButton(
    isListening: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val description = if (isListening) "Stop voice input" else "Start voice input"
    val containerColor = if (isListening) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }

    FloatingActionButton(
        onClick = onToggle,
        modifier = modifier.semantics { contentDescription = description },
        containerColor = containerColor,
    ) {
        if (isListening) {
            Icon(
                imageVector = Icons.Default.Stop,
                contentDescription = null,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Mic,
                contentDescription = null,
            )
        }
    }
}
