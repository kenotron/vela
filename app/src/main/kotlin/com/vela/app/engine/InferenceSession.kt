package com.vela.app.engine

    /**
     * Testable interface for the AI inference backend.
     * [AmplifierSession] implements this. Tests use [FakeInferenceSession].
     */
    interface InferenceSession {
        fun isConfigured(): Boolean
        suspend fun runTurn(
            historyJson:       String,
            userInput:         String,
            userContentJson:   String? = null,   // null = plain text; non-null = Anthropic content blocks JSON
            systemPrompt:      String = "",
            onToolStart:       (suspend (name: String, argsJson: String) -> String),
            onToolEnd:         (suspend (stableId: String, result: String) -> Unit),
            onToken:           (suspend (token: String) -> Unit),
            onProviderRequest: (suspend () -> String?) = { null },   // ephemeral injection before each LLM call
                onServerTool:      (suspend (name: String, argsJson: String) -> Unit) = { _, _ -> },
            )
        }
    