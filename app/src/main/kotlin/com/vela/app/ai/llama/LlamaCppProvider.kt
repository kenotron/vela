    package com.vela.app.ai.llama

    import com.vela.app.ai.InferenceProvider
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.channels.awaitClose
    import kotlinx.coroutines.flow.Flow
    import kotlinx.coroutines.flow.callbackFlow
    import kotlinx.coroutines.flow.flowOn
    import kotlinx.coroutines.withContext
    import java.io.File

    /**
     * [InferenceProvider] backed by llama.cpp via JNI.
     *
     * Loads any GGUF model file. Works with Gemma 3/4, Qwen 2.5, Llama 3.x — any model
     * whose chat template is embedded in the GGUF (applied automatically by llama_bridge.cpp).
     *
     * Streaming: tokens flow from native code → [LlamaBridge.TokenCallback] → [callbackFlow].
     *
     * Lifecycle:
     *   1. Create instance with the model file.
     *   2. Call [loadModel] once (suspends while loading — show a progress indicator).
     *   3. Inject into [ProviderRegistry] — it becomes the primary provider.
     *   4. Call [shutdown] when the app is closing or the model is being swapped.
     */
    class LlamaCppProvider(
        private val modelFile: File,
        private val nCtx: Int = 4096,
        private val nThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        private val nGpuLayers: Int = 0,   // 0 = CPU only; 999 = offload all to GPU (Adreno)
        private val nPredict: Int = 512,
    ) : InferenceProvider {

        override val name: String = "llama-cpp/${modelFile.nameWithoutExtension}"

        @Volatile
        private var contextPtr: Long = 0L

        /**
         * Load the GGUF model into native memory. Suspends on IO until the model is ready.
         * Safe to call multiple times — no-ops if already loaded.
         *
         * @throws IllegalStateException if the model file doesn't exist or loading fails.
         */
        suspend fun loadModel() = withContext(Dispatchers.IO) {
            if (contextPtr != 0L) return@withContext
            check(modelFile.exists()) { "Model file not found: ${modelFile.absolutePath}" }

            contextPtr = LlamaBridge.nativeLoad(
                modelPath  = modelFile.absolutePath,
                nCtx       = nCtx,
                nThreads   = nThreads,
                nGpuLayers = nGpuLayers,
            )
            check(contextPtr != 0L) { "Failed to load model: ${modelFile.name}" }
        }

        /**
         * True when the model file exists AND has been loaded into native memory.
         * The ProviderRegistry checks this to decide whether to use this provider.
         */
        override suspend fun isAvailable(): Boolean =
            modelFile.exists() && contextPtr != 0L

        /**
         * Stream token chunks from llama.cpp for [prompt].
         * Each emitted String is a raw token piece — the caller accumulates them.
         *
         * Runs entirely on [Dispatchers.IO]; safe to collect on any coroutine context.
         */
        override fun streamText(prompt: String): Flow<String> = callbackFlow<String> {
            withContext(Dispatchers.IO) {
                LlamaBridge.nativeCompletion(
                    contextPtr    = contextPtr,
                    prompt        = prompt,
                    nPredict      = nPredict,
                    tokenCallback = { token -> trySend(token) },
                )
            }
            close()
            awaitClose()
        }.flowOn(Dispatchers.IO)

        /** Release native model memory. Call on app shutdown or before swapping models. */
        override fun shutdown() {
            val ptr = contextPtr
            if (ptr != 0L) {
                contextPtr = 0L
                LlamaBridge.nativeFree(ptr)
            }
        }
    }
    