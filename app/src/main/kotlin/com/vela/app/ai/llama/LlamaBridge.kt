    package com.vela.app.ai.llama

    /**
     * Kotlin JNI declarations for the native llama.cpp bridge (vela-llama.so).
     * C++ implementations live in app/src/main/cpp/llama_bridge.cpp.
     *
     * The caller ([LlamaCppProvider]) is responsible for building the full
     * Gemma 4 formatted prompt (including <bos>, <|turn>, <|tool> blocks, etc.)
     * via [GemmaPromptBuilder]. The bridge tokenises and generates without any
     * additional formatting — parse_special=true handles the special tokens.
     */
    object LlamaBridge {

        init { System.loadLibrary("vela-llama") }

        /** Load a GGUF model. Returns non-zero context pointer on success, 0 on failure. */
        external fun nativeLoad(
            modelPath: String,
            nCtx: Int = 4096,
            nThreads: Int = 4,
            nGpuLayers: Int = 0,
        ): Long

        /**
         * Run text completion for [prompt].
         *
         * The prompt is expected to be a fully-formatted Gemma 4 native chat string
         * including BOS token and all special turn/tool tags. The bridge tokenises
         * it with add_special=false (BOS already in string) and parse_special=true
         * (special tokens like <|turn> are in the vocabulary).
         *
         * Each generated token piece is emitted to [tokenCallback] for streaming UI.
         * Returns the full generated text.
         */
        external fun nativeCompletion(
            contextPtr: Long,
            prompt: String,
            nPredict: Int = 512,
            tokenCallback: TokenCallback,
        ): String

        /** Free native model + context. Call when done or before swapping models. */
        external fun nativeFree(contextPtr: Long)

        fun interface TokenCallback {
            fun onToken(token: String)
        }
    }
    