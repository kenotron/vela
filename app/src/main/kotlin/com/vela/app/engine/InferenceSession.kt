package com.vela.app.engine

/**
 * Testable interface for the AI inference backend.
 * [AmplifierSession] implements this. Tests use [FakeInferenceSession].
 */
interface InferenceSession {
    fun isConfigured(): Boolean
    suspend fun runTurn(
        historyJson: String,
        userInput:   String,
        onToolStart: (suspend (name: String, argsJson: String) -> String),
        onToolEnd:   (suspend (stableId: String, result: String) -> Unit),
        onToken:     (suspend (token: String) -> Unit),
    )
}
