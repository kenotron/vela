package com.vela.app.amplifierd

data class AmplifierdProject(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
)

data class AmplifierdSession(
    val sessionId: String,
    val projectId: String,
    val bundleName: String,
    val createdAt: Long,
    val status: String, // "running", "waiting", "done", "error"
)

data class AmplifierdCapabilities(
    val hostname: String,
    val platform: String,
    val amplifierdVersion: String,
    val activeBundles: List<String>,
    val availableTools: List<String>,
)
