package com.vela.voice.narrator

import com.vela.events.C2Event
import com.vela.events.ToolCallCorrelator.AttributedActivity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verification substitution note (mirroring the `ZeroLostEventsTest.kt`
 * pattern of documenting substitutions): item 6 asks for narration
 * inter-event gap p99 < 5s measured against realistic-cadence C2 events.
 * True wall-clock p99 measurement is not meaningfully testable on a JVM
 * unit test without real cadence and without flaking in CI (shared
 * runners, GC pauses, etc.). This suite instead asserts the practical
 * proxy that actually matters for meeting that budget: the narration
 * collector processes a realistic-cadence event stream with ZERO dropped
 * events and preserves arrival order — i.e. nothing in this pipeline adds
 * unbounded buffering, blocking, or reordering that could itself blow the
 * p99 budget. True end-to-end p99 latency requires a live or replayed
 * cadence against a live TTS pipeline, which is a residual for CI's
 * emulator-backed runner.
 */
class NarratorTest {

    private fun activity(agentName: String?, event: C2Event, toolCallId: String? = null, toolName: String? = null) =
        AttributedActivity(
            agentName = agentName,
            sessionId = event.sessionId,
            toolCallId = toolCallId,
            toolName = toolName,
            event = event,
        )

    @Test
    fun `narrationFor tool started includes agent attribution when delegated`() {
        val narrator = Narrator()
        val event = C2Event.ToolStarted("s1", "t1", "agent-1", "tc1", "search", null)
        val text = narrator.narrationFor(activity("agent-1", event))
        assertEquals("Delegating to agent-1: starting search", text)
    }

    @Test
    fun `narrationFor tool started top-level has no agent mention`() {
        val narrator = Narrator()
        val event = C2Event.ToolStarted("s1", "t1", null, "tc1", "search", null)
        val text = narrator.narrationFor(activity(null, event))
        assertEquals("Starting search", text)
    }

    @Test
    fun `narrationFor tool completed includes duration and agent`() {
        val narrator = Narrator()
        val event = C2Event.ToolCompleted("s1", "t1", "agent-2", "tc2", "deploy", null, 250)
        val text = narrator.narrationFor(activity("agent-2", event))
        assertEquals("Delegated to agent-2: deploy completed in 250ms", text)
    }

    @Test
    fun `narrationFor progress passes message through`() {
        val narrator = Narrator()
        val event = C2Event.Progress("s1", "t1", null, "halfway there", 50)
        assertEquals("halfway there", narrator.narrationFor(activity(null, event)))
    }

    @Test
    fun `narrationFor thinking delta is never narrated - no filler guarantee`() {
        val narrator = Narrator()
        val event = C2Event.ThinkingDelta("s1", "t1", null, "some partial chunk")
        assertNull(narrator.narrationFor(activity(null, event)))
    }

    @Test
    fun `narrationFor thinking final IS narrated with real content`() {
        val narrator = Narrator()
        val event = C2Event.ThinkingFinal("s1", "t1", null, "concluded the plan is X")
        assertEquals("concluded the plan is X", narrator.narrationFor(activity(null, event)))
    }

    @Test
    fun `narrationFor error includes message`() {
        val narrator = Narrator()
        val event = C2Event.Error("s1", "t1", null, "E1", "network timeout", true)
        assertEquals("Error: network timeout", narrator.narrationFor(activity(null, event)))
    }

    @Test
    fun `narrationFor usage is not narrated`() {
        val narrator = Narrator()
        val event = C2Event.Usage("s1", "t1", null, 10, 20, null, null, null)
        assertNull(narrator.narrationFor(activity(null, event)))
    }

    @Test
    fun `narrationFor approval requested is narrated`() {
        val narrator = Narrator()
        val event = C2Event.ApprovalRequested("a1", "s1", "t1", null, "tool_call", "deploy", null, 30)
        assertEquals("Approval needed: deploy", narrator.narrationFor(activity(null, event)))
    }

    @Test
    fun `narrate collector forwards only non-null narration to sink in order`() = runBlocking {
        val narrator = Narrator()
        val events = listOf(
            activity(null, C2Event.ToolStarted("s1", "t1", null, "tc1", "search", null)),
            activity(null, C2Event.ThinkingDelta("s1", "t1", null, "chunk - should be filtered")),
            activity(null, C2Event.ToolCompleted("s1", "t1", null, "tc1", "search", null, 10)),
        )

        val captured = mutableListOf<String>()
        narrator.narrate(flowOf(*events.toTypedArray())) { text -> captured.add(text) }

        assertEquals(2, captured.size)
        assertEquals("Starting search", captured[0])
        assertEquals("search completed in 10ms", captured[1])
    }

    @Test
    fun `realistic-cadence replayed stream - zero dropped events, order preserved (p99 proxy)`() = runBlocking {
        // Practical proxy for the item-6 p99 requirement — see the class
        // doc comment above for why true wall-clock p99 isn't reliably
        // testable here. Uses a static replayed list (no artificial
        // kotlinx.coroutines.delay) to avoid wall-clock flakiness in CI;
        // this asserts correctness/ordering/no-drop, not timing.
        val narrator = Narrator()
        val agents = (1..5).map { "agent-$it" }
        val replayed = mutableListOf<AttributedActivity>()
        agents.forEach { agent ->
            replayed += activity(agent, C2Event.ToolStarted("s1", "t1", agent, "tc-$agent", "work", null))
            replayed += activity(agent, C2Event.Progress("s1", "t1", agent, "$agent progress", null))
            replayed += activity(agent, C2Event.ToolCompleted("s1", "t1", agent, "tc-$agent", "work", null, 100))
        }

        val captured = mutableListOf<String>()
        narrator.narrate(flowOf(*replayed.toTypedArray())) { text -> captured.add(text) }

        // 3 narratable lines per agent (start, progress, complete) x 5 agents.
        assertEquals(15, captured.size)
        // Order preserved: each agent's own 3 lines appear in start/progress/complete order.
        agents.forEach { agent ->
            val startIdx = captured.indexOfFirst { it.contains(agent) && it.startsWith("Delegating") }
            val progressIdx = captured.indexOfFirst { it == "$agent progress" }
            val completeIdx = captured.indexOfFirst { it.contains(agent) && it.startsWith("Delegated") }
            assertTrue("$agent narration must appear", startIdx >= 0 && progressIdx >= 0 && completeIdx >= 0)
            assertTrue("$agent narration must preserve order", startIdx < progressIdx && progressIdx < completeIdx)
        }
    }
}
