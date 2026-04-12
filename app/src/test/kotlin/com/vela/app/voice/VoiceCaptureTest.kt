package com.vela.app.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceCaptureTest {

    @Test
    fun initialStateIsIdle() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun startCaptureTransitionsToListening() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        voiceCapture.startCapture()
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Listening)
    }

    @Test
    fun stopCaptureTransitionsToIdle() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        voiceCapture.startCapture()
        voiceCapture.stopCapture()
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Idle)
    }

    @Test
    fun transcriptStateForwardsFinal() {
        val transcriber = FakeSpeechTranscriber()
        val voiceCapture = VoiceCapture(transcriber)
        transcriber.emitFinal("hello world")
        assertThat(voiceCapture.transcriptState.value).isEqualTo(TranscriptState.Final("hello world"))
    }
}
