package com.vela.app.ui.sessionlist

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.SessionSummary
import com.vela.app.ui.theme.VelaColors
import java.util.Locale

// ── Pure color-mapping logic ──────────────────────────────────────────────────
// All functions are `internal` so the test source set can access them.

/**
 * M3 tonal fill for the session card surface.
 *
 * Each value is a hardcoded approximation of:
 *   color-mix(StatusContainer × weight%, SurfaceSub)
 * per DESIGN.md §7.2.
 */
internal fun cardBackgroundFor(status: SessionStatus): Color = when (status) {
    SessionStatus.EXECUTING -> Color(0xFF1C1A0E)
    SessionStatus.RESUMING  -> Color(0xFF1A1234)
    SessionStatus.IDLE      -> VelaColors.SurfaceSub
    SessionStatus.ERROR     -> Color(0xFF1C1117)
}

/** Status chip background — status container color per DESIGN.md §7.2. */
internal fun chipContainerFor(status: SessionStatus): Color = when (status) {
    SessionStatus.EXECUTING -> VelaColors.RunningContainer
    SessionStatus.RESUMING  -> VelaColors.WaitingContainer
    SessionStatus.IDLE      -> VelaColors.DoneContainer
    SessionStatus.ERROR     -> VelaColors.ErrorContainer
}

/** Status chip text — on-container color per DESIGN.md §7.2. */
internal fun chipOnContainerFor(status: SessionStatus): Color = when (status) {
    SessionStatus.EXECUTING -> VelaColors.RunningOnContainer
    SessionStatus.RESUMING  -> VelaColors.WaitingOnContainer
    SessionStatus.IDLE      -> VelaColors.DoneOnContainer
    SessionStatus.ERROR     -> VelaColors.ErrorOnContainer
}

// ── Composable ────────────────────────────────────────────────────────────────

/**
 * Session card for Screen 3 — Project Detail (Sessions List).
 *
 * Design: DESIGN.md §7.2
 * - M3 tonal fill background (NO left border stripe)
 * - 8dp radius status chip (NOT pill)
 * - EXECUTING: amber glow via drawBehind + 14dp thin spinner
 * - RESUMING: 1dp violet outline around card + '↻ Resuming…' affordance
 * - IDLE/ERROR: no animation
 */
@Composable
fun SessionCard(
    session: SessionSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRunning  = session.status == SessionStatus.EXECUTING
    val isResuming = session.status == SessionStatus.RESUMING

    // Running glow animation
    val infiniteTransition = rememberInfiniteTransition(label = "sessionCardGlow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.12f,
        targetValue  = 0.22f,
        animationSpec = infiniteRepeatable(
            animation  = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glowAlpha",
    )

    // Running spinner rotation
    val spinnerRotation by infiniteTransition.animateFloat(
        initialValue  = 0f,
        targetValue   = 360f,
        animationSpec = infiniteRepeatable(
            animation  = tween(1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spinnerRotation",
    )

    val borderStroke = if (isResuming) {
        BorderStroke(1.dp, VelaColors.Waiting)
    } else {
        null
    }

    // Priority: hooks-session-naming title → first user message preview → formatted date
    val displayTitle = when {
        session.title.isNotBlank() && !session.title.matches(Regex("[0-9a-f-]{8,}")) -> session.title
        session.preview.isNotBlank()                                                  -> session.preview
        else                                                                          -> formatSessionDate(session.lastActiveMs)
    }

    Surface(
        onClick  = onClick,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (isRunning) Modifier.drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                VelaColors.Running.copy(alpha = glowAlpha),
                                Color.Transparent,
                            ),
                            radius = size.maxDimension * 0.75f,
                        ),
                    )
                } else Modifier
            ),
        shape  = RoundedCornerShape(20.dp),
        color  = cardBackgroundFor(session.status),
        border = borderStroke,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // ── Title row ──────────────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = displayTitle,
                    style    = MaterialTheme.typography.titleLarge,
                    color    = VelaColors.TextPrimary,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                if (isRunning) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier    = Modifier.size(14.dp).rotate(spinnerRotation),
                        color       = VelaColors.Running,
                        strokeWidth = 1.5.dp,
                        progress    = { 0.25f },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            // ── Preview: first user message (what session is about) ───────────
            if (session.preview.isNotBlank() && session.preview != displayTitle) {
                Text(
                    text     = session.preview,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = VelaColors.TextSecondary,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
            }

            // ── Executing: last user message = what AI is currently working on ─
            if (isRunning && session.lastUserMessage.isNotBlank()
                && session.lastUserMessage != session.preview) {
                Text(
                    text     = "↳ ${session.lastUserMessage}",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = VelaColors.Running,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
            }

            // ── Date subtitle ─────────────────────────────────────────────────
            Text(
                text  = formatSessionDate(session.lastActiveMs),
                style = MaterialTheme.typography.bodySmall,
                color = VelaColors.TextTertiary,
            )

            Spacer(Modifier.height(10.dp))

            // ── Status chip row ───────────────────────────────────────────────
            Row(
                modifier          = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Status chip: 8dp radius, 28dp height, Inter 11sp 600
                Surface(
                    shape    = RoundedCornerShape(8.dp),
                    color    = chipContainerFor(session.status),
                    modifier = Modifier.height(28.dp),
                ) {
                    Box(
                        modifier         = Modifier.padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text  = session.status.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = chipOnContainerFor(session.status),
                        )
                    }
                }
            }

            // ── Resuming: '↻ Resuming…' affordance ───────────────────────────
            if (isResuming) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = "↻ Resuming…",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 11.5.sp),
                    color = VelaColors.Waiting,
                )
            }
        }
    }
}

/**
 * Format epoch millis as a human-readable relative date string.
 * e.g. "Just now", "5m ago", "Today at 3:22 PM", "Yesterday at 10:00 AM", "Apr 28"
 */
internal fun formatSessionDate(epochMs: Long): String {
    if (epochMs == 0L) return "Unknown"
    val now = System.currentTimeMillis()
    val diff = now - epochMs
    return when {
        diff < 60_000L      -> "Just now"
        diff < 3_600_000L   -> "${diff / 60_000}m ago"
        diff < 86_400_000L  -> {
            val sdf = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
            "Today at ${sdf.format(java.util.Date(epochMs))}"
        }
        diff < 172_800_000L -> {
            val sdf = java.text.SimpleDateFormat("h:mm a", Locale.getDefault())
            "Yesterday at ${sdf.format(java.util.Date(epochMs))}"
        }
        else -> {
            val sdf = java.text.SimpleDateFormat("MMM d", Locale.getDefault())
            sdf.format(java.util.Date(epochMs))
        }
    }
}
