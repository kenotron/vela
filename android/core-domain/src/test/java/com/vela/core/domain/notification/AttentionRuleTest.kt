package com.vela.core.domain.notification

import com.vela.core.domain.LedgerRepository.LedgerEntry
import com.vela.core.domain.LedgerRepository.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Proves [AttentionRule] against the six concrete examples in
 * `docs/examples/attention-rule-examples.md` (#31, #49). Each test builds the
 * [LedgerEntry] that a real call site would produce for that example, derives
 * `requiresAttention` from [AttentionRule], and checks both:
 *
 * 1. The derived value matches the example's expected classification.
 * 2. [AttentionCandidate.from] behaves consistently with that derived value
 *    (non-null iff the example needs a decision).
 */
class AttentionRuleTest {

    private fun entryFor(signal: JobSignal, id: String, title: String, summary: String) = LedgerEntry(
        id = id,
        title = title,
        summary = summary,
        createdAtEpochMs = 1_000L,
        source = "test",
        status = Status.PENDING,
        requiresAttention = AttentionRule.requiresAttention(signal),
    )

    // -- Needs a decision (requiresAttention = true) --------------------

    @Test
    fun `example 1 - freshly dispatched job awaiting acknowledgement needs a decision`() {
        val entry = entryFor(
            JobSignal.ATTENTION_REQUIRED,
            id = "lane-31-49",
            title = "Lane dispatched: attention-rule-examples-tuning",
            summary = "Awaiting acknowledgement of dispatch to fleet.",
        )

        assertEquals(true, entry.requiresAttention)
        assertNotNull(AttentionCandidate.from(entry))
    }

    @Test
    fun `example 2 - failed job needing retry or abandon decision needs a decision`() {
        val entry = entryFor(
            JobSignal.FAILED,
            id = "lane-42",
            title = "Lane failed: gradle build",
            summary = "Compile error the agent could not resolve; retry, reassign, or abandon?",
        )

        assertEquals(true, entry.requiresAttention)
        assertNotNull(AttentionCandidate.from(entry))
    }

    @Test
    fun `example 3 - done but user-flagged residual needs a decision`() {
        val entry = entryFor(
            JobSignal.DONE_USER_FLAGGED,
            id = "lane-48",
            title = "Lane complete with residual",
            summary = "Done, but flagged: needs a human call on touching core-ui.",
        )

        assertEquals(true, entry.requiresAttention)
        assertNotNull(AttentionCandidate.from(entry))
    }

    // -- Just progress (requiresAttention = false) -----------------------

    @Test
    fun `example 4 - clean completion with nothing flagged is just progress`() {
        val entry = entryFor(
            JobSignal.DONE_CLEAN,
            id = "lane-35",
            title = "Lane complete",
            summary = "DONE.json verdict COMPLETE, no residuals.",
        )

        assertEquals(false, entry.requiresAttention)
        assertNull(AttentionCandidate.from(entry))
    }

    @Test
    fun `example 5 - routine step update with a result is just progress`() {
        val entry = entryFor(
            JobSignal.PROGRESS,
            id = "lane-31-49-step",
            title = "Tests run",
            summary = "./gradlew :core-domain:testDebugUnitTest -- 12/12 passed.",
        )

        assertEquals(false, entry.requiresAttention)
        assertNull(AttentionCandidate.from(entry))
    }

    @Test
    fun `example 6 - mid-flight heartbeat with no outcome yet is just progress`() {
        val entry = entryFor(
            JobSignal.PROGRESS,
            id = "lane-31-49-heartbeat",
            title = "Lane running",
            summary = "attention-rule-examples-tuning still running, no output yet.",
        )

        assertEquals(false, entry.requiresAttention)
        assertNull(AttentionCandidate.from(entry))
    }

    @Test
    fun `rule covers all JobSignal values with no ambiguous case`() {
        JobSignal.entries.forEach { signal ->
            // Must not throw -- when(signal) in AttentionRule is exhaustive.
            AttentionRule.requiresAttention(signal)
        }
    }
}
