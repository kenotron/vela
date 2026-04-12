package com.vela.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpeechTranscriberTest {

    @Test
    fun initialStateIsIdle() {
        val transcriber = FakeSpeechTranscriber()
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun startListeningTransitionsToListening() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.startListening()
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Listening)
    }

    @Test
    fun emitPartialUpdatesState() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.emitPartial("hello world")
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Partial("hello world"))
    }

    @Test
    fun emitFinalUpdatesState() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.emitFinal("done speaking")
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Final("done speaking"))
    }

    @Test
    fun stopListeningFromListeningGoesToIdle() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.startListening()
        transcriber.stopListening()
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun emitErrorUpdatesState() {
        val transcriber = FakeSpeechTranscriber()
        transcriber.emitError("microphone unavailable")
        assertThat(transcriber.transcriptState.value).isEqualTo(TranscriptState.Error("microphone unavailable"))
    }
}
