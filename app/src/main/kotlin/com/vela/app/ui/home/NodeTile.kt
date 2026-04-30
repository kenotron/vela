package com.vela.app.ui.home

import androidx.compose.ui.graphics.Color
import com.vela.app.ssh.NodeType
import com.vela.app.ssh.SshNode
import com.vela.app.ui.theme.VelaColors

/**
 * Visual status for a node tile's stripe, dot, and chip.
 *
 * Running / Waiting / Done require session data (Phase 3+).
 * In Phase 2 every node resolves to [Idle].
 */
enum class NodeTileStatus { Running, Waiting, Done, Idle }

// ── Pure logic helpers ───────────────────────────────────────────────────────
// All functions are `internal` so they are accessible from the test source set.

/** Stripe and live-dot color for the given status. */
internal fun stripeColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.Running
    NodeTileStatus.Waiting -> VelaColors.Waiting
    NodeTileStatus.Done    -> VelaColors.Done
    NodeTileStatus.Idle    -> VelaColors.Accent
}

/** Stripe and live-dot opacity for the given status. */
internal fun stripeAlphaFor(status: NodeTileStatus): Float = when (status) {
    NodeTileStatus.Running -> 1f
    NodeTileStatus.Waiting -> 1f
    NodeTileStatus.Done    -> 0.5f
    NodeTileStatus.Idle    -> 0.4f
}

/** Status-chip container fill for the given status. */
internal fun chipContainerColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.RunningContainer
    NodeTileStatus.Waiting -> VelaColors.WaitingContainer
    NodeTileStatus.Done    -> VelaColors.DoneContainer
    NodeTileStatus.Idle    -> VelaColors.SurfaceRaised
}

/** Status-chip text color for the given status. */
internal fun chipOnColorFor(status: NodeTileStatus): Color = when (status) {
    NodeTileStatus.Running -> VelaColors.RunningOnContainer
    NodeTileStatus.Waiting -> VelaColors.WaitingOnContainer
    NodeTileStatus.Done    -> VelaColors.DoneOnContainer
    NodeTileStatus.Idle    -> VelaColors.TextSecondary
}

/** All-caps chip label for the given status. */
internal fun chipLabelFor(status: NodeTileStatus): String = when (status) {
    NodeTileStatus.Running -> "RUNNING"
    NodeTileStatus.Waiting -> "WAITING"
    NodeTileStatus.Done    -> "DONE"
    NodeTileStatus.Idle    -> "IDLE"
}

/**
 * Derives tile status from node state alone (Phase 2).
 *
 * Returns [NodeTileStatus.Idle] for all nodes — session data is not available
 * until Phase 3. Phase 3 will pass an active-session count and return the
 * correct Running / Waiting / Done state.
 */
internal fun nodeStatusFor(node: SshNode): NodeTileStatus = NodeTileStatus.Idle

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
