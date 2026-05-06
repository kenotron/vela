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
    /** amplifierd base URL, e.g. http://10.0.0.106:8410 */
    val url:      String = "",
    /** amplifierd x-amplifier-token shared secret. */
    val token:    String = "",
    /**
     * Tailscale IP URL (e.g. http://100.x.x.x:8410), if detected during bootstrap.
     * Tried first during connectivity checks since it works across networks.
     * Empty when the remote machine is not on Tailscale.
     */
    val tailscaleUrl: String = "",
    /** Bootstrap lifecycle state. New SSH nodes default to UNPROVISIONED. */
    val bootstrapStatus: BootstrapStatus = BootstrapStatus.UNPROVISIONED,
    /** Workspace directory used as cwd when amplifierd runs sessions on this node. */
    val workspaceDir: String = "~",
) {
    val primaryHost: String get() = hosts.firstOrNull() ?: ""
}
