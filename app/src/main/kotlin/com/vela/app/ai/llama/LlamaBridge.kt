    package com.vela.app.ai.llama

    /**
     * Kotlin JNI declarations for the native llama.cpp bridge (vela-llama.so).
     *
     * The matching C++ implementations live in app/src/main/cpp/llama_bridge.cpp.
     * JNI function naming: Java_com_vela_app_ai_llama_LlamaBridge_<methodName>
     *
     * Thread safety: nativeCompletion blocks the calling thread until inference is done.
     * Always call from Dispatchers.IO, never from the main thread.
     */
    object LlamaBridge {

        init {
            System.loadLibrary("vela-llama")
        }

        /**
         * Load a GGUF model file and create an inference context.
         *
         * @param modelPath  Absolute path to the .gguf file.
         * @param nCtx       Context window size in tokens. 4096 is a safe default.
         * @param nThreads   Number of CPU threads to use. 4 is safe; use Runtime.availableProcessors()/2.
         * @param nGpuLayers Number of model layers to offload to GPU. 0 = CPU-only.
         *                   Use 999 to offload all layers (auto-clamps to model size).
         * @return Non-zero context pointer on success. 0 on failure.
         */
        external fun nativeLoad(
            modelPath: String,
            nCtx: Int = 4096,
            nThreads: Int = 4,
            nGpuLayers: Int = 0,
        ): Long

        /**
         * Run text completion for [prompt]. Calls [tokenCallback] synchronously for each
         * generated token. Returns the full generated text when done.
         *
         * The chat template baked into the GGUF (e.g. Gemma / Qwen / Llama 3) is applied
         * automatically by llama_chat_apply_template before tokenisation.
         *
         * @param contextPtr  Pointer returned by [nativeLoad].
         * @param prompt      User-visible prompt text (template applied in native code).
         * @param nPredict    Maximum tokens to generate.
         * @param tokenCallback  Called for each generated token piece (for streaming UI).
         */
        external fun nativeCompletion(
            contextPtr: Long,
            prompt: String,
            nPredict: Int = 512,
            tokenCallback: TokenCallback,
        ): String

        /**
         * Free the model and context, releasing all native memory.
         * After this call [contextPtr] is invalid — do not use it again.
         */
        external fun nativeFree(contextPtr: Long)

        /**
         * Functional interface for streaming token callbacks from native code.
         * Each [onToken] call receives one raw piece (may be sub-word, may contain spaces).
         */
        fun interface TokenCallback {
            fun onToken(token: String)
        }
    }
    