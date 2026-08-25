package com.vela.voice

import com.vela.core.domain.VoiceTransport
import com.vela.voice.internal.LiveKitRoomClient
import com.vela.voice.turndetection.TurnDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Issue #24 (V2) code-level verification: [LiveKitVoiceTransport] must feed
 * incoming transcript deltas into its client-local [TurnDetector], self-drive
 * a background silence-poll loop (so pure elapsed silence - no further delta
 * ever arriving - is actually detected without any external app-level
 * ticker), and reset that detector on barge-in - all without altering the
 * existing incoming/outgoing event contract exercised by
 * [LiveKitVoiceTransportBargeInTest]. No emulator or live LiveKit server is
 * required - the room client is a fake, and the silence-poll loop is driven
 * by [kotlinx.coroutines.test]'s virtual time so tests are instant and
 * deterministic.
 */
class LiveKitVoiceTransportTurnDetectionTest {

    private class FakeRoomClient : LiveKitRoomClient {
        override val connectionState = MutableStateFlow(LiveKitRoomClient.RoomConnectionState.CONNECTED)
        override val events = MutableSharedFlow<LiveKitRoomClient.RoomEvent>(extraBufferCapacity = 8)

        override suspend fun connect(url: String, token: String) = Unit
        override suspend fun disconnect() = Unit
        override suspend fun publishAudio(pcm16: ByteArray, sampleRateHz: Int) = Unit
        override suspend fun signalBargeIn() = Unit
    }

