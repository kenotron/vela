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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vela.app.ssh.NodeConnectivity
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors

/**
 * Visual status for a node tile.
 *
 * State is expressed through the entire card background — no border or stripe.
 * Each value maps to a distinct background color in [cardBackgroundFor].
 *
 * Busy is the node-level equivalent of "has active sessions" (Phase 3+).
 * SettingUp covers both initial bootstrap and amplifierd update in progress.
 */
enum class NodeTileStatus { Offline, Checking, SettingUp, Ready, Busy }

// ── Pure logic helpers ───────────────────────────────────────────────────────
// All functions are `internal` so they are accessible from the test source set.

/** Full card background color — the primary state signal. */
internal fun cardBackgroundFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Busy       -> Color(0xFF151209)  // amber-tinted
    NodeTileStatus.Ready      -> Color(0xFF0C1C1A)  // teal-tinted
    NodeTileStatus.SettingUp  -> Color(0xFF131527)  // warm indigo
    NodeTileStatus.Checking   -> Color(0xFF0F1019)  // neutral dark
    NodeTileStatus.Offline    -> Color(0xFF0C0C10)  // near-black, no chroma
}

/** Live-dot color for the given status. */
internal fun dotColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Busy       -> VelaColors.Running          // amber #F5A524
    NodeTileStatus.Ready      -> VelaColors.Accent           // teal  #5EEAD4
    NodeTileStatus.SettingUp  -> Color(0xFF7A7EAA)           // muted indigo
    NodeTileStatus.Checking   -> Color(0xFF5A5E72)           // neutral gray
    NodeTileStatus.Offline    -> Color(0xFF32333E)           // very dim
}

/** Live-dot base opacity for the given status. */
internal fun dotAlphaFor(status: NodeTileStatus): Float = when (status) {
    NodeTileStatus.Busy       -> 0.9f
    NodeTileStatus.Ready      -> 0.7f
    NodeTileStatus.SettingUp  -> 0.35f
    NodeTileStatus.Checking   -> 0.30f
    NodeTileStatus.Offline    -> 0.50f
}

/** Status-chip container fill for the given status. */
internal fun chipContainerColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Busy       -> VelaColors.RunningContainer  // #3A2400
    NodeTileStatus.Ready      -> Color(0xFF0E2724)
    NodeTileStatus.SettingUp  -> Color(0xFF1A1C32)
    NodeTileStatus.Checking   -> Color(0xFF13141D)
    NodeTileStatus.Offline    -> Color(0xFF0F0F13)
}

/** Status-chip text color for the given status. */
internal fun chipOnColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Busy       -> VelaColors.RunningOnContainer  // #FFD89B
    NodeTileStatus.Ready      -> VelaColors.Accent               // #5EEAD4
    NodeTileStatus.SettingUp  -> Color(0xFF6A6E88)
    NodeTileStatus.Checking   -> Color(0xFF40425A)
    NodeTileStatus.Offline    -> Color(0xFF2A2C38)
}

/** All-caps chip label for the given status. */
internal fun chipLabelFor(status: NodeTileStatus): String = when (status) {
    NodeTileStatus.Busy       -> "BUSY"
    NodeTileStatus.Ready      -> "READY"
    NodeTileStatus.SettingUp  -> "SETTING UP"
    NodeTileStatus.Checking   -> "CHECKING"
    NodeTileStatus.Offline    -> "OFFLINE"
}

/**
 * Derives tile status from node type and live connectivity.
 *
 * SSH nodes (not yet promoted to amplifierd) always resolve to [SettingUp] —
 * they may be mid-bootstrap or mid-update.
 *
 * AMPLIFIERD nodes follow connectivity:
 *   null / Unknown / Checking → [Checking]  (probe in flight or not yet started)
 *   Reachable                 → [Ready]      (Phase 3: promote to Busy when sessions active)
 *   Unreachable               → [Offline]
 */
