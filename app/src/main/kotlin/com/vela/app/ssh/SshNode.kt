package com.vela.app.ssh

    import java.util.UUID

    data class SshNode(
        val id: String = UUID.randomUUID().toString(),
        val label: String,
        /** Ordered list of IPs/hostnames to try. First is primary, rest are fallbacks. */
        val hosts: List<String>,
        val port: Int = 22,
        val username: String,
        val addedAt: Long = System.currentTimeMillis(),
    ) {
        val primaryHost: String get() = hosts.firstOrNull() ?: ""
    }
    