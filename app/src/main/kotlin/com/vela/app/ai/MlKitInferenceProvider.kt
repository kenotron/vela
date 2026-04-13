    package com.vela.app.ai

    import kotlinx.coroutines.flow.Flow
    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * [InferenceProvider] backed by ML Kit Gemma 4 via Android AICore.
     *
     * Thin wrapper around [MlKitGemma4Engine] — all ML Kit logic stays in the engine;
     * this class only adapts it to the [InferenceProvider] interface.
     *
     * Used as the fallback provider when llama.cpp is not loaded (Phase 1 primary,
     * Phase 2 fallback after LlamaCppProvider is added as primary).
     */
    @Singleton
    class MlKitInferenceProvider @Inject constructor(
        private val engine: MlKitGemma4Engine,
    ) : InferenceProvider {

        override val name = "mlkit-gemma4"

        override suspend fun isAvailable(): Boolean =
            engine.checkReadiness() == ReadinessState.Available

        override fun streamText(prompt: String): Flow<String> = engine.streamText(prompt)

        override fun shutdown() = engine.shutdown()
    }
    