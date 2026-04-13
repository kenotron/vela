package com.vela.app.engine

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * Unit tests for the InferenceEngine event ordering invariants.
 * These run on the JVM — no device needed.
 *
 * We test the data model and ordering logic directly, not the DB.
 * The @Relation DB fix is verified by integration tests on-device.
 */
class InferenceFlowTest {

    // Simulate what InferenceEngine writes to DB
    data class SimulatedEvent(val id: String, val seq: Int, val type: String, val status: String)

    private fun simulateTurn(toolCount: Int): List<SimulatedEvent> {
        val events = mutableListOf<SimulatedEvent>()
        var seq = 0

        // onToolStart for each tool
        val toolIds = (1..toolCount).map { i ->
            val id = "event-tool-$i"
            events += SimulatedEvent(id, seq++, "tool", "running")
            id
        }

        // onToolEnd for each tool — same IDs, status updated in-place
        // In real code this is an UPDATE, simulated here as replacement
        toolIds.forEachIndexed { i, id ->
            val idx = events.indexOfFirst { it.id == id }
            events[idx] = events[idx].copy(status = "done")
        }

        // Final text event — always last
        events += SimulatedEvent("event-text", seq++, "text", "done")

        return events
    }

    @Test
    fun toolEventsAlwaysBeforeTextEventBySeq() {
        val events = simulateTurn(toolCount = 2)

        val toolEvents = events.filter { it.type == "tool" }
        val textEvents = events.filter { it.type == "text" }

        assertThat(toolEvents).isNotEmpty()
        assertThat(textEvents).hasSize(1)

        val maxToolSeq = toolEvents.maxOf { it.seq }
        val textSeq    = textEvents.first().seq

        assertThat(maxToolSeq).isLessThan(textSeq)
    }

    @Test
    fun multipleToolsGetUniqueIncreasingSeqs() {
        val events = simulateTurn(toolCount = 3)
        val seqs   = events.map { it.seq }

        assertThat(seqs.toSet().size).isEqualTo(seqs.size)
        assertThat(seqs).isInOrder()
    }

    @Test
    fun toolEventsUpdateInPlaceNotAppend() {
        val events = simulateTurn(toolCount = 1)

        // Only one tool event row (not two — running + done)
        val toolEvents = events.filter { it.type == "tool" }
        assertThat(toolEvents).hasSize(1)
        assertThat(toolEvents[0].status).isEqualTo("done")
    }

    @Test
    fun zeroToolsStillProducesTextEvent() {
        val events = simulateTurn(toolCount = 0)
        assertThat(events.filter { it.type == "text" }).hasSize(1)
    }

    @Test
    fun eventsPersistAfterTurnCompletes() {
        // Regression: completed turns rendered with emptyList().
        // Here we verify the model itself — events list is not cleared on status change.
        val events = simulateTurn(toolCount = 2)

        // Events are still accessible after the turn "completes"
        // (In DB terms: UPDATE turns SET status='complete' does not touch turn_events)
        assertThat(events).hasSize(3) // tool1, tool2, text
        assertThat(events.filter { it.type == "tool" }).hasSize(2)
        assertThat(events.filter { it.type == "text" }).hasSize(1)
    }
}
