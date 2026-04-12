package com.vela.app.ai

    /**
     * Abstraction over the real ML Kit [com.google.mlkit.genai.prompt.GenerativeModel] that allows
     * unit tests to inject a fast, in-process test double without Android dependencies.
     */
    internal interface MlKitModelPort {
        val isClosed: Boolean
        suspend fun checkStatus(): ReadinessState
        suspend fun download()
        suspend fun generate(input: String): String
        fun close()
    }
    