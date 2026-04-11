package com.vela.app.ai

class FakeGemmaEngine : GemmaEngine {
    override fun processText(input: String): String {
        Thread.sleep(100)
        return "Hello! I'm Vela, your on-device AI assistant. How can I help?"
    }
}