internal fun nodeStatusFor(node: SshNode, connectivity: NodeConnectivity? = null): NodeTileStatus {
    if (node.type != NodeType.AMPLIFIERD) return NodeTileStatus.SettingUp
    return when (connectivity) {
        is NodeConnectivity.Reachable   -> NodeTileStatus.Ready
        is NodeConnectivity.Unreachable -> NodeTileStatus.Offline
        else                             -> NodeTileStatus.Checking  // null, Unknown, Checking
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
 * - Container: full-card background = state color, 28dp radius, min 120dp height
 * - No stripe — state is expressed by the entire card background (M3 Expressive)
 * - Node name: headlineLarge (Instrument Serif 28sp)
 * - Telemetry: bodyMedium (Inter 14sp, TextSecondary)
 * - Status chip: 8dp radius, 28dp height, labelSmall
 * - Live dot: 8dp circle, bottom-right
 *   - Busy: breathing scale 1.0→1.2 over 2.4s
 *   - Checking: opacity pulse 0.30→0.80 over 2.2s
 *   - SettingUp: opacity pulse 0.30→0.70 over 3.6s (slower — patient)
 *   - Ready / Offline: static
 * - Busy glow: radial amber gradient drawn behind the card via drawBehind
 */
@Composable
fun NodeTileItem(
    node: SshNode,
    onClick: () -> Unit,
    connectivity: NodeConnectivity? = null,
    modifier: Modifier = Modifier,
) {
    val status = nodeStatusFor(node, connectivity)
    val isBusy = status == NodeTileStatus.Busy

    // Amber glow behind the card when busy — same as previous Running glow
    val glowModifier: Modifier = if (isBusy) {
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

    // Dot animations — only active for Busy, Checking, SettingUp
    val infiniteTransition = rememberInfiniteTransition(label = "nodeTileDot")

    // Scale: Busy breathes (1.0→1.2), others stay at 1.0
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue  = if (isBusy) 1.2f else 1.0f,
        animationSpec = infiniteRepeatable(
            animation  = tween(durationMillis = 2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotScale",
    )

    // Alpha: Checking and SettingUp pulse opacity; others are static
    val dotAnimatedAlpha by infiniteTransition.animateFloat(
        initialValue = dotAlphaFor(status),
        targetValue  = when (status) {
            NodeTileStatus.Checking  -> 0.80f
            NodeTileStatus.SettingUp -> 0.70f
            else                      -> dotAlphaFor(status)  // static
        },
        animationSpec = infiniteRepeatable(
            animation  = tween(
                durationMillis = when (status) {
                    NodeTileStatus.Checking  -> 2200
                    NodeTileStatus.SettingUp -> 3600
                    else                      -> 2400
                },
                easing = FastOutSlowInEasing,
            ),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dotAlpha",
    )

    Surface(
        onClick   = onClick,
        modifier  = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 120.dp)
            .then(glowModifier),
        shape     = RoundedCornerShape(28.dp),
        color     = cardBackgroundFor(status),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {

            // ── Main content ────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp),
            ) {
                Text(
                    text  = node.label,
                    style = MaterialTheme.typography.headlineLarge,
                    color = when (status) {
                        NodeTileStatus.Offline   -> Color(0xFF32333E)
                        NodeTileStatus.Checking  -> Color(0xFF5A5E72)
                        NodeTileStatus.SettingUp -> Color(0xFF8A8EAA)
                        else                      -> VelaColors.TextPrimary
                    },
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text  = telemetryLineFor(node),
                    style = MaterialTheme.typography.bodyMedium,
                    color = when (status) {
                        NodeTileStatus.Offline   -> Color(0xFF202230)
                        NodeTileStatus.Checking  -> Color(0xFF38394A)
                        NodeTileStatus.SettingUp -> Color(0xFF4E5168)
                        NodeTileStatus.Ready     -> Color(0xFF8ABFBA)
                        else                      -> VelaColors.TextSecondary
                    },
                )
                Spacer(modifier = Modifier.height(12.dp))
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

            // ── Live dot — bottom-right ──────────────────────────────────────
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(20.dp)
                    .size((8f * dotScale).dp)
                    .background(
                        color = dotColorFor(status).copy(alpha = dotAnimatedAlpha),
                        shape = CircleShape,
                    ),
            )
        }
    }
}
