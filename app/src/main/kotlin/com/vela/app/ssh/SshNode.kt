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
    /** Bootstrap lifecycle state. New SSH nodes default to UNPROVISIONED. */
    val bootstrapStatus: BootstrapStatus = BootstrapStatus.UNPROVISIONED,
) {
    val primaryHost: String get() = hosts.firstOrNull() ?: ""
}
