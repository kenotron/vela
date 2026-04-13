    package com.vela.app.ssh

    import java.util.UUID

    data class SshNode(
        val id: String   = UUID.randomUUID().toString(),
        val label: String,        // "MacBook Pro", "Dev Server"
        val host: String,         // "192.168.1.50" or "myserver.local"
        val port: Int    = 22,
        val username: String,     // "ken"
        val addedAt: Long = System.currentTimeMillis(),
    )
    