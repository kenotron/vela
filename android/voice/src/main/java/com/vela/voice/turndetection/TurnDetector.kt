package com.vela.voice.turndetection

/**
 * Client-side turn-detection abstraction for issue #24 (V2: "never interrupt a
 * thinking pause; use semantic turn detection, not a silence timer").
 *
 * ## Why this exists and what it is NOT
 *
 * The primary, authoritative turn-completion signal for this product is the
 * LiveKit Agents semantic turn detector running server-side in voice-worker
 * (see the comment on [com.vela.voice.LiveKitVoiceTransport.send]'s
 * `AudioChunk` branch). That server-side model has access to the full audio
 * stream and a purpose-built semantic model; this client module intentionally
 * does not attempt to reimplement or second-guess it for the primary path.
 *
 * This [TurnDetector] exists for the client-local situations where no
 * server-side semantic signal is available to the `android/voice` module
 * today:
 *  - the client only observes [com.vela.voice.internal.LiveKitRoomClient.RoomEvent.Transcript]
 *    deltas (text + isFinal) over the vendor-free seam - there is no semantic
 *    "end of turn" event type currently defined on that seam;
 *  - local UI affordances (e.g. "still listening..." indicators, deciding
 *    when a PTT-less push affordance should re-arm) need *some* client-local
 *    estimate of turn completion, independent of and no slower than the
 *    server-side model, without introducing a network round trip.
 *
 * BLOCKED (named, not silently worked around): the current LiveKit vendor
 * integration (as reachable through [com.vela.voice.internal.LiveKitRoomClient])
 * exposes no explicit semantic end-of-turn event on its `RoomEvent` union.
 * Wiring the *real* server-side semantic signal through to this module (i.e.
 * adding a `RoomEvent.EndOfTurn` / `VoiceEvent` case end-to-end) requires
 * changes to `LiveKitRoomClient`'s real implementation and potentially the
 * `VoiceTransport` contract in `core-domain`, which is outside this lane's
 * file ownership (this module, `android/voice`, only) and is recorded as a residual.
 *
 * ## What this class does instead: a hybrid heuristic
 *
 * This is explicitly **not** a naive fixed-silence-threshold VAD (the exact
 * anti-pattern V2 calls out). A naive VAD fires end-of-turn purely because N
 * milliseconds of silence elapsed, which is what causes assistants to barge
 * in on thinking pauses ("uh... let me think... ok so").
 *
 * Instead, [TurnDetector] requires **both**:
 *  1. A minimum trailing silence since the last transcript delta
 *     ([Config.minTrailingSilenceMs]), AND
 *  2. Content-completeness: the accumulated utterance has at least
 *     [Config.minContentWords] words, AND was marked final by the upstream
 *     transcript source at least once ([TranscriptSample.isFinal]).
 *
 * If the trailing text ends with terminal punctuation
 * ([Config.terminalPunctuation]), the required trailing silence is relaxed to
 * [Config.minTrailingSilenceAfterTerminalPunctuationMs] (shorter), since a
 * sentence-final punctuation mark is itself a strong content-completeness
 * signal - this is the "semantic/prosodic-adjacent" part of the hybrid: it
 * uses *what was said*, not just *how long nothing was said*, to decide
 * whether a pause is a thinking-pause or a genuine end of turn.
 *
 * This class is a small, deterministic, synchronous state machine with an
 * injectable clock - fully unit-testable without a device, emulator, or live
 * LiveKit connection.
 */
public class TurnDetector(
    private val config: Config = Config(),
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    public data class Config(
        /** Minimum silence, in ms, since the last transcript delta before a turn can be considered complete. */
        val minTrailingSilenceMs: Long = 700L,
        /**
         * Relaxed (shorter) trailing silence requirement, in ms, applied when the
         * trailing text already ends with terminal punctuation - a strong
         * independent signal of completeness beyond raw silence duration.
         */
        val minTrailingSilenceAfterTerminalPunctuationMs: Long = 250L,
        /** Minimum word count for the accumulated utterance to count as "content complete". */
        val minContentWords: Int = 2,
        /** Characters treated as sentence-final terminal punctuation. */
        val terminalPunctuation: Set<Char> = setOf('.', '?', '!'),
    )

    /** Result of evaluating the current turn state. */
    public sealed interface Signal {
        /** The user is still speaking, or too little content/silence has accumulated to conclude otherwise. */
        public data object TurnOngoing : Signal

        /** Trailing silence + content-completeness both indicate the user's turn has ended. */
        public data object TurnComplete : Signal
    }

    private var accumulatedText: String = ""
    private var lastDeltaAtMs: Long = 0L
    private var sawFinalDelta: Boolean = false
    private var turnAlreadyCompleted: Boolean = false

    /**
     * Feed a transcript delta observed from the transport (e.g. mapped from
     * [com.vela.voice.internal.LiveKitRoomClient.RoomEvent.Transcript]).
     *
     * Returns the current [Signal] immediately after ingesting this delta -
     * new deltas naturally reset the "silence" clock, so a delta that arrives
     * before [Config.minTrailingSilenceMs] has elapsed always yields
     * [Signal.TurnOngoing].
     */
    public fun onTranscriptDelta(text: String, isFinal: Boolean): Signal {
        accumulatedText = text
        lastDeltaAtMs = nowMs()
        if (isFinal) sawFinalDelta = true
        turnAlreadyCompleted = false
        return evaluate()
    }

    /**
     * Re-evaluate turn completion with no new transcript delta having arrived,
     * driven by an external periodic ticker (e.g. an app-level timer). This is
     * how a genuine trailing silence - as opposed to a delta arriving inside
     * the silence window - is detected: nothing new arrived, so on a later
     * tick the elapsed-since-last-delta duration crosses the threshold.
     */
    public fun onSilenceTick(): Signal = evaluate()

    /** Reset all accumulated state, e.g. at the start of a new turn or after a barge-in. */
    public fun reset() {
        accumulatedText = ""
        lastDeltaAtMs = 0L
        sawFinalDelta = false
        turnAlreadyCompleted = false
    }

    private fun evaluate(): Signal {
        if (accumulatedText.isBlank()) return Signal.TurnOngoing
        if (turnAlreadyCompleted) return Signal.TurnComplete

        val trimmed = accumulatedText.trim()
        val wordCount = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
        val hasMinContent = wordCount >= config.minContentWords
        val endsWithTerminalPunctuation = trimmed.lastOrNull() in config.terminalPunctuation

        val requiredSilenceMs = if (endsWithTerminalPunctuation) {
            config.minTrailingSilenceAfterTerminalPunctuationMs
        } else {
            config.minTrailingSilenceMs
        }

        val silenceElapsedMs = nowMs() - lastDeltaAtMs
        val silenceSatisfied = silenceElapsedMs >= requiredSilenceMs
        val contentComplete = sawFinalDelta && hasMinContent

        return if (silenceSatisfied && contentComplete) {
            turnAlreadyCompleted = true
            Signal.TurnComplete
        } else {
            Signal.TurnOngoing
        }
    }
}
