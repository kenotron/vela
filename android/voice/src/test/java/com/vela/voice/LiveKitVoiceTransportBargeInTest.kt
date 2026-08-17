package com.vela.voice

import com.vela.core.domain.VoiceTransport
import com.vela.voice.internal.LiveKitRoomClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Item 4 code-level verification: barge-in (V1/V2/V3) must actually flow
 * through [LiveKitVoiceTransport] in both directions -
 *   - outgoing: VoiceEvent.BargeIn -> LiveKitRoomClient.signalBargeIn()
 *   - incoming: LiveKitRoomClient.RoomEvent.BargeInDetected -> VoiceEvent.BargeIn
 * against a fake [LiveKitRoomClient], with no emulator or live LiveKit
 * server required. This does not (and cannot, in this environment) verify
 * that TTS audio physically stops playing on a device - that remains
 * BLOCKED-no-dev-kvm - but it does prove the transport's own barge-in
 * plumbing is wired correctly and behaves as intended, rather than being
 * untested placeholder code.
 */
class LiveKitVoiceTransportBargeInTest {

    private class FakeRoomClient : LiveKitRoomClient {
        override val connectionState = MutableStateFlow(LiveKitRoomClient.RoomConnectionState.CONNECTED)
        override val events = MutableSharedFlow<LiveKitRoomClient.RoomEvent>(extraBufferCapacity = 8)

        var signalBargeInCallCount = 0
            private set
        var publishAudioCallCount = 0
            private set

        override suspend fun connect(url: String, token: String) = Unit
        override suspend fun disconnect() = Unit

        override suspend fun publishAudio(pcm16: ByteArray, sampleRateHz: Int) {
            publishAudioCallCount++
        }

        override suspend fun signalBargeIn() {
            signalBargeInCallCount++
        }
    }

    @Test
    fun `sending VoiceEvent BargeIn signals the room client exactly once and does not publish audio`() = runBlocking {
        val fakeRoomClient = FakeRoomClient()
        val transport = LiveKitVoiceTransport(
            url = "wss://example.invalid",
            token = "test-token",
            roomClient = fakeRoomClient,
        )

        transport.send(VoiceTransport.VoiceEvent.BargeIn)

        assertEquals(
            "send(BargeIn) must call signalBargeIn() on the room client - " +
                "publishing silent/empty audio is not a real interrupt signal",
            1,
            fakeRoomClient.signalBargeInCallCount,
        )
        assertEquals(
            "send(BargeIn) must not fall through to publishAudio",
            0,
            fakeRoomClient.publishAudioCallCount,
        )
    }

    @Test
    fun `a room-level BargeInDetected event surfaces as VoiceEvent BargeIn to callers`() = runBlocking {
        val fakeRoomClient = FakeRoomClient()
        val transport = LiveKitVoiceTransport(
            url = "wss://example.invalid",
            token = "test-token",
            roomClient = fakeRoomClient,
        )

        val received = mutableListOf<VoiceTransport.VoiceEvent>()
        val job = kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Unconfined) {
            transport.incomingEvents.collect { received.add(it) }
        }

        fakeRoomClient.events.emit(LiveKitRoomClient.RoomEvent.BargeInDetected)

        job.cancel()

        assertTrue(
            "expected a VoiceEvent.BargeIn to be emitted from incomingEvents after a " +
                "room-level BargeInDetected, got: $received",
            received.any { it is VoiceTransport.VoiceEvent.BargeIn },
        )
    }
}
