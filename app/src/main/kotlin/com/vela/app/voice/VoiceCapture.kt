package com.vela.app.voice

import kotlinx.coroutines.flow.StateFlow

// AudioRecordWrapper interface retained for backward compatibility
interface AudioRecordWrapper {
    fun startRecording()
    fun stop()
    fun read(buffer: ByteArray, offsetInBytes: Int, sizeInBytes: Int): Int
    fun release()
}

class VoiceCapture(
    private val transcriber: SpeechTranscriber,
) {
    val transcriptState: StateFlow<TranscriptState> = transcriber.transcriptState

    fun startCapture() {
        transcriber.startListening()
    }

    fun stopCapture() {
        transcriber.stopListening()
    }

    fun destroy() {
        transcriber.destroy()
    }
}
