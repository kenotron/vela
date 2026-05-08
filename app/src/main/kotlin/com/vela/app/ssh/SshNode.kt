package com.vela.app.ssh

import java.util.UUID

enum class NodeType { SSH, AMPLIFIERD }

/**
 * Lifecycle of an amplifierd-capable node.
 *
 * UNPROVISIONED → fresh SSH node, never bootstrapped.
 * BOOTSTRAPPING → bootstrap in progress.
 * RUNNING       → amplifierd is live and health-checked.
 * STALE         → running but a newer amplifierd version is available.
 * FAILED        → bootstrap attempted but failed; retry required.
 */
enum class BootstrapStatus {
    UNPROVISIONED,
    BOOTSTRAPPING,
    RUNNING,
    STALE,
    FAILED,
}

data class SshNode(
    val id:       String = UUID.randomUUID().toString(),
    val label:    String,
    /** Ordered list of IPs/hostnames for SSH nodes (primary + fallbacks). */
    val hosts:    List<String> = emptyList(),
    val port:     Int    = 22,
    val username: String = "",
    val addedAt:  Long   = System.currentTimeMillis(),
    /** Node type — SSH or Amplifierd daemon. */
    val type:     NodeType = NodeType.SSH,

    // ── Deprecated transport fields ───────────────────────────────────────────
    // Kept for backward-compat mapping reads; no longer written.
    /** @deprecated Use endpoints list instead. Kept for migration reads. */
    val url:          String = "",
    /** @deprecated Use endpoints list instead. Kept for migration reads. */
    val tailscaleUrl: String = "",

    // ── New multi-transport fields ────────────────────────────────────────────
    /** amplifierd x-amplifier-token shared secret. */
    val token:     String = "",
    /**
     * Stable hardware identity from /health machine_id.
     * Used to match mDNS-discovered services to saved nodes.
     */
    val machineId: String = "",
    /**
     * Ordered list of transport endpoints for this node.
     * Phase 2 EndpointResolver tries these in priority order:
     * Mdns (LAN-fastest) → Tailscale → Direct.
     */
    val endpoints: List<NodeEndpoint> = emptyList(),
    /** Bootstrap lifecycle state. New SSH nodes default to UNPROVISIONED. */
    val bootstrapStatus: BootstrapStatus = BootstrapStatus.UNPROVISIONED,
    /** Workspace directory used as cwd when amplifierd runs sessions on this node. */
    val workspaceDir: String = "~",
) {
    val primaryHost: String get() = hosts.firstOrNull() ?: ""
}
