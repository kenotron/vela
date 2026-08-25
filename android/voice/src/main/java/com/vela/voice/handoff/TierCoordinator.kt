package com.vela.voice.handoff

import com.vela.voice.classifier.UtteranceClassifier
import com.vela.voice.narrator.NarrationSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect

/**
 * Fast-tier / slow-tier hand-off state machine for issue #23 (design doc
 * §4.4). Given a completed user utterance (i.e. after [com.vela.voice.turndetection.TurnDetector]
 * has signalled [com.vela.voice.turndetection.TurnDetector.Signal.TurnComplete]),
 * this class:
 *
 *  1. Classifies the utterance via [UtteranceClassifier].
 *  2. TRIVIAL: emits [TierEvent.RespondDirectly] -- the fast tier answers
 *     immediately, no hand-off, no narration.
 *  3. REAL_WORK: emits an [TierEvent.Acknowledged] verbal acknowledgement
 *     ("I'll get on that"-style), then dispatches to the slow tier via
 *     [SlowTierGateway], narrating each real progress event it emits
 *     ([TierEvent.Narrating], forwarded to a [NarrationSink] -- V4/V5: only
 *     ever derived from real slow-tier events, never synthetic filler), and
 *     finally folds the terminal result back in ([TierEvent.Completed] /
 *     [TierEvent.Failed]).
 *
 * ## Slow-tier link (named simplification for DONE.json)
 *
 * [SlowTierGateway] is a small interface owned by this module
 * (`android/voice`) rather than a concrete `vela-agentd` HTTP/tool-loop
 * client. The real bridge to `vela-agentd` lives in `android/host-tools`
 * (`AmplifierToolLoopClient`, `DispatchToFleetTool`) and `voice-worker`,
 * both outside this lane's file ownership (the `android/voice` module tree, plus additive
 * changes to `core-domain`'s `VoiceTransport`/`EventStream`). Wiring a real
 * `SlowTierGateway` implementation on top of `AmplifierToolLoopClient` (or a
 * new dedicated slow-tier dispatch tool) is recorded as a residual; this
 * lane defines the seam and a fully unit-testable state machine against it,
 * matching the same pattern `TurnDetector`/`LiveKitVoiceTransport` used for
 * #24's client-local heuristic vs. the real server-side signal.
 */
public class TierCoordinator(
    private val classifier: UtteranceClassifier,
    private val slowTierGateway: SlowTierGateway,
    private val acknowledgementText: (String) -> String = { "I'll get on that." },
) {

    /** Events emitted while handling a single completed user utterance. */
    public sealed interface TierEvent {
        /** TRIVIAL utterance: fast tier should answer directly. No hand-off occurred. */
        public data class RespondDirectly(val utterance: String) : TierEvent

        /** REAL_WORK utterance: verbal acknowledgement to speak immediately, before the slow tier responds. */
        public data class Acknowledged(val utterance: String, val acknowledgement: String) : TierEvent

        /** A real progress narration line derived from a slow-tier progress event (V5). */
        public data class Narrating(val text: String) : TierEvent

        /** The slow tier finished the work; this is the result to fold back into speech. */
        public data class Completed(val resultText: String) : TierEvent

        /** The slow tier failed; this is what should be spoken back to the user. */
        public data class Failed(val message: String) : TierEvent
    }

    /**
     * Handle one completed user utterance end-to-end, emitting [TierEvent]s
     * as they occur. Callers collect this flow and route each event to
     * speech/UI as appropriate (e.g. TTS for [TierEvent.Acknowledged],
     * [TierEvent.Completed], [TierEvent.Failed]; narration audio/text for
     * [TierEvent.Narrating]).
     *
     * Also forwards every [TierEvent.Narrating] line to [narrationSink], so
     * callers that only care about the narration text (not the full event
     * stream) can pass a sink instead of collecting the flow themselves.
     */
    public fun handle(utterance: String, narrationSink: NarrationSink? = null): Flow<TierEvent> =
        kotlinx.coroutines.flow.flow {
            when (classifier.classify(utterance)) {
                UtteranceClassifier.Classification.TRIVIAL -> {
                    emit(TierEvent.RespondDirectly(utterance))
                }

                UtteranceClassifier.Classification.REAL_WORK -> {
                    val ack = acknowledgementText(utterance)
                    emit(TierEvent.Acknowledged(utterance, ack))

                    slowTierGateway.dispatch(utterance).collect { slowEvent ->
                        val tierEvent = slowEvent.toTierEvent()
                        if (tierEvent is TierEvent.Narrating) {
                            narrationSink?.narrate(tierEvent.text)
                        }
                        emit(tierEvent)
                    }
                }
            }
        }

    private fun SlowTierEvent.toTierEvent(): TierEvent = when (this) {
        is SlowTierEvent.Progress -> TierEvent.Narrating(message)
        is SlowTierEvent.Completed -> TierEvent.Completed(resultText)
        is SlowTierEvent.Failed -> TierEvent.Failed(message)
    }
}

/**
 * Seam to the slow tier (`vela-agentd`). See [TierCoordinator]'s class kdoc
 * for why this is an interface owned by `android/voice` rather than a
 * concrete client, and what implementing it for real is left as a residual.
 */
public fun interface SlowTierGateway {
    /**
     * Dispatch [utterance] to the slow tier and stream back its real
     * progress/terminal events, in order, terminating with exactly one of
     * [SlowTierEvent.Completed] or [SlowTierEvent.Failed].
     */
    public fun dispatch(utterance: String): Flow<SlowTierEvent>
}

/** Real slow-tier events (V4/V5: narration must only ever derive from these, never synthetic filler). */
public sealed interface SlowTierEvent {
    public data class Progress(val message: String) : SlowTierEvent
    public data class Completed(val resultText: String) : SlowTierEvent
    public data class Failed(val message: String) : SlowTierEvent
}
