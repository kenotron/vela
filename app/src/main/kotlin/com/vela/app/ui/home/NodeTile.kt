package com.vela.app.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vela.app.ssh.NodeConnectivity
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors

/**
 * Visual status for a node tile's stripe, dot, and chip.
 *
 * Running / Waiting / Done require session data (Phase 3+).
 * In Phase 2 every node resolves to [Idle].
 */
enum class NodeTileStatus { Running, Waiting, Done, Idle, Offline }

// ── Pure logic helpers ───────────────────────────────────────────────────────
// All functions are `internal` so they are accessible from the test source set.

/** Stripe and live-dot color for the given status. */
internal fun stripeColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.Running
    NodeTileStatus.Waiting -> VelaColors.Waiting
    NodeTileStatus.Done    -> VelaColors.Done
    NodeTileStatus.Idle    -> VelaColors.Accent
    NodeTileStatus.Offline -> VelaColors.TextTertiary
}

/** Stripe and live-dot opacity for the given status. */
internal fun stripeAlphaFor(status: NodeTileStatus): Float = when (status) {
    NodeTileStatus.Running -> 1f
    NodeTileStatus.Waiting -> 1f
    NodeTileStatus.Done    -> 0.5f
    NodeTileStatus.Idle    -> 0.4f
    NodeTileStatus.Offline -> 0.3f
}

/** Status-chip container fill for the given status. */
internal fun chipContainerColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.RunningContainer
    NodeTileStatus.Waiting -> VelaColors.WaitingContainer
    NodeTileStatus.Done    -> VelaColors.DoneContainer
    NodeTileStatus.Idle    -> VelaColors.SurfaceRaised
    NodeTileStatus.Offline -> VelaColors.SurfaceRaised
}

/** Status-chip text color for the given status. */
internal fun chipOnColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.RunningOnContainer
    NodeTileStatus.Waiting -> VelaColors.WaitingOnContainer
    NodeTileStatus.Done    -> VelaColors.DoneOnContainer
    NodeTileStatus.Idle    -> VelaColors.TextSecondary
    NodeTileStatus.Offline -> VelaColors.TextTertiary
}

/** All-caps chip label for the given status. */
internal fun chipLabelFor(status: NodeTileStatus): String = when (status) {
    NodeTileStatus.Running -> "RUNNING"
    NodeTileStatus.Waiting -> "WAITING"
    NodeTileStatus.Done    -> "DONE"
    NodeTileStatus.Idle    -> "IDLE"
    NodeTileStatus.Offline -> "OFFLINE"
}

/**
 * Derives tile status from node state and optional connectivity check.
 *
 * Running / Waiting / Done require session data (Phase 3+).
 * In Phase 2 AMPLIFIERD nodes resolve to [Idle] unless [connectivity] reports
 * [NodeConnectivity.Unreachable], in which case [Offline] is returned.
 */
internal fun nodeStatusFor(node: SshNode, connectivity: NodeConnectivity? = null): NodeTileStatus {
    if (node.type != NodeType.AMPLIFIERD) return NodeTileStatus.Idle
    return when (connectivity) {
        is NodeConnectivity.Unreachable -> NodeTileStatus.Offline
        is NodeConnectivity.Reachable   -> NodeTileStatus.Idle  // Phase 3: add Running/Waiting from session data
        is NodeConnectivity.Checking    -> NodeTileStatus.Idle
        is NodeConnectivity.Unknown, null -> NodeTileStatus.Idle
    }
}

/**
 * Single-line telemetry string shown below the node name on the tile.
 * Format: "type · host"
 */
internal fun telemetryLineFor(node: SshNode): String {
    val typeLabel = if (node.type == NodeType.AMPLIFIERD) "amplifierd" else "ssh"
    val hostLabel = node.primaryHost.ifBlank {
        node.url
            .removePrefix("http://")
            .removePrefix("https://")
            .substringBefore("/")
    }
    return if (hostLabel.isNotBlank()) "$typeLabel · $hostLabel" else typeLabel
}

// ── Composable ───────────────────────────────────────────────────────────────

/**
 * Node tile — the primary cell of the Home screen.
 *
 * Design spec: DESIGN.md §7.1
 * - Container: SurfaceSub, 28dp radius, full-width minus 16dp gutters, min 120dp height
 * - Leading 4dp status stripe, clipped to the card's rounded-left corners
 * - Node name: headlineLarge (Instrument Serif 28sp)
 * - Telemetry: bodyMedium (Inter 14sp, TextSecondary)
 * - Status chip: 8dp radius, 28dp height, labelSmall
 * - Live dot: 8dp circle, bottom-right, breathing when running
 * - Running glow: radial amber gradient drawn behind the card via drawBehind
 * - Running surface tint: Color(0xFF151209) — SurfaceSub tinted toward RunningContainer
 */
@Composable
fun NodeTileItem(
    node: SshNode,
    onClick: () -> Unit,
    connectivity: NodeConnectivity? = null,
    modifier: Modifier = Modifier,
) {
    val status      = nodeStatusFor(node, connectivity)
    val stripeColor = stripeColorFor(status)
    val stripeAlpha = stripeAlphaFor(status)
    val isRunning   = status == NodeTileStatus.Running

    // Breathing animation — live dot scales 1.0 → 1.15 over 2.4s, ease-in-out, when running
    val infiniteTransition = rememberInfiniteTransition(label = "nodeTileLiveDot")
    val liveDotScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue  = if (isRunning) 1.15f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "liveDotScale",
    )

    // Amber glow behind the card when running (drawBehind renders within layout bounds)
    val glowModifier: Modifier = if (isRunning) {
        Modifier.drawBehind {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFF5A524).copy(alpha = 0.18f),
                        Color.Transparent,
                    ),
                    radius = size.maxDimension * 0.75f,
                    center = center,
                ),
                radius = size.maxDimension * 0.75f,
            )
        }
    } else Modifier

    Surface(
        onClick   = onClick,
        modifier  = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 120.dp)
            .then(glowModifier),
        shape     = RoundedCornerShape(28.dp),
        color     = if (isRunning) Color(0xFF151209) else VelaColors.SurfaceSub,
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            // ── Leading 4dp status stripe ─────────────────────────────────────
            // Clipped to follow the card's top-left and bottom-left rounded corners.
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .matchParentSize()
                    .clip(RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp))
                    .background(stripeColor.copy(alpha = stripeAlpha)),
            )

            // ── Main content ──────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            ) {
                // Node name — Instrument Serif 28sp (headlineLarge)
                Text(
                    text  = node.label,
                    style = MaterialTheme.typography.headlineLarge,
                    color = VelaColors.TextPrimary,
                )
                Spacer(modifier = Modifier.height(8.dp))
                // Telemetry — Inter 14sp (bodyMedium)
                Text(
                    text  = telemetryLineFor(node),
                    style = MaterialTheme.typography.bodyMedium,
                    color = VelaColors.TextSecondary,
                )
                Spacer(modifier = Modifier.height(12.dp))
                // Status chip — 8dp radius, 28dp height, labelSmall (Inter 11sp/Bold)
                Box(
                    modifier = Modifier
                        .background(
                            color = chipContainerColorFor(status),
                            shape = RoundedCornerShape(8.dp),
                        )
                        .height(28.dp)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text  = chipLabelFor(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = chipOnColorFor(status),
                    )
                }
            }

            // ── Live dot — bottom-right, 8dp, breathing when running ──────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .size((8f * liveDotScale).dp)
                    .background(
                        color = stripeColor.copy(alpha = stripeAlpha),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
