package com.vela.app.ui.voice

    import androidx.compose.animation.AnimatedContent
    import androidx.compose.animation.core.FastOutSlowInEasing
    import androidx.compose.animation.core.LinearEasing
    import androidx.compose.animation.core.RepeatMode
    import androidx.compose.animation.core.animateFloat
    import androidx.compose.animation.core.infiniteRepeatable
    import androidx.compose.animation.core.rememberInfiniteTransition
    import androidx.compose.animation.core.tween
    import androidx.compose.animation.fadeIn
    import androidx.compose.animation.fadeOut
    import androidx.compose.animation.togetherWith
    import androidx.compose.foundation.Canvas
    import androidx.compose.foundation.background
    import androidx.compose.foundation.border
    import androidx.compose.foundation.clickable
    import androidx.compose.foundation.layout.Arrangement
    import androidx.compose.foundation.layout.Box
    import androidx.compose.foundation.layout.Column
    import androidx.compose.foundation.layout.Row
    import androidx.compose.foundation.layout.Spacer
    import androidx.compose.foundation.layout.fillMaxHeight
    import androidx.compose.foundation.layout.fillMaxSize
    import androidx.compose.foundation.layout.fillMaxWidth
    import androidx.compose.foundation.layout.height
    import androidx.compose.foundation.layout.padding
    import androidx.compose.foundation.layout.size
    import androidx.compose.foundation.layout.width
    import androidx.compose.foundation.rememberScrollState
    import androidx.compose.foundation.shape.CircleShape
    import androidx.compose.foundation.shape.RoundedCornerShape
    import androidx.compose.foundation.verticalScroll
    import androidx.compose.material.icons.Icons
    import androidx.compose.material.icons.filled.Stop
    import androidx.compose.material3.Button
    import androidx.compose.material3.ButtonDefaults
    import androidx.compose.material3.Icon
    import androidx.compose.material3.MaterialTheme
    import androidx.compose.material3.OutlinedButton
    import androidx.compose.material3.Text
    import androidx.compose.runtime.Composable
    import androidx.compose.runtime.getValue
    import androidx.compose.ui.Alignment
    import androidx.compose.ui.Modifier
    import androidx.compose.ui.draw.clip
    import androidx.compose.ui.graphics.Color
    import androidx.compose.ui.graphics.drawscope.Stroke
    import androidx.compose.ui.text.font.FontWeight
    import androidx.compose.ui.text.style.TextAlign
    import androidx.compose.ui.unit.dp
    import androidx.compose.ui.unit.sp
    import androidx.compose.ui.window.Dialog
    import androidx.compose.ui.window.DialogProperties
    import com.vela.app.ui.theme.VelaColors
    import kotlin.math.PI
    import kotlin.math.sin

    /**
     * Full-screen voice capture overlay. Shown as a Dialog so it floats above all
     * current navigation content.
     *
     * @param phase        Current recording phase (RECORDING or REVIEW).
     * @param transcript   Live transcript text from the SpeechTranscriber.
     * @param elapsedMs    Elapsed recording time in milliseconds.
     * @param nodeName     Name of the destination node shown in the context pill.
     * @param onStop       Called when the user taps the Stop button (RECORDING phase).
     * @param onSend       Called with the transcript when the user taps Send (REVIEW phase).
     * @param onDiscard    Called when the user discards the recording.
     */
    @Composable
    fun VoiceCaptureOverlay(
        phase: VoiceOverlayViewModel.VoicePhase,
        transcript: String,
        elapsedMs: Long,
        nodeName: String,
        onStop: () -> Unit,
        onSend: (String) -> Unit,
        onDiscard: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Dialog(
            onDismissRequest = onDiscard,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress      = true,
                dismissOnClickOutside   = false,
            ),
        ) {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .background(VelaColors.Abyss.copy(alpha = 0.88f)),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedContent(
                    targetState   = phase,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(200)) },
                    label          = "voicePhase",
                ) { targetPhase ->
                    when (targetPhase) {
                        VoiceOverlayViewModel.VoicePhase.RECORDING ->
                            RecordingPhase(
                                transcript = transcript,
                                elapsedMs  = elapsedMs,
                                nodeName   = nodeName,
                                onStop     = onStop,
                            )
                        VoiceOverlayViewModel.VoicePhase.REVIEW ->
                            ReviewPhase(
                                transcript = transcript,
                                elapsedMs  = elapsedMs,
                                onSend     = onSend,
                                onDiscard  = onDiscard,
                            )
                    }
                }
            }
        }
    }

    // ── Recording phase ──────────────────────────────────────────────────────────────────

    @Composable
    private fun RecordingPhase(
        transcript: String,
        elapsedMs: Long,
        nodeName: String,
        onStop: () -> Unit,
    ) {
        val infiniteTransition = rememberInfiniteTransition(label = "recording")

        // Single wave-progress drives all 10 bars via sine math.
        val waveProgress by infiniteTransition.animateFloat(
            initialValue  = 0f,
            targetValue   = 1f,
            animationSpec = infiniteRepeatable(
                animation  = tween(durationMillis = 800, easing = LinearEasing),
            ),
            label = "waveProgress",
        )

        // Pulsing red dot for the timer — 1-second breathe.
        val dotAlpha by infiniteTransition.animateFloat(
            initialValue  = 0.4f,
            targetValue   = 1.0f,
            animationSpec = infiniteRepeatable(
                animation  = tween(durationMillis = 1_000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "timerDot",
        )

        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            // ── Node context tag (pill) ──────────────────────────────────────────────────
            // Only show the destination pill when a node is selected
            if (nodeName.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(VelaColors.SurfaceRaised)
                        .border(1.dp, VelaColors.StrokeEdge, RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment   = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(VelaColors.Accent, CircleShape),
                    )
                    Text(
                        text  = "→ $nodeName",
                        style = MaterialTheme.typography.labelSmall,
                        color = VelaColors.TextPrimary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Bloom circle + waveform bars + transcript ────────────────────────────────
            Box(contentAlignment = Alignment.Center) {
                Canvas(modifier = Modifier.size(340.dp)) {
                    drawCircle(
                        color  = VelaColors.Accent.copy(alpha = 0.6f),
                        radius = size.minDimension / 2f,
                        style  = Stroke(width = 1.5.dp.toPx()),
                    )
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier            = Modifier.padding(horizontal = 40.dp),
                ) {
                    // Waveform bars: 10 bars, 3dp wide, sine-driven height
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment     = Alignment.CenterVertically,
                        modifier              = Modifier.height(48.dp),
                    ) {
                        repeat(10) { i ->
                            val barPhase = (waveProgress + i / 10f) % 1f
                            val scale    = (sin(barPhase * 2.0 * PI).toFloat() * 0.35f + 0.65f)
                                .coerceIn(0.3f, 1.0f)
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .fillMaxHeight(scale)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(VelaColors.Accent.copy(alpha = 0.7f)),
                            )
                        }
                    }

                    if (transcript.isNotBlank()) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            text      = transcript,
                            style     = MaterialTheme.typography.displayMedium.copy(fontSize = 26.sp),
                            color     = VelaColors.TextPrimary,
                            textAlign = TextAlign.Center,
                            maxLines  = 4,
                        )
                    }
                }
            }

            Spacer(Modifier.weight(1f))

            // ── Hint ─────────────────────────────────────────────────────────────────────
            Text(
                text  = "Recording · Swipe to discard",
                style = MaterialTheme.typography.labelSmall,
                color = VelaColors.TextTertiary,
            )

            Spacer(Modifier.height(16.dp))

            // ── Timer row ────────────────────────────────────────────────────────────────
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .background(VelaColors.Error.copy(alpha = dotAlpha), CircleShape),
                )
                Text(
                    text  = VoiceOverlayViewModel.formatElapsedMs(elapsedMs),
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontFamily    = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize      = 22.sp,
                        letterSpacing = 3.sp,
                    ),
                    color = VelaColors.TextPrimary,
                )
            }

            Spacer(Modifier.height(32.dp))

            // ── Stop button (72dp circle, ErrorContainer fill) ───────────────────────────
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .background(VelaColors.ErrorContainer, CircleShape)
                    .border(1.5.dp, Color(0xFFFF6B6B).copy(alpha = 0.28f), CircleShape)
                    .clickable(onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector        = Icons.Default.Stop,
                    contentDescription = "Stop recording",
                    tint               = VelaColors.ErrorOnContainer,
                    modifier           = Modifier.size(28.dp),
                )
            }

            Spacer(Modifier.height(48.dp))
        }
    }

    // ── Review phase ─────────────────────────────────────────────────────────────────────

    @Composable
    private fun ReviewPhase(
        transcript: String,
        elapsedMs: Long,
        onSend: (String) -> Unit,
        onDiscard: () -> Unit,
    ) {
        Column(
            modifier            = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(56.dp))

            // Scrollable transcript (Instrument Serif 18sp for review scale)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text      = transcript,
                    style     = MaterialTheme.typography.displayMedium.copy(fontSize = 18.sp),
                    color     = VelaColors.TextPrimary,
                    textAlign = TextAlign.Start,
                )
            }

            Spacer(Modifier.height(12.dp))

            // Duration stamp
            Text(
                text  = "Recorded ${VoiceOverlayViewModel.formatElapsedMs(elapsedMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = VelaColors.TextTertiary,
            )

            Spacer(Modifier.height(24.dp))

            // ── Button row: Discard (left) + Send (right, full-width) ────────────────────
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick  = onDiscard,
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    shape  = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = VelaColors.Error,
                    ),
                ) {
                    Text(
                        text  = "DISCARD",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Button(
                    onClick  = { onSend(transcript) },
                    modifier = Modifier
                        .weight(2f)
                        .height(52.dp),
                    shape  = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = VelaColors.Accent,
                        contentColor   = VelaColors.Abyss,
                    ),
                ) {
                    Text(
                        text  = "SEND",
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
    