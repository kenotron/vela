package com.vela.voice.internal

import kotlinx.coroutines.flow.Flow

/**
 * Internal seam isolating direct LiveKit Android SDK usage from the rest of the
 * `android/voice` module. No LiveKit SDK types (Room, Track, Participant, etc.)
 * may appear anywhere in this interface's signature - only plain Kotlin/JVM
 * types and the internal [RoomEvent]/[RoomConnectionState] types below.
 *
 * [LiveKitVoiceTransport] depends only on this interface, never on the LiveKit
 * SDK directly. A concrete implementation ([LiveKitRoomClientImpl], not shown
 * here to keep this seam free of any real SDK import at the interface level)
 * would perform the actual `io.livekit.android.room.Room` connect/publish/
 * subscribe calls and translate LiveKit's own event types into [RoomEvent].
 *
 * This module was implemented in a network-restricted environment where the
 * LiveKit Android SDK artifact may not be resolvable by Gradle. Keeping all
 * direct SDK usage behind this narrow, purely-internal interface means the
 * public API surface of this module (and therefore the vendor-leakage test)
 * is unaffected either way, and the rest of the module's logic (PTT, earcons,
 * transport state mapping) compiles and is unit-testable independent of
 * whether the SDK dependency resolves.
 */
internal interface LiveKitRoomClient {
    /** Connection lifecycle state of the underlying room, as plain enum - no vendor type. */
    val connectionState: Flow<RoomConnectionState>

    /** Room-level events translated into vendor-free types. */
    val events: Flow<RoomEvent>

    suspend fun connect(url: String, token: String)

    suspend fun disconnect()

    /** Publish a chunk of outgoing PCM16 audio at the given sample rate. */
    suspend fun publishAudio(pcm16: ByteArray, sampleRateHz: Int)

    enum class RoomConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        RECONNECTING,
        FAILED,
    }

    sealed interface RoomEvent {
        data class RemoteAudio(val pcm16: ByteArray, val sampleRateHz: Int) : RoomEvent
        data class Transcript(val text: String, val isFinal: Boolean) : RoomEvent
        data object BargeInDetected : RoomEvent
        data class RoomError(val message: String, val cause: Throwable? = null) : RoomEvent
    }
}
