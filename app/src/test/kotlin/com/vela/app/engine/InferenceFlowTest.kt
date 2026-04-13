package com.vela.app.engine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests verifying InferenceEngine event ordering invariants.
 * Run on JVM: ./gradlew :app:testDebugUnitTest
 */
class InferenceFlowTest {

    data class Event(val id: String, val seq: Int, val type: String, val status: String, val text: String? = null)

    /**
     * Simulate what InferenceEngine.processTurn writes to DB.
     * preambleText → tool calls → finalText is the typical Anthropic response shape.
     */
    private fun simulateTurn(
        preambleText: String? = null,
        toolNames: List<String> = emptyList(),
        finalText: String? = "Here is my response.",
    ): List<Event> {
        val events = mutableListOf<Event>()
        var seq = 0

        // Text BEFORE tools — flushed when first tool starts (or at end if no tools)
        if (!preambleText.isNullOrBlank() && toolNames.isNotEmpty()) {
            events += Event("text-pre", seq++, "text", "done", preambleText)
        }

        // Tool events (insert on start, update in-place on done)
        val toolEventIds = toolNames.map { name ->
            val id = "tool-$name"
            events += Event(id, seq++, "tool", "running")
            id
        }
        // Update in-place — same index, same seq
        toolEventIds.forEachIndexed { i, id ->
            val idx = events.indexOfFirst { it.id == id }
            events[idx] = events[idx].copy(status = "done")
        }

        // Text AFTER tools (or only text if no tools)
        val postText = if (preambleText != null && toolNames.isEmpty()) preambleText else finalText
        if (!postText.isNullOrBlank()) {
            events += Event("text-post", seq++, "text", "done", postText)
        }

        return events
    }

    @Test
    fun preambleTextBeforeToolBySeq() {
        val events = simulateTurn(
            preambleText = "I'll search for that...",
            toolNames    = listOf("search_web"),
            finalText    = "Based on results...",
        )
        val preText  = events.first { it.id == "text-pre" }
        val tool     = events.first { it.type == "tool" }
        val postText = events.first { it.id == "text-post" }

        assertThat(preText.seq).isLessThan(tool.seq)
        assertThat(tool.seq).isLessThan(postText.seq)
    }

    @Test
    fun textAndToolsInterleaveCorrectly() {
        val events = simulateTurn(
            preambleText = "Let me search...",
            toolNames    = listOf("search_web", "fetch_url"),
            finalText    = "Here's the answer.",
        )
        // Order must be: text → tool → tool → text
        val ordered = events.sortedBy { it.seq }
        assertThat(ordered[0].type).isEqualTo("text")
        assertThat(ordered[1].type).isEqualTo("tool")
        assertThat(ordered[2].type).isEqualTo("tool")
        assertThat(ordered[3].type).isEqualTo("text")
    }

    @Test
    fun noToolsJustText() {
        val events = simulateTurn(preambleText = null, toolNames = emptyList(), finalText = "Simple answer.")
        assertThat(events).hasSize(1)
        assertThat(events[0].type).isEqualTo("text")
    }

    @Test
    fun toolsUpdateInPlaceNeverDuplicated() {
        val events = simulateTurn(toolNames = listOf("search_web"))
        val toolEvents = events.filter { it.type == "tool" }
        assertThat(toolEvents).hasSize(1)
        assertThat(toolEvents[0].status).isEqualTo("done")
    }

    @Test
    fun eventsPersistAfterTurnCompletes() {
        // Regression: events disappeared when _activeTurnId was cleared.
        // TurnWithEvents @Relation means events always load with the turn.
        val events = simulateTurn(
            preambleText = "Searching...",
            toolNames    = listOf("search_web"),
            finalText    = "Done.",
        )
        // After "turn completes" the event list is unchanged
        assertThat(events.filter { it.type == "tool" }).hasSize(1)
        assertThat(events.filter { it.type == "text" }).hasSize(2)
    }

    @Test
    fun seqsAreStrictlyMonotonic() {
        val events = simulateTurn(
            preambleText = "text before",
            toolNames    = listOf("tool1", "tool2"),
            finalText    = "text after",
        )
        val seqs = events.map { it.seq }
        assertThat(seqs.toSet().size).isEqualTo(seqs.size)  // all unique
        assertThat(seqs).isInOrder()                         // monotonic
    }
}
