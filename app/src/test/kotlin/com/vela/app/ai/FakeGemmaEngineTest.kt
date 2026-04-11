package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FakeGemmaEngineTest {

    @Test
    fun processTextReturnsNonEmptyResponse() = runTest {
        val engine: GemmaEngine = FakeGemmaEngine()
        val response = engine.processText("hello")
        assertThat(response).isNotNull()
        assertThat(response).isNotEmpty()
    }

    @Test
    fun processTextReturnsCannedGreeting() = runTest {
        val engine: GemmaEngine = FakeGemmaEngine()
        val response = engine.processText("hello")
        assertThat(response).isEqualTo("Hello! I'm Vela, your on-device AI assistant. How can I help?")
    }

    @Test
    fun processTextHandlesDifferentInputs() = runTest {
        val engine: GemmaEngine = FakeGemmaEngine()
        val responseOne = engine.processText("What is the weather like?")
        val responseTwo = engine.processText("")
        assertThat(responseOne).isNotEmpty()
        assertThat(responseTwo).isNotEmpty()
    }

    @Test
    fun engineImplementsGemmaEngineInterface() {
        val engine = FakeGemmaEngine()
        assertThat(engine).isInstanceOf(GemmaEngine::class.java)
    }
}
