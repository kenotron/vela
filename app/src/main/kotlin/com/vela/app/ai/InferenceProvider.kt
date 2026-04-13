    package com.vela.app.ai

    import kotlinx.coroutines.flow.Flow

    /**
     * Unified inference abstraction for any model backend (ML Kit, llama.cpp, HTTP).
     * The AgentOrchestrator talks only to InferenceProvider — never to backend-specific classes.
     * Phase 1: MlKitInferenceProvider implements this.
     * Phase 2: LlamaCppProvider implements this as primary.
     */
    interface InferenceProvider {
        val name: String

        /** True if this provider can handle inference right now. */
        suspend fun isAvailable(): Boolean

        /**
         * Stream token chunks for [prompt]. Each emitted String is a raw text delta.
         * The caller accumulates them into the full response.
         */
        fun streamText(prompt: String): Flow<String>

        /** Release resources (close model, free native memory). No-op by default. */
        fun shutdown() {}
    }
    