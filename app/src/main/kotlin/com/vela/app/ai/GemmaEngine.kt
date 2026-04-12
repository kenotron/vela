package com.vela.app.ai

interface GemmaEngine {
    suspend fun processText(input: String): String
    fun shutdown() {}  // default no-op so FakeGemmaEngine doesn't need to implement
}
