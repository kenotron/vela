package com.vela.app.ai

    import kotlinx.coroutines.delay

    class FakeGemmaEngine : GemmaEngine {
        override suspend fun processText(input: String): String {
            delay(100)
            return "Hello! I'm Vela, your on-device AI assistant. How can I help?"
        }
    }
    