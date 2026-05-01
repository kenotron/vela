package com.vela.app.ui.sessiondetail

import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vela.app.ui.theme.VelaColors

/**
 * Session input bar — Claude.ai-style card design.
 *
 * A single [Surface] card containing:
 *  - [BasicTextField] text area, min 3 lines (~80dp), expands to ~8 lines (~200dp)
 *  - Optional attachment thumbnail [LazyRow] inside the card (between text and action row)
 *  - Thin hairline divider separating text area from action row
 *  - Action row: attach icon button + optional mic (if [hasOpenAiKey]) + filled send button
 *
 * The send button is tinted [VelaColors.Accent] when enabled, and dimmed (alpha 0.4f) when blank.
 */
@Composable
fun SessionInputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onVoiceStart: () -> Unit,
    onVoiceStop: () -> Unit,
    isRecording: Boolean,
    onAttachImage: () -> Unit,
    isLoading: Boolean,
    hasOpenAiKey: Boolean,
    attachments: List<Uri> = emptyList(),
    onRemoveAttachment: (Uri) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "recPulse")
    val recordAlpha by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 0.3f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "recPulse",
    )

    val sendEnabled = text.isNotBlank() && !isLoading

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        shape  = RoundedCornerShape(20.dp),
        color  = VelaColors.SurfaceRaised,
        border = androidx.compose.foundation.BorderStroke(1.dp, VelaColors.StrokeEdge),
    ) {
        Column {
            // ── Text area ──────────────────────────────────────────────────────
            Box(
                modifier         = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp, max = 200.dp)
                    .padding(top = 12.dp, start = 12.dp, end = 12.dp, bottom = 8.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                if (text.isEmpty()) {
                    Text(
                        text  = "Type a message\u2026",
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize   = 14.sp,
                            color      = VelaColors.TextTertiary,
                        ),
                    )
                }
                BasicTextField(
                    value         = text,
                    onValueChange = onTextChange,
                    textStyle     = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize   = 14.sp,
                        color      = VelaColors.TextPrimary,
                    ),
                    cursorBrush   = SolidColor(VelaColors.Accent),
                    modifier      = Modifier.fillMaxWidth(),
                )
            }

            // ── Attachment thumbnails (inside the card) ────────────────────────
            if (attachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp),
                ) {
                    items(attachments) { uri ->
                        Box(modifier = Modifier.padding(end = 8.dp)) {
                            AsyncImage(
                                model              = uri,
                                contentDescription = "Attachment",
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(6.dp)),
                            )
                            IconButton(
                                onClick  = { onRemoveAttachment(uri) },
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(Alignment.TopEnd)
                                    .background(VelaColors.SurfacePeak, CircleShape),
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.Close,
                                    contentDescription = "Remove attachment",
                                    tint               = VelaColors.TextSecondary,
                                    modifier           = Modifier.size(10.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── Hairline divider between text area and action row ──────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(VelaColors.StrokeHair),
            )

            // ── Action row ─────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Attach image button
                IconButton(onClick = onAttachImage) {
                    Icon(
                        imageVector        = Icons.Default.AttachFile,
                        contentDescription = "Attach image",
                        tint               = VelaColors.TextTertiary,
                    )
                }

                // Mic button — only shown when OpenAI key is configured
                if (hasOpenAiKey) {
                    IconButton(
                        onClick = { if (isRecording) onVoiceStop() else onVoiceStart() },
                    ) {
                        if (isRecording) {
                            Icon(
                                imageVector        = Icons.Default.Stop,
                                contentDescription = "Stop recording",
                                tint               = VelaColors.Error.copy(alpha = recordAlpha),
                            )
                        } else {
                            Icon(
                                imageVector        = Icons.Default.Mic,
                                contentDescription = "Start recording",
                                tint               = VelaColors.TextTertiary,
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // Send button — filled tonal, Accent container, dimmed when disabled
                FilledTonalIconButton(
                    onClick  = onSend,
                    enabled  = sendEnabled,
                    colors   = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor         = VelaColors.Accent,
                        contentColor           = VelaColors.Abyss,
                        disabledContainerColor = VelaColors.Accent.copy(alpha = 0.4f),
                        disabledContentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        modifier           = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
