package com.vela.app.ai

interface LifecycleAwareEngine : GemmaEngine {
    suspend fun checkReadiness(): ReadinessState
    suspend fun ensureReady()
}
