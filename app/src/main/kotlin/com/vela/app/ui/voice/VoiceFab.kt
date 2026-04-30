package com.vela.app.ui.voice

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vela.app.ui.theme.VelaColors

/**
 * Persistent Voice FAB. 64dp diameter layered circle: outer glow halo →
 * 1.5dp ring → solid disc → mic icon. Manages overlay visibility internally.
 *
 * @param voiceVm         Hilt-injected ViewModel shared with the overlay.
 * @param isSessionRunning True when any session on the current node is RUNNING.
 *                         Shifts the FAB from cyan-idle to amber-breathing.
 * @param nodeName        Forwarded to VoiceCaptureOverlay's context pill.
 */
@Composable
fun VoiceFab(
    voiceVm: VoiceOverlayViewModel,
    isSessionRunning: Boolean,
    nodeName: String = "",
    modifier: Modifier = Modifier,
) {
    val phase      by voiceVm.phase.collectAsState()
    val transcript by voiceVm.transcript.collectAsState()
    val elapsedMs  by voiceVm.elapsedMs.collectAsState()

    var showOverlay by remember { mutableStateOf(false) }

    // Breathing halo animation — idle state keeps same value so produces no visible motion.
    val infiniteTransition = rememberInfiniteTransition(label = "fabHalo")
    val haloAlpha by infiniteTransition.animateFloat(
        initialValue  = if (isSessionRunning) 0.14f else 0.18f,
        targetValue   = if (isSessionRunning) 0.32f else 0.18f,
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = if (isSessionRunning) 1_200 else 1_000,
                easing         = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "haloAlpha",
    )

    val ringColor = if (isSessionRunning) VelaColors.Running else VelaColors.Accent
    val discColor = if (isSessionRunning) VelaColors.Running else VelaColors.SurfacePeak
    val iconTint  = if (isSessionRunning) Color(0xFF1A1000)  else VelaColors.Accent

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Outer glow halo (drawn behind the FAB circle).
        Canvas(modifier = Modifier.size(100.dp)) {
            drawCircle(color = ringColor.copy(alpha = haloAlpha))
        }

        // FAB disc with ring border and mic icon.
        Box(
            modifier = Modifier
                .size(64.dp)
                .background(discColor, CircleShape)
                .border(1.5.dp, ringColor, CircleShape)
                .clickable {
                    showOverlay = true
                    voiceVm.startRecording()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector        = Icons.Default.Mic,
                contentDescription = "Open voice input",
                tint               = iconTint,
                modifier           = Modifier.size(26.dp),
            )
        }
    }

    if (showOverlay) {
        VoiceCaptureOverlay(
            phase      = phase,
            transcript = transcript,
            elapsedMs  = elapsedMs,
            nodeName   = nodeName,
            onStop     = { voiceVm.stopRecording() },
            onSend     = {
                // Reset state and close overlay — backend dispatch handled in a later phase.
                voiceVm.discard()
                showOverlay = false
            },
            onDiscard  = {
                voiceVm.discard()
                showOverlay = false
            },
        )
    }
}
