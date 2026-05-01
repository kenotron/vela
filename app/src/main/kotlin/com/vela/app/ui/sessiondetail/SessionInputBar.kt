package com.vela.app.ui.sessiondetail

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
    import androidx.compose.foundation.layout.imePadding
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.layout.size
    import androidx.compose.foundation.layout.width
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
    import androidx.compose.material3.Icon
    import androidx.compose.material3.IconButton
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
    import android.net.Uri

    /**
     * Session input bar — pinned at the bottom of SessionDetailScreen.
     *
     * Features:
     *  - Multiline text field (mono 14sp, expands up to 5 visible lines)
     *  - Attach image button (left of text field)
     *  - Voice button — mic when idle, pulsing stop when recording
     *  - Send button — enabled only when text is non-blank and not loading
     *  - Attachment thumbnail row above the bar when attachments exist
     *  - imePadding() so the bar stays above the keyboard
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
        attachments: List<Uri> = emptyList(),
        onRemoveAttachment: (Uri) -> Unit = {},
        isLoading: Boolean,
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

        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(VelaColors.SurfaceRaised)
                .imePadding(),
        ) {
            // ── Top hairline divider ──────────────────────────────────────────
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(VelaColors.StrokeHair),
            )

            // ── Attachment previews ───────────────────────────────────────────
            if (attachments.isNotEmpty()) {
                LazyRow(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    items(attachments) { uri ->
                        Box(modifier = Modifier.padding(end = 8.dp)) {
                            AsyncImage(
                                model             = uri,
                                contentDescription = "Attachment",
                                contentScale      = ContentScale.Crop,
                                modifier          = Modifier
                                    .size(56.dp)
                                    .clip(RoundedCornerShape(8.dp)),
                            )
                            // Remove (✕) button
                            IconButton(
                                onClick  = { onRemoveAttachment(uri) },
                                modifier = Modifier
                                    .size(20.dp)
                                    .align(Alignment.TopEnd)
                                    .background(VelaColors.SurfacePeak, CircleShape),
                            ) {
                                Icon(
                                    imageVector        = Icons.Default.Close,
                                    contentDescription = "Remove",
                                    tint               = VelaColors.TextSecondary,
                                    modifier           = Modifier.size(12.dp),
                                )
                            }
                        }
                    }
                }
            }

            // ── Input row ─────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                // Attach
                IconButton(onClick = onAttachImage) {
                    Icon(
                        imageVector        = Icons.Default.AttachFile,
                        contentDescription = "Attach image",
                        tint               = VelaColors.TextTertiary,
                    )
                }

                // Text field
                Surface(
                    shape    = RoundedCornerShape(18.dp),
                    color    = VelaColors.SurfacePeak,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 40.dp, max = 140.dp),
                ) {
                    Box(
                        modifier         = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (text.isEmpty()) {
                            Text(
                                text  = "Message…",
                                style = TextStyle(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize   = 14.sp,
                                    color      = VelaColors.TextTertiary,
                                ),
                            )
                        }
                        BasicTextField(
                            value            = text,
                            onValueChange    = onTextChange,
                            textStyle        = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize   = 14.sp,
                                color      = VelaColors.TextPrimary,
                            ),
                            cursorBrush      = SolidColor(VelaColors.Accent),
                            maxLines         = 6,
                            modifier         = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(Modifier.width(4.dp))

                // Voice button
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
                            tint               = VelaColors.Accent,
                        )
                    }
                }

                // Send button
                IconButton(
                    onClick  = onSend,
                    enabled  = text.isNotBlank() && !isLoading,
                ) {
                    Icon(
                        imageVector        = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint               = if (text.isNotBlank() && !isLoading)
                                                VelaColors.Accent else VelaColors.TextTertiary,
                    )
                }
            }
        }
    }
    