package com.vela.voice.narrator

import com.vela.events.C2Event
import com.vela.events.ToolCallCorrelator.AttributedActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

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

    /**
     * REAL wall-clock p99 measurement (not the correctness/ordering proxy above).
     *
     * The goal's item 6 explicitly authorizes measuring "against a simulated or
     * replayed event stream if a live long-running agent turn isn't available" --
     * this test exercises that authorization directly, rather than substituting
     * a non-timing proxy.
     *
     * CRITICAL pitfall avoided: `kotlinx.coroutines.test.runTest` uses a virtual-time
     * scheduler that auto-advances `delay()` calls instantly, which would make any
     * wall-clock measurement here meaningless (near-zero gaps regardless of the
     * simulated cadence). This test deliberately uses plain `kotlinx.coroutines.runBlocking`
     * (real dispatcher, real time) so `delay()` calls actually elapse real wall-clock
     * time and `System.nanoTime()` gaps are genuine.
     *
     * Simulated cadence rationale (mirrors realistic C2 traffic during an agent turn,
     * per design doc §4.4's fast/slow tier flow):
     *   - ToolStarted -> Progress: short gap (200-400ms) -- tool kicks off quickly.
     *   - Progress -> Progress: frequent small gaps (150-300ms) -- streaming updates.
     *   - Progress -> ToolCompleted: a longer "doing the work" gap (800-1500ms).
     *   - ToolCompleted -> next agent's ToolStarted: a "thinking" pause up to ~2s,
     *     representing the orchestrator deciding the next delegation.
     * None of these individual simulated gaps exceed ~2s, so a correctly-behaving
     * narration pipeline (adding negligible overhead of its own) should easily clear
     * the <5s p99 budget -- this test proves the PIPELINE does not add cumulative
     * delay on top of that realistic cadence; it does NOT prove real device/server
     * cadence in production stays under budget, since this environment cannot run a
     * live agent turn or a live TTS pipeline. That end-to-end proof remains a
     * residual for CI's KVM-backed / live-server runner.
     */
    @Test
    fun `real wall-clock narration inter-event gap p99 under simulated realistic cadence`() = runBlocking {
        val narrator = Narrator()
        val agents = (1..5).map { "agent-$it" }

        val timedFlow = flow {
            agents.forEach { agent ->
                emit(activity(agent, C2Event.ToolStarted("s1", "t1", agent, "tc-$agent", "work", null)))
                delay(250)
                repeat(3) { i ->
                    emit(activity(agent, C2Event.Progress("s1", "t1", agent, "$agent progress $i", null)))
                    delay(200)
                }
                delay(1000)
                emit(activity(agent, C2Event.ToolCompleted("s1", "t1", agent, "tc-$agent", "work", null, 1450)))
                delay(400) // pre-next-agent "thinking" pause (kept short to bound real test wall-clock runtime)
            }
        }

        val timestampsNanos = mutableListOf<Long>()
        narrator.narrate(timedFlow) { _ -> timestampsNanos.add(System.nanoTime()) }

        val gapsMs = timestampsNanos.zipWithNext { a, b -> (b - a) / 1_000_000.0 }
        assertTrue("expected narration events to have been captured", gapsMs.isNotEmpty())

        val sorted = gapsMs.sorted()
        val rank = ceil(0.99 * sorted.size).toInt().coerceIn(1, sorted.size) - 1
        val p99 = sorted[rank]

        assertTrue(
            "p99 inter-event gap was ${p99}ms across ${sorted.size} gaps (max=${sorted.last()}ms) -- " +
                "expected < 5000ms under this simulated realistic cadence",
            p99 < 5000.0,
        )
    }
}
