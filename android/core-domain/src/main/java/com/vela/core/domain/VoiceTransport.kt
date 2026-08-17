package com.vela.core.domain

import kotlinx.coroutines.flow.Flow

/**
 * Domain interface for the voice transport layer.
 *
 * Implemented by lane 1.2. This lane (1.1) defines the contract only, so other
 * lanes can code against a stable surface before their implementation lands.
 */
interface VoiceTransport {

    /** Current connection state of the transport. */
    val state: Flow<TransportState>

    /** Stream of incoming voice/text events from the remote party. */
    val incomingEvents: Flow<VoiceEvent>

    /** Establish (or re-establish) the transport connection. */
    suspend fun connect()

    /** Tear down the transport connection. */
    suspend fun disconnect()

    /** Send an outgoing event (e.g. barge-in, push-to-talk audio chunk, text). */
    suspend fun send(event: VoiceEvent)

    enum class TransportState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        ERROR,
    }

    sealed interface VoiceEvent {
        data class AudioChunk(val pcm16: ByteArray, val sampleRateHz: Int) : VoiceEvent
        data class TranscriptDelta(val text: String, val isFinal: Boolean) : VoiceEvent
        data object BargeIn : VoiceEvent
        data class Error(val message: String, val cause: Throwable? = null) : VoiceEvent
    }
}
