package com.vela.app.ssh

/**
 * Live reachability state for an amplifierd node's HTTP endpoint.
 *
 * Used by [HomeViewModel] to drive the status chip on each [NodeTileItem] and
 * by [NodeDetailViewModel] to show accurate online/offline telemetry.
 */
sealed class NodeConnectivity {
    /** Initial state — health check has not yet been attempted. */
    object Unknown : NodeConnectivity()
    /** A health check is currently in flight. */
    object Checking : NodeConnectivity()
    /** /health returned 200; [activeUrl] is the URL that responded. */
    data class Reachable(val activeUrl: String) : NodeConnectivity()
    /** All candidate URLs timed out or returned non-2xx. */
    object Unreachable : NodeConnectivity()
}
