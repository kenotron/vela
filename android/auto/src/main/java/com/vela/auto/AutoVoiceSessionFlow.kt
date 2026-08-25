package com.vela.auto

import com.vela.voice.handoff.TierCoordinator
import com.vela.voice.narrator.NarrationSink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart

/**
 * Hands-free voice session flow for the Android Auto surface (issue #50).
 *
 * There is no physical Android Auto head unit or DHU emulator available in
 * this build/test environment, so this class is a car-surface-shaped,
 * pure-Kotlin session flow: it is exercised on the JVM against the real
 * [TierCoordinator] pipeline (the same pipeline already wired into the phone
 * chat surface by `ChatViewModel.ingestVoiceTurn`), but it has NOT been
 * verified against real Android Auto hardware or the DHU emulator. Any
 * `CarAppService`/manifest-level entry point that invokes this class from a
 * live Auto session is a residual, to be wired and verified when Auto
 * hardware/emulator access is available.
 *
 * A "session" here is the handling of exactly one user utterance from start
 * (utterance received) to a terminal [TierCoordinator.TierEvent] (either
 * [TierCoordinator.TierEvent.RespondDirectly], [TierCoordinator.TierEvent.Completed],
 * or [TierCoordinator.TierEvent.Failed]). This matches how the phone chat
 * surface treats one turn of `TierCoordinator.handle(...)` as a
 * "session" for narration/result purposes.
 *
 * Instrumentation (issue #51) is built in from the start: every session's
 * start and completion are reported to [tracker] as they occur, not
 * bolted on after the fact.
 */
public class AutoVoiceSessionFlow(
    private val tierCoordinator: TierCoordinator,
    private val tracker: AutoVoiceSessionTracker,
) {

    /**
     * Result of a single completed Auto voice session, surfaced once the
     * underlying [TierCoordinator] flow reaches a terminal event.
     */
    public sealed interface SessionResult {
        /** Fast-tier direct response; no hand-off to the slow tier occurred. */
        public data class RespondedDirectly(val utterance: String) : SessionResult

        /** Slow-tier work completed successfully. */
        public data class Completed(val resultText: String) : SessionResult

        /** Slow-tier work failed. */
        public data class Failed(val message: String) : SessionResult
    }

    /**
     * Start a hands-free voice session for [utterance]. This is the
     * Auto-surface equivalent of `ChatViewModel.ingestVoiceTurn`: it drives
     * [utterance] through the real [TierCoordinator]/[com.vela.voice.handoff.SlowTierGateway]
     * pipeline, forwarding [TierCoordinator.TierEvent.Narrating] text to
     * [narrationSink] (e.g. for TTS), and reports session lifecycle to
     * [tracker]:
     *
     *  - [AutoVoiceSessionTracker.onSessionStarted] fires once, when
     *    collection of the underlying flow begins (i.e. the session has
     *    actually started, not merely been requested).
     *  - [AutoVoiceSessionTracker.onSessionCompleted] fires exactly once,
     *    when the flow finishes -- whether it reached a terminal
     *    [TierCoordinator.TierEvent] normally or the flow failed/was
     *    cancelled. `success` reflects whether a terminal, non-failure
     *    event was observed.
     *
     * Callers collect the returned [Flow] of [SessionResult] to react to
     * the terminal outcome (e.g. read result text aloud, end the Auto
     * session UI). Non-terminal events ([TierCoordinator.TierEvent.Acknowledged],
     * [TierCoordinator.TierEvent.Narrating]) are consumed internally (routed
     * to [narrationSink]/dropped) and do not appear in the returned flow --
     * only terminal, session-ending outcomes do.
     */
    public fun start(utterance: String, narrationSink: NarrationSink? = null): Flow<SessionResult> {
        var completedSuccessfully = false

        return kotlinx.coroutines.flow.flow {
            tierCoordinator.handle(utterance, narrationSink).collect { event ->
                when (event) {
                    is TierCoordinator.TierEvent.RespondDirectly -> {
                        completedSuccessfully = true
                        emit(SessionResult.RespondedDirectly(event.utterance))
                    }

                    is TierCoordinator.TierEvent.Acknowledged -> {
                        // Non-terminal: acknowledgement text is spoken by the
                        // caller directly from the ack event upstream in a
                        // real integration; this flow only surfaces terminal
                        // session outcomes.
                    }

                    is TierCoordinator.TierEvent.Narrating -> {
                        // Already forwarded to narrationSink by TierCoordinator.handle.
                    }

                    is TierCoordinator.TierEvent.Completed -> {
                        completedSuccessfully = true
                        emit(SessionResult.Completed(event.resultText))
                    }

                    is TierCoordinator.TierEvent.Failed -> {
                        completedSuccessfully = false
                        emit(SessionResult.Failed(event.message))
                    }
                }
            }
        }
            .onStart { tracker.onSessionStarted(utterance) }
            .onCompletion { cause -> tracker.onSessionCompleted(utterance, success = completedSuccessfully && cause == null) }
    }
}
