package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MediaPipeGemmaEngineTest {

    @Test
    fun processTextReturnsResponse() = runTest {
        val fake = FakeLlmInferenceWrapper("This is the answer.")
        val engine = MediaPipeGemmaEngine(fake)
        val result = engine.processText("What is 2+2?")
        assertThat(result).isEqualTo("This is the answer.")
    }

    @Test
    fun processTextPassesInputToInference() = runTest {
        val fake = FakeLlmInferenceWrapper("any response")
        val engine = MediaPipeGemmaEngine(fake)
        engine.processText("hello")
        assertThat(fake.lastInput).isEqualTo("hello")
    }

    @Test(expected = IllegalStateException::class)
    fun processTextThrowsWhenInferenceIsNull() = runTest {
        val engine = MediaPipeGemmaEngine(null)
        engine.processText("hello")
    }

    @Test
    fun implementsGemmaEngineInterface() {
        val fake = FakeLlmInferenceWrapper("response")
        val engine = MediaPipeGemmaEngine(fake)
        assertThat(engine).isInstanceOf(GemmaEngine::class.java)
    }
}
