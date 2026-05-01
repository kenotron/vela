package com.vela.app.amplifierd

    data class AmplifierdProject(
        val id: String,
        val name: String,
        val description: String,
        val createdAt: Long,
        val workingDir: String = "",
    )

    data class AmplifierdSession(
        val sessionId: String,
        val projectId: String,
        val bundleName: String,
        val createdAt: Long,
        val lastActivity: Long = 0L,
        val status: String,  // "running" | "waiting" | "completed" | "error"
        val title: String = "",
    )

    data class AmplifierdCapabilities(
        val hostname: String,
        val platform: String,
        val amplifierdVersion: String,
        val activeBundles: List<String>,
        val availableTools: List<String>,
        /** Number of currently active (running/waiting) sessions on this node. */
        val activeSessions: Int = 0,
    )
