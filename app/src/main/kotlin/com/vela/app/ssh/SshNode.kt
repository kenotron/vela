package com.vela.app.ssh

    import java.util.UUID

    enum class NodeType { SSH, AMPLIFIERD }

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
    ) {
        val primaryHost: String get() = hosts.firstOrNull() ?: ""
    }
    