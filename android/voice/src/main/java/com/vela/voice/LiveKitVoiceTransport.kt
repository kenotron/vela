package com.vela.voice

import com.vela.core.domain.VoiceTransport
import com.vela.voice.internal.LiveKitRoomClient
import com.vela.voice.turndetection.TurnDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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
    /**
     * Scope the internal silence-polling loop runs on (see [turnSignal]).
     * Injectable purely so unit tests can supply a [kotlinx.coroutines.test.TestScope]
     * and drive the loop deterministically with virtual time - production
     * wiring uses the default, which is this instance's own lifecycle-scoped
     * job cancelled in [disconnect].
     */
    private val turnDetectionScope: CoroutineScope = CoroutineScope(SupervisorJob()),
    /** Polling cadence for the internal silence-completion loop. See [turnSignal]. */
    private val turnDetectionPollIntervalMs: Long = 150L,
    /**
     * Clock used by [turnDetector] to measure trailing silence. Injectable
     * purely so unit tests can supply a virtual clock (e.g.
     * `{ testScheduler.currentTime }`) that advances in lockstep with
     * [turnDetectionScope]'s virtual time via `advanceTimeBy` - production
     * wiring uses the default real wall clock.
     */
    private val turnDetectorClock: () -> Long = { System.currentTimeMillis() },
) : VoiceTransport {

    private val outgoingErrors = MutableSharedFlow<VoiceTransport.VoiceEvent.Error>(extraBufferCapacity = 8)

    // Issue #24 (V2): client-local hybrid turn-detection heuristic, layered on
    // top of transcript deltas observed over this transport. This is
    // additive/advisory only - it does NOT drive `send(AudioChunk)` gating or
    // any server-side behavior; the authoritative turn-completion decision
    // remains the LiveKit Agents semantic turn detector running server-side
    // (see the AudioChunk branch of [send] below). See [TurnDetector]'s kdoc
    // for the full rationale, including the named BLOCKED gap (no semantic
    // end-of-turn event exists on the current [LiveKitRoomClient] seam) and
    // why this class exists to approximate it client-side in the meantime.
    private val turnDetector = TurnDetector(nowMs = turnDetectorClock)

    private val turnSignalState = MutableStateFlow<TurnDetector.Signal>(TurnDetector.Signal.TurnOngoing)
    private var silencePollJob: Job? = null
    private var eventFeedJob: Job? = null

    override val state: Flow<VoiceTransport.TransportState> =
        roomClient.connectionState.map { it.toTransportState() }

    override val incomingEvents: Flow<VoiceTransport.VoiceEvent> =
        roomClient.events.map { it.toVoiceEvent() }

    /**
     * Client-local turn-detection signal, self-driven end-to-end within this
     * transport for the duration of a connection - no external app-level
     * ticker or caller-side wiring is required.
     *
     * Two things keep this current while connected, both internal to this
     * class and both started in [connect] / stopped in [disconnect]:
     *  1. A dedicated collector on `roomClient.events` (see [startEventFeed])
     *     feeds every transcript delta into [turnDetector] directly,
     *     independent of whether any caller ever collects [incomingEvents] -
     *     [incomingEvents] is a cold flow, so relying on it alone would
     *     silently break turn detection for a caller that never subscribes.
     *  2. A background poll loop (see [startSilencePollLoop]) re-evaluates
     *     [turnDetector] on [turnDetectionPollIntervalMs] cadence so that
     *     pure elapsed silence - i.e. no further delta ever arrives - is
     *     actually observed rather than requiring one more event to trigger
     *     re-evaluation.
     *
     * Naming: not part of the [VoiceTransport] contract (that interface lives
     * in `core-domain`, outside this lane's file ownership of the
     * `android/voice` module) - this is additive API on the concrete
     * implementation.
     */
    public val turnSignal: StateFlow<TurnDetector.Signal> = turnSignalState

    override suspend fun connect() {
        roomClient.connect(url, token)
        startEventFeed()
        startSilencePollLoop()
    }

    override suspend fun disconnect() {
        stopSilencePollLoop()
        stopEventFeed()
        roomClient.disconnect()
    }

    /**
     * Feeds [turnDetector] directly from `roomClient.events`, independent of
     * whether [incomingEvents] is externally collected (see [turnSignal]'s
     * kdoc for why this must not depend on that cold flow being subscribed).
     *
     * CoroutineStart.UNDISPATCHED: begins collecting synchronously, before
     * this call returns, so there is no race where an event emitted
     * immediately after [connect] could be missed because the collector had
     * not yet subscribed to the (non-replaying) shared flow. The existing
     * barge-in tests establish this same synchronous-subscribe requirement
     * for `incomingEvents` consumers (they use Dispatchers.Unconfined for the
     * identical reason).
     */
    private fun startEventFeed() {
        if (eventFeedJob?.isActive == true) return
        eventFeedJob = turnDetectionScope.launch(start = CoroutineStart.UNDISPATCHED) {
            roomClient.events.collect { event ->
                when (event) {
                    is LiveKitRoomClient.RoomEvent.Transcript ->
                        turnSignalState.value = turnDetector.onTranscriptDelta(event.text, event.isFinal)
                    is LiveKitRoomClient.RoomEvent.BargeInDetected -> {
                        turnDetector.reset()
                        turnSignalState.value = TurnDetector.Signal.TurnOngoing
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun stopEventFeed() {
        eventFeedJob?.cancel()
        eventFeedJob = null
    }

    private fun startSilencePollLoop() {
        if (silencePollJob?.isActive == true) return
        silencePollJob = turnDetectionScope.launch {
            while (isActive) {
                delay(turnDetectionPollIntervalMs)
                turnSignalState.value = turnDetector.onSilenceTick()
            }
        }
    }

    private fun stopSilencePollLoop() {
        silencePollJob?.cancel()
        silencePollJob = null
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
                roomClient.signalBargeIn()
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
                // Turn-detector feeding happens in the dedicated `init` block
                // collector above (independent of whether this cold flow is
                // ever collected) - this mapping is a pure translation only.
                VoiceTransport.VoiceEvent.TranscriptDelta(text, isFinal)
            is LiveKitRoomClient.RoomEvent.BargeInDetected ->
                // Turn-detector reset on barge-in happens in the dedicated
                // `init` block collector above - this mapping is a pure
                // translation only.
                VoiceTransport.VoiceEvent.BargeIn
            is LiveKitRoomClient.RoomEvent.RoomError ->
                VoiceTransport.VoiceEvent.Error(message, cause)
        }
}
