package com.vela.app.ai

interface GemmaEngine {
    suspend fun processText(input: String): String
}
