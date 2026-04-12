package com.vela.app.audio

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TtsEngineTest {

    @Test
    fun speakRecordsText() = runTest {
        val tts = FakeTtsEngine()
        tts.speak("Hello world")
        assertThat(tts.spokenTexts).containsExactly("Hello world")
    }

    @Test
    fun speakMultipleTextsRecordsAll() = runTest {
        val tts = FakeTtsEngine()
        tts.speak("First")
        tts.speak("Second")
        tts.speak("Third")
        assertThat(tts.spokenTexts).containsExactly("First", "Second", "Third").inOrder()
    }

    @Test
    fun stopClearsCurrentSpeech() {
        val tts = FakeTtsEngine()
        tts.stop()
        assertThat(tts.stopCount).isEqualTo(1)
    }

    @Test
    fun shutdownCallsShutdown() {
        val tts = FakeTtsEngine()
        tts.shutdown()
        assertThat(tts.isShutdown).isTrue()
    }

    @Test
    fun implementsTtsEngineInterface() {
        val tts = FakeTtsEngine()
        assertThat(tts).isInstanceOf(TtsEngine::class.java)
    }
}
