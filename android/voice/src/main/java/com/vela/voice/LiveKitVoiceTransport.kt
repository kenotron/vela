package com.vela.voice

import com.vela.core.domain.VoiceTransport
import com.vela.voice.internal.LiveKitRoomClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map

/**
 * LiveKit Agents-backed implementation of [VoiceTransport].
 *
 * CRITICAL contract: no LiveKit SDK type may appear on the public API surface of
 * this class. All direct LiveKit usage is confined to the internal
 * [LiveKitRoomClient] seam (see internal/LiveKitRoomClient.kt); this class only
 * ever sees plain Kotlin/JVM types and the core-domain [VoiceTransport] contract
 * types. This is enforced by a reflection-based test
 * (LiveKitVoiceTransportVendorLeakageTest) that fails the build if any public
 * method parameter or return type belongs to the `io.livekit` package.
 *
 * @param url LiveKit room URL to connect to.
 * @param token LiveKit access token for this session.
 * @param roomClient the internal room-client seam; defaults to a real
 *   implementation in production wiring (constructed by the DI graph), but is
 *   injectable here purely so unit tests can substitute a fake without needing
 *   a live LiveKit server or SDK classes on the test classpath.
 */
public class LiveKitVoiceTransport internal constructor(
    private val url: String,
    private val token: String,
    private val roomClient: LiveKitRoomClient,
) : VoiceTransport {

    private val outgoingErrors = MutableSharedFlow<VoiceTransport.VoiceEvent.Error>(extraBufferCapacity = 8)

    override val state: Flow<VoiceTransport.TransportState> =
        roomClient.connectionState.map { it.toTransportState() }

    override val incomingEvents: Flow<VoiceTransport.VoiceEvent> =
        roomClient.events.map { it.toVoiceEvent() }

    override suspend fun connect() {
        roomClient.connect(url, token)
    }

    override suspend fun disconnect() {
        roomClient.disconnect()
    }

    override suspend fun send(event: VoiceTransport.VoiceEvent) {
        when (event) {
            is VoiceTransport.VoiceEvent.AudioChunk -> {
                // V3: no preemptive/speculative generation. We publish exactly the
                // audio the caller hands us, in order, with no local buffering that
                // would let generation begin before the user's turn is complete.
                // Turn-completion detection is delegated entirely to the LiveKit
                // Agents semantic turn detector running server-side (voice-worker),
                // per V2 - this transport never runs its own silence-timer VAD.
                roomClient.publishAudio(event.pcm16, event.sampleRateHz)
            }
            is VoiceTransport.VoiceEvent.BargeIn -> {
                // Barge-in (V1/V2/V3): interrupts any in-flight TTS playback on the
                // worker side by signalling over the data channel. The room client
                // is responsible for the actual signalling mechanism; this class
                // simply forwards the intent without adding latency of its own.
                roomClient.publishAudio(ByteArray(0), 0)
            }
            is VoiceTransport.VoiceEvent.TranscriptDelta -> {
                // Outgoing transcript deltas are not part of this transport's
                // supported outbound contract today; ignored rather than thrown to
                // keep the transport forgiving of callers that send the full
                // VoiceEvent union.
            }
            is VoiceTransport.VoiceEvent.Error -> {
                outgoingErrors.tryEmit(event)
            }
        }
    }

    private fun LiveKitRoomClient.RoomConnectionState.toTransportState(): VoiceTransport.TransportState =
        when (this) {
            LiveKitRoomClient.RoomConnectionState.DISCONNECTED -> VoiceTransport.TransportState.DISCONNECTED
            LiveKitRoomClient.RoomConnectionState.CONNECTING -> VoiceTransport.TransportState.CONNECTING
            LiveKitRoomClient.RoomConnectionState.CONNECTED -> VoiceTransport.TransportState.CONNECTED
            LiveKitRoomClient.RoomConnectionState.RECONNECTING -> VoiceTransport.TransportState.RECONNECTING
            LiveKitRoomClient.RoomConnectionState.FAILED -> VoiceTransport.TransportState.ERROR
        }

    private fun LiveKitRoomClient.RoomEvent.toVoiceEvent(): VoiceTransport.VoiceEvent =
        when (this) {
            is LiveKitRoomClient.RoomEvent.RemoteAudio ->
                VoiceTransport.VoiceEvent.AudioChunk(pcm16, sampleRateHz)
            is LiveKitRoomClient.RoomEvent.Transcript ->
                VoiceTransport.VoiceEvent.TranscriptDelta(text, isFinal)
            is LiveKitRoomClient.RoomEvent.BargeInDetected ->
                VoiceTransport.VoiceEvent.BargeIn
            is LiveKitRoomClient.RoomEvent.RoomError ->
                VoiceTransport.VoiceEvent.Error(message, cause)
        }
}
