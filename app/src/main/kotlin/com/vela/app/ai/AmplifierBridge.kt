package com.vela.app.ai

    /**
     * JNI bridge to the amplifier-android Rust crate (libamplifier_android.so).
     *
     * The Rust side implements the agent loop:
     *  - AnthropicProvider — HTTP to api.anthropic.com/v1/messages
     *  - SimpleOrchestrator — tool-calling loop (≤10 steps) with hook callbacks
     *  - SimpleContext — in-memory message history
     *
     * Tool calls and hook callbacks delegate back to Kotlin so Android-specific
     * logic stays in Kotlin.
     */
    object AmplifierBridge {

        init { System.loadLibrary("amplifier_android") }

        external fun nativeRun(
            apiKey:            String,
            model:             String,
            toolsJson:         String,
            historyJson:       String,
            userInput:         String,
            userContentJson:   String?,       // null = plain text; non-null = content blocks JSON
            systemPrompt:      String,
            tokenCb:           TokenCallback,
            toolCb:            ToolCallback,
            providerRequestCb: ProviderRequestCallback,   // called before each LLM call
                serverToolCb:      ServerToolCallback,           // called when server tool executes
            ): String

        /** Per-token streaming callback — called from the Rust decode loop. */
        fun interface TokenCallback {
            fun onToken(token: String)
        }

        /**
         * Tool execution callback.
         *
         * @param name     Tool name (e.g. "search_web")
         * @param argsJson JSON object of arguments
         * @return         Tool result string passed back to the model
         */
        fun interface ToolCallback {
            fun executeTool(name: String, argsJson: String): String
        }

        /**
         * Server tool callback — called when Anthropic server tools (e.g. web_search_20250305)
         * execute. UI display only — no local execution needed.
         *
         * @param name     Tool name, e.g. "web_search"
         * @param argsJson JSON object of arguments, e.g. {"query":"..."}
         */
        fun interface ServerToolCallback {
            fun onServerTool(name: String, argsJson: String)
        }

        /**
         * Provider request callback — called by the Rust orchestrator before each
         * LLM API call within the agent loop.
         *
         * @return Ephemeral context to inject before the LLM call, or null / empty
         *         string for no injection. Injected content is NOT stored in history.
         */
        fun interface ProviderRequestCallback {
            fun onProviderRequest(): String?
        }
    }
    