    @Test
    fun `turnSignal is TurnOngoing before any transcript arrives`() = runBlocking {
        val fakeRoomClient = FakeRoomClient()
        val transport = LiveKitVoiceTransport(
            url = "wss://example.invalid",
            token = "test-token",
            roomClient = fakeRoomClient,
        )

        assertEquals(TurnDetector.Signal.TurnOngoing, transport.turnSignal.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `after connect, the internal silence-poll loop alone drives TurnComplete with no external ticker`() = runTest {
        // This is the load-bearing proof that turn detection is actually WIRED
        // into the live voice flow end-to-end, entirely inside android/voice:
        // nothing outside this transport (no app-level timer, no test-driven
        // manual tick call) is invoked below - only connect(), one transcript
        // delta, and virtual-time advancement of the SAME test dispatcher the
        // transport's internal poll loop runs on.
        val fakeRoomClient = FakeRoomClient()
        val transport = LiveKitVoiceTransport(
            url = "wss://example.invalid",
            token = "test-token",
            roomClient = fakeRoomClient,
            // Reuse THIS runTest's own TestScope (rather than a detached
            // TestScope) so the internally-launched poll loop is tracked by
            // runTest's own structured-concurrency/idle bookkeeping and is
            // reliably cancelled by transport.disconnect() below.
            turnDetectionScope = this,
            turnDetectionPollIntervalMs = 100L,
            // Tie TurnDetector's clock to the SAME virtual clock advanceTimeBy
            // manipulates below - otherwise TurnDetector would fall back to
            // real wall-clock time, which barely advances during a fast,
            // synchronous test and would never satisfy its silence threshold.
            turnDetectorClock = { testScheduler.currentTime },
        )

        transport.connect()

        fakeRoomClient.events.emit(
            LiveKitRoomClient.RoomEvent.Transcript(text = "what is the weather today", isFinal = true),
        )
        testScheduler.runCurrent()

        // No new delta ever arrives from here on - only elapsed silence.
        assertEquals(
            "immediately after the delta, before any silence has elapsed, the turn must still be ongoing",
            TurnDetector.Signal.TurnOngoing,
            transport.turnSignal.value,
        )

        advanceTimeBy(699L) // just under the 700ms default minTrailingSilenceMs
        testScheduler.runCurrent()
        assertEquals(
            "insufficient elapsed silence must not yet report completion",
            TurnDetector.Signal.TurnOngoing,
            transport.turnSignal.value,
        )

        advanceTimeBy(101L) // crosses the 700ms threshold; loop polls every 100ms
        testScheduler.runCurrent()
        assertEquals(
            "the internal poll loop, driven by nothing but connect() + elapsed virtual time, " +
                "must observe the completed trailing silence and report TurnComplete without " +
                "any external caller invoking a tick",
            TurnDetector.Signal.TurnComplete,
            transport.turnSignal.value,
        )

        transport.disconnect()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `disconnect stops the silence-poll loop so turnSignal no longer advances`() = runTest {
        val fakeRoomClient = FakeRoomClient()
        val transport = LiveKitVoiceTransport(
            url = "wss://example.invalid",
            token = "test-token",
            roomClient = fakeRoomClient,
            turnDetectionScope = this,
            turnDetectionPollIntervalMs = 100L,
            turnDetectorClock = { testScheduler.currentTime },
        )

        transport.connect()
        fakeRoomClient.events.emit(
            LiveKitRoomClient.RoomEvent.Transcript(text = "hello there friend", isFinal = true),
        )
        transport.disconnect()

        // With the loop stopped, no amount of further (virtual) time should
        // flip the signal, proving the loop is genuinely cancelled rather than
        // merely paused.
        advanceTimeBy(10_000L)
        testScheduler.runCurrent()

        assertEquals(
            "after disconnect, the poll loop must be cancelled - it must not keep evaluating " +
                "and flipping turnSignal in the background",
            TurnDetector.Signal.TurnOngoing,
            transport.turnSignal.value,
        )
    }

    @Test
    fun `a final transcript delta is still surfaced unchanged on the existing incomingEvents contract`() = runBlocking {
        val fakeRoomClient = FakeRoomClient()
        val transport = LiveKitVoiceTransport(
            url = "wss://example.invalid",
            token = "test-token",
            roomClient = fakeRoomClient,
        )

        val received = mutableListOf<VoiceTransport.VoiceEvent>()
        val job = GlobalScope.launch(Dispatchers.Unconfined) {
            transport.incomingEvents.collect { received.add(it) }
        }

        fakeRoomClient.events.emit(
            LiveKitRoomClient.RoomEvent.Transcript(text = "what is the weather today", isFinal = true),
        )

        job.cancel()

        assertEquals(
            listOf(VoiceTransport.VoiceEvent.TranscriptDelta("what is the weather today", isFinal = true)),
            received,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `barge-in resets turn-detection state and still surfaces VoiceEvent BargeIn to callers`() = runTest {
        val fakeRoomClient = FakeRoomClient()
        val transport = LiveKitVoiceTransport(
            url = "wss://example.invalid",
            token = "test-token",
            roomClient = fakeRoomClient,
            turnDetectionScope = this,
            turnDetectionPollIntervalMs = 100L,
            turnDetectorClock = { testScheduler.currentTime },
        )

        val received = mutableListOf<VoiceTransport.VoiceEvent>()
        val job = launch(Dispatchers.Unconfined) {
            transport.incomingEvents.collect { received.add(it) }
        }

        transport.connect()

        fakeRoomClient.events.emit(
            LiveKitRoomClient.RoomEvent.Transcript(text = "what is the weather today", isFinal = true),
        )
        advanceTimeBy(800L) // would complete the turn if not for the barge-in below
        testScheduler.runCurrent()
        fakeRoomClient.events.emit(LiveKitRoomClient.RoomEvent.BargeInDetected)
        testScheduler.runCurrent()

        job.cancel()
        transport.disconnect()

        assertEquals(
            "existing barge-in event surfacing must not regress",
            true,
            received.any { it is VoiceTransport.VoiceEvent.BargeIn },
        )
        assertEquals(
            "after a barge-in reset, turn-detection state must not spuriously report " +
                "completion from the previous (discarded) turn's content",
            TurnDetector.Signal.TurnOngoing,
            transport.turnSignal.value,
        )
    }
}
