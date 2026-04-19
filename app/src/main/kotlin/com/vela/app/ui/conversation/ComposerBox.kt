package com.vela.app.ui.conversation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vela.app.voice.SpeechTranscriber

internal data class AttachmentItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: android.net.Uri,
    val displayName: String,
    val mimeType: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onRecord: () -> Unit,
    speechTranscriber: SpeechTranscriber?,
    isListening: Boolean,
    onMicClick: () -> Unit,
    attachments: List<AttachmentItem>,
    onRemoveAttachment: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
) {
    var showAttachmentSheet by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {

            // ── Row 1: mic + text input ───────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                // Text field — no outline, outer Surface provides the container shape
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .defaultMinSize(minHeight = 48.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text  = "Type a message\u2026",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    }
                    BasicTextField(
                        value         = value,
                        onValueChange = onValueChange,
                        modifier      = Modifier.fillMaxWidth(),
                        textStyle     = MaterialTheme.typography.bodyLarge.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        maxLines      = 6,
                        cursorBrush   = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions.Default,
                        keyboardActions = KeyboardActions.Default,
                    )
                }
            }

            // ── Attachment chip row (between text input and action bar) ──────
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(attachments, key = { it.id }) { attachment ->
                        AttachmentChip(
                            attachment = attachment,
                            onRemove   = { onRemoveAttachment(attachment.id) },
                        )
                    }
                }
            }

            // ── Row 2: action bar ────────────────────────────────────────────
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
            ) {
                // [+] Attachment — opens AttachmentSheet
                IconButton(
                    onClick  = { showAttachmentSheet = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        Icons.Default.Add,
                        contentDescription = "Attach",
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }

                // Spacer — was vault chip
                Box(Modifier.weight(1f))

                // [→ send] — animates in/out based on whether there is text
                AnimatedVisibility(
                    visible = value.isNotBlank() || attachments.isNotEmpty(),
                    enter   = fadeIn() + scaleIn(initialScale = 0.8f),
                    exit    = fadeOut() + scaleOut(targetScale = 0.8f),
                ) {
                    IconButton(
                        onClick  = { if (value.isNotBlank() || attachments.isNotEmpty()) onSend() },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint     = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // Mic button — voice to text — rightmost in action bar
                IconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size(36.dp),
                    enabled = speechTranscriber != null,
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Start voice input",
                        tint = when {
                            isListening -> MaterialTheme.colorScheme.error
                            speechTranscriber == null -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }

    // Attachment sheet
    if (showAttachmentSheet) {
        AttachmentSheet(
            onDismiss   = { showAttachmentSheet = false },
            onRecord    = { showAttachmentSheet = false; onRecord() },
            onPickPhoto = { showAttachmentSheet = false; onPickPhoto() },
            onPickFile  = { showAttachmentSheet = false; onPickFile() },
            onCamera    = { showAttachmentSheet = false; onCamera() },
        )
    }
}

// ---- AttachmentSheet --------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AttachmentSheet(
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Attach",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            // ── Featured: Record Transcription ──────────────────────────────
            Card(
                onClick = onRecord,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
                shape = RoundedCornerShape(16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(32.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Record & Transcribe",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Text(
                            "AI transcribes your voice \u2014 attached to this message",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        )
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f),
                    )
                }
            }

            // ── Standard options: Camera · Photos · Files ───────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AttachOption(
                    icon = Icons.Default.CameraAlt,
                    label = "Camera",
                    onClick = { onCamera(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                AttachOption(
                    icon = Icons.Default.PhotoLibrary,
                    label = "Photos",
                    onClick = { onPickPhoto(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
                AttachOption(
                    icon = Icons.Default.InsertDriveFile,
                    label = "Files",
                    onClick = { onPickFile(); onDismiss() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun AttachOption(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(24.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
internal fun AttachmentChip(attachment: AttachmentItem, onRemove: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 64.dp, height = 56.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = if (attachment.mimeType.startsWith("image"))
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Column(
                modifier = Modifier.padding(4.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    imageVector = if (attachment.mimeType.startsWith("image"))
                        Icons.Default.Image
                    else
                        Icons.Default.InsertDriveFile,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    attachment.displayName.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .size(20.dp)
                .align(Alignment.TopEnd),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier.size(14.dp),
            )
        }
    }
}
