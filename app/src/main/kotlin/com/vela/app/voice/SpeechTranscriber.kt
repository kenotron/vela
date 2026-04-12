package com.vela.app.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

sealed class TranscriptState {
    object Idle : TranscriptState()
    object Listening : TranscriptState()
    data class Partial(val text: String) : TranscriptState()
    data class Final(val text: String) : TranscriptState()
    data class Error(val cause: String) : TranscriptState()
}

interface SpeechTranscriber {
    val transcriptState: StateFlow<TranscriptState>
    fun startListening()
    fun stopListening()
    fun destroy()
}

class FakeSpeechTranscriber : SpeechTranscriber {
    private val _transcriptState = MutableStateFlow<TranscriptState>(TranscriptState.Idle)
    override val transcriptState: StateFlow<TranscriptState> = _transcriptState

    override fun startListening() {
        _transcriptState.value = TranscriptState.Listening
    }

    override fun stopListening() {
        _transcriptState.value = TranscriptState.Idle
    }

    override fun destroy() {
        _transcriptState.value = TranscriptState.Idle
    }

    fun emitPartial(text: String) {
        _transcriptState.value = TranscriptState.Partial(text)
    }

    fun emitFinal(text: String) {
        _transcriptState.value = TranscriptState.Final(text)
    }

    fun emitError(cause: String) {
        _transcriptState.value = TranscriptState.Error(cause)
    }
}
