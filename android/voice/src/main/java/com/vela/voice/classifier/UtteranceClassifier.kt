package com.vela.voice.classifier

/**
 * Fast-tier classifier for issue #23 (design doc §4.4, "Fast-tier / slow-tier
 * conversational split"): labels a completed user utterance as either
 * [Classification.TRIVIAL] (answerable directly by the fast/voice tier,
 * ~800ms) or [Classification.REAL_WORK] (must hand off to the slow tier,
 * `vela-agentd`).
 *
 * ## Simplification named for DONE.json
 *
 * The design doc's real architecture classifies with the fast-tier voice
 * model itself (a genuine LLM call, colocated with the realtime voice
 * session). Wiring a live model call is out of scope for this environment/
 * lane (no reachable fast-tier voice-model endpoint from this module, and
 * `android/voice` should not gain a new network dependency for this). This
 * class is a **deterministic, rule-based MVP behind the same
 * [UtteranceClassifier] interface** so the real model-backed implementation
 * can be swapped in later (e.g. in `voice-worker`, or as a real fast-tier
 * model call from `LiveKitVoiceTransport`) without touching callers.
 *
 * The heuristic:
 *  - Utterances containing an action verb/intent keyword (see
 *    [Config.actionKeywords]) are [Classification.REAL_WORK] -- these name a
 *    concrete task the slow tier must actually perform (schedule, send,
 *    email, book, search, delegate, remind, cancel, buy, create, look up,
 *    research, order, move, add, delete, update, etc).
 *  - Very short utterances (<= [Config.trivialMaxWords] words) with no
 *    action keyword are [Classification.TRIVIAL] -- greetings, "thanks",
 *    "ok", acknowledgements, and short clarifying questions ("what time is
 *    it", "who are you").
 *  - Everything else defaults to [Classification.TRIVIAL] -- the fast tier
 *    is free to just answer conversationally; false negatives here just mean
 *    the fast tier answers directly for something that arguably could have
 *    been delegated, which is the safe failure direction (no unnecessary
 *    slow-tier round trip), rather than blocking on a network call for a
 *    "hello".
 */
public interface UtteranceClassifier {
    public fun classify(utterance: String): Classification

    public enum class Classification {
        /** The fast tier answers directly; no hand-off to the slow tier. */
        TRIVIAL,

        /** Real work: hand off to the slow tier (vela-agentd) and narrate while waiting. */
        REAL_WORK,
    }
}

/**
 * Deterministic/rule-based [UtteranceClassifier] MVP. See class-level kdoc on
 * [UtteranceClassifier] for the named simplification (no real fast-tier model
 * call in this environment).
 */
public class RuleBasedUtteranceClassifier(
    private val config: Config = Config(),
) : UtteranceClassifier {

    public data class Config(
        /** Utterances at or below this word count, with no action keyword, are TRIVIAL. */
        val trivialMaxWords: Int = 4,
        /**
         * Case-insensitive substrings that, if present, mark the utterance as
         * REAL_WORK regardless of length -- these name a concrete task/intent
         * for the slow tier to actually perform.
         */
        val actionKeywords: Set<String> = setOf(
            "schedule", "book", "cancel", "reschedule",
            "email", "send", "text", "message", "call",
            "remind", "reminder",
            "search", "research", "look up", "find out", "delegate",
            "create", "add", "delete", "remove", "update", "edit",
            "order", "buy", "purchase",
            "move", "reschedule", "postpone",
            "check my", "check the", "pull up",
        ),
    )

    override fun classify(utterance: String): UtteranceClassifier.Classification {
        val normalized = utterance.trim().lowercase()
        if (normalized.isBlank()) return UtteranceClassifier.Classification.TRIVIAL

        val hasActionKeyword = config.actionKeywords.any { normalized.contains(it) }
        if (hasActionKeyword) return UtteranceClassifier.Classification.REAL_WORK

        val wordCount = normalized.split(Regex("\\s+")).count { it.isNotBlank() }
        return if (wordCount <= config.trivialMaxWords) {
            UtteranceClassifier.Classification.TRIVIAL
        } else {
            // Longer utterances with no recognized action keyword: still
            // treated as TRIVIAL by default (see class kdoc -- safe failure
            // direction is "fast tier answers directly", not an unnecessary
            // slow-tier round trip).
            UtteranceClassifier.Classification.TRIVIAL
        }
    }
}
