    package com.vela.app.ai

    import javax.inject.Inject
    import javax.inject.Singleton

    /**
     * Holds an ordered list of [InferenceProvider]s and returns the first available one.
     *
     * Providers are injected in priority order via AppModule:
     *   Phase 1: [MlKitInferenceProvider] only.
     *   Phase 2: [LlamaCppProvider] prepended — llama.cpp is primary, ML Kit is fallback.
     *
     * Falls back through the list if the primary provider is not ready (model not downloaded,
     * AICore not available, etc.).
     */
    @Singleton
    class ProviderRegistry @Inject constructor(
        private val providers: List<InferenceProvider>,
    ) {
        /**
         * Returns the first provider that is currently available.
         * If no provider is available, returns the first registered provider and lets
         * it fail at inference time with a meaningful error.
         */
        suspend fun current(): InferenceProvider =
            providers.firstOrNull { it.isAvailable() }
                ?: providers.firstOrNull()
                ?: error("No InferenceProviders registered in ProviderRegistry")

        fun all(): List<InferenceProvider> = providers
    }
    