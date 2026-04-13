    package com.vela.app.ai.llama

    import com.vela.app.ai.ChatMessage
    import com.vela.app.ai.InferenceProvider
    import com.vela.app.ai.tools.Tool
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
     * Uses [GemmaPromptBuilder] to produce a Gemma 4 native chat prompt with
     * proper <|tool> declaration blocks and <|turn>role formatting, then passes
     * it to the C++ bridge for tokenisation and generation.
     *
     * The model outputs tool calls as:
     *   <|tool_call>call:search_web{query:"AI news"}<tool_call|>
     *
     * [com.vela.app.ai.GemmaToolCallParser] in [AgentOrchestrator] detects these
     * and runs the corresponding [Tool], feeding the result back as a
     * "tool" role message for the next generation pass.
     */
    class LlamaCppProvider(
        private val modelFile: File,
        private val nCtx: Int = 4096,
        private val nThreads: Int = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(2),
        private val nGpuLayers: Int = 0,
        private val nPredict: Int = 512,
    ) : InferenceProvider {

        override val name: String = "llama-cpp/\${modelFile.nameWithoutExtension}"

        @Volatile private var contextPtr: Long = 0L

        suspend fun loadModel() = withContext(Dispatchers.IO) {
            if (contextPtr != 0L) return@withContext
            check(modelFile.exists()) { "Model file not found: \${modelFile.absolutePath}" }
            contextPtr = LlamaBridge.nativeLoad(modelFile.absolutePath, nCtx, nThreads, nGpuLayers)
            check(contextPtr != 0L) { "Failed to load model: \${modelFile.name}" }
        }

        override suspend fun isAvailable(): Boolean = modelFile.exists() && contextPtr != 0L

        /**
         * Stream a completion. Builds the Gemma 4 native prompt from [messages] and [tools],
         * then generates token-by-token via JNI.
         */
        override fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<String> =
            callbackFlow {
                withContext(Dispatchers.IO) {
                    val prompt = GemmaPromptBuilder.build(messages, tools)
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

        override fun shutdown() {
            val ptr = contextPtr
            if (ptr != 0L) { contextPtr = 0L; LlamaBridge.nativeFree(ptr) }
        }
    }
    