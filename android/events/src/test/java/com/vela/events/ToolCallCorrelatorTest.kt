package com.vela.events

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Item-5 stress test: 5-way parallel delegation must attribute every
 * streaming chunk to the correct agent's tool call with zero
 * cross-assignment, applying the `eb207e74` claim-first-unclaimed lesson.
 */
class ToolCallCorrelatorTest {

    private fun started(agent: String, toolCallId: String, sessionId: String = "s1") = C2Event.ToolStarted(
        sessionId = sessionId,
        turnId = "t1",
        agentName = agent,
        toolCallId = toolCallId,
        name = "tool-$agent",
        args = null,
    )

    private fun completed(agent: String, toolCallId: String, sessionId: String = "s1") = C2Event.ToolCompleted(
        sessionId = sessionId,
        turnId = "t1",
        agentName = agent,
        toolCallId = toolCallId,
        name = "tool-$agent",
        result = null,
        durationMs = 5,
    )

    private fun progress(agent: String, message: String, sessionId: String = "s1") = C2Event.Progress(
        sessionId = sessionId,
        turnId = "t1",
        agentName = agent,
        message = message,
        percent = null,
    )

    private fun thinkingDelta(agent: String, text: String, sessionId: String = "s1") = C2Event.ThinkingDelta(
        sessionId = sessionId,
        turnId = "t1",
        agentName = agent,
        text = text,
    )

    @Test
    fun `five-way parallel delegation - zero cross-assignment across all agents`() {
        val correlator = ToolCallCorrelator()
        val agents = (1..5).map { "agent-$it" }
        // Not-sorted tool call ids and interleaved start order.
        val toolCallIds = mapOf(
            "agent-1" to "tc-1",
            "agent-2" to "tc-2",
            "agent-3" to "tc-3",
            "agent-4" to "tc-4",
            "agent-5" to "tc-5",
        )

        // Start all 5 in shuffled order (not sorted by agent name).
        val startOrder = listOf("agent-3", "agent-1", "agent-5", "agent-2", "agent-4")
        for (agent in startOrder) {
            correlator.accept(started(agent, toolCallIds.getValue(agent)))
        }

        // Interleave several progress/thinking chunks per agent, shuffled.
        val streamingEvents = listOf(
            progress("agent-2", "p2-a"),
            thinkingDelta("agent-4", "t4-a"),
            progress("agent-1", "p1-a"),
            thinkingDelta("agent-3", "t3-a"),
            progress("agent-5", "p5-a"),
            progress("agent-2", "p2-b"),
            thinkingDelta("agent-1", "t1-a"),
            progress("agent-4", "p4-a"),
            progress("agent-3", "p3-a"),
            thinkingDelta("agent-5", "t5-a"),
        )

        val attributedByAgent = mutableMapOf<String, MutableList<ToolCallCorrelator.AttributedActivity>>()
        for (event in streamingEvents) {
            val attributed = correlator.accept(event)
            attributedByAgent.getOrPut(attributed.agentName!!) { mutableListOf() }.add(attributed)
        }

        // Assert every attributed activity for each agent resolves to THAT
        // agent's toolCallId — zero cross-assignment across all 5 agents.
        for (agent in agents) {
            val expectedToolCallId = toolCallIds.getValue(agent)
            val activities = attributedByAgent[agent].orEmpty()
            assertEquals("expected streaming events recorded for $agent", 2, activities.size)
            for (activity in activities) {
                assertEquals(
                    "cross-assignment detected for $agent: got ${activity.toolCallId}, expected $expectedToolCallId",
                    expectedToolCallId,
                    activity.toolCallId,
                )
            }
        }

        // Complete all 5 in shuffled (not start) order; each completion must
        // still resolve to its own toolCallId.
        val completeOrder = listOf("agent-4", "agent-2", "agent-5", "agent-1", "agent-3")
        for (agent in completeOrder) {
            val attributed = correlator.accept(completed(agent, toolCallIds.getValue(agent)))
            assertEquals(toolCallIds.getValue(agent), attributed.toolCallId)
            assertEquals(agent, attributed.agentName)
        }
    }

    @Test
    fun `single-agent non-parallel case still attributes correctly`() {
        val correlator = ToolCallCorrelator()
        correlator.accept(started("agent-1", "tc-1"))
        val p = correlator.accept(progress("agent-1", "working"))
        assertEquals("tc-1", p.toolCallId)
        val c = correlator.accept(completed("agent-1", "tc-1"))
        assertEquals("tc-1", c.toolCallId)
    }

    @Test
    fun `progress for agent with no open tool call is unattributed, does not crash or misattribute`() {
        val correlator = ToolCallCorrelator()
        correlator.accept(started("agent-1", "tc-1"))

        // agent-2 has no open tool call at all.
        val attributed = correlator.accept(progress("agent-2", "orphan progress"))
        assertNull(attributed.toolCallId)
        assertEquals("agent-2", attributed.agentName)

        // agent-1's own progress must still resolve correctly afterward.
        val attributed2 = correlator.accept(progress("agent-1", "still going"))
        assertEquals("tc-1", attributed2.toolCallId)
    }

    @Test
    fun `top-level non-delegated progress with null agentName is unattributed when no open call`() {
        val correlator = ToolCallCorrelator()
        val attributed = correlator.accept(
            C2Event.Progress(sessionId = "s1", turnId = "t1", agentName = null, message = "top level", percent = null),
        )
        assertNull(attributed.toolCallId)
        assertNull(attributed.agentName)
    }

    @Test
    fun `multiple sequential tool calls for the same agent use FIFO ordering`() {
        val correlator = ToolCallCorrelator()
        correlator.accept(started("agent-1", "tc-a"))
        correlator.accept(started("agent-1", "tc-b"))

        // Oldest-open (tc-a) claims the progress chunk while both are open.
        val attributed = correlator.accept(progress("agent-1", "chunk"))
        assertEquals("tc-a", attributed.toolCallId)

        // Completing tc-a advances the FIFO so tc-b becomes oldest-open.
        correlator.accept(completed("agent-1", "tc-a"))
        val attributed2 = correlator.accept(progress("agent-1", "chunk2"))
        assertEquals("tc-b", attributed2.toolCallId)
    }
}
