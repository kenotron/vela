package com.vela.events

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class C2EventTest {

    @Test
    fun `parses tool started`() {
        val json = JSONObject(
            """{"type":"tool/started","sessionId":"s1","turnId":"t1","agentName":"agent-1",
                "toolCallId":"tc1","name":"search","args":{"q":"x"}}""",
        )
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.ToolStarted)
        event as C2Event.ToolStarted
        assertEquals("s1", event.sessionId)
        assertEquals("t1", event.turnId)
        assertEquals("agent-1", event.agentName)
        assertEquals("tc1", event.toolCallId)
        assertEquals("search", event.name)
        assertEquals("x", event.args?.getString("q"))
    }

    @Test
    fun `parses tool completed`() {
        val json = JSONObject(
            """{"type":"tool/completed","sessionId":"s1","turnId":"t1",
                "toolCallId":"tc1","name":"search","result":{"ok":true},"durationMs":120}""",
        )
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.ToolCompleted)
        event as C2Event.ToolCompleted
        assertNull(event.agentName)
        assertEquals(120L, event.durationMs)
        assertEquals(true, event.result?.getBoolean("ok"))
    }

    @Test
    fun `parses progress with percent`() {
        val json = JSONObject(
            """{"type":"progress","sessionId":"s1","turnId":"t1","message":"halfway","percent":50}""",
        )
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.Progress)
        event as C2Event.Progress
        assertEquals("halfway", event.message)
        assertEquals(50, event.percent)
    }

    @Test
    fun `parses progress without percent`() {
        val json = JSONObject("""{"type":"progress","sessionId":"s1","turnId":"t1","message":"working"}""")
        val event = C2Event.fromJson(json) as C2Event.Progress
        assertNull(event.percent)
    }

    @Test
    fun `parses thinking delta`() {
        val json = JSONObject("""{"type":"thinking/delta","sessionId":"s1","turnId":"t1","text":"chunk"}""")
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.ThinkingDelta)
        assertEquals("chunk", (event as C2Event.ThinkingDelta).text)
    }

    @Test
    fun `parses thinking final`() {
        val json = JSONObject("""{"type":"thinking/final","sessionId":"s1","turnId":"t1","text":"done thinking"}""")
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.ThinkingFinal)
        assertEquals("done thinking", (event as C2Event.ThinkingFinal).text)
    }

    @Test
    fun `parses usage`() {
        val json = JSONObject(
            """{"type":"usage","sessionId":"s1","turnId":"t1","inputTokens":10,"outputTokens":20,
                "cost":0.002,"model":"gpt","provider":"openai"}""",
        )
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.Usage)
        event as C2Event.Usage
        assertEquals(10L, event.inputTokens)
        assertEquals(20L, event.outputTokens)
        assertEquals(0.002, event.cost!!, 0.0001)
        assertEquals("gpt", event.model)
        assertEquals("openai", event.provider)
    }

    @Test
    fun `parses error`() {
        val json = JSONObject(
            """{"type":"error","sessionId":"s1","turnId":"t1","code":"E1","message":"boom","recoverable":true}""",
        )
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.Error)
        event as C2Event.Error
        assertEquals("E1", event.code)
        assertEquals("boom", event.message)
        assertTrue(event.recoverable)
    }

    @Test
    fun `parses approval requested`() {
        val json = JSONObject(
            """{"type":"approval/requested","approvalId":"a1","sessionId":"s1","turnId":"t1",
                "kind":"tool_call","toolName":"deploy","payload":{"env":"prod"},"timeoutSeconds":30}""",
        )
        val event = C2Event.fromJson(json)
        assertTrue(event is C2Event.ApprovalRequested)
        event as C2Event.ApprovalRequested
        assertEquals("a1", event.approvalId)
        assertEquals("deploy", event.toolName)
        assertEquals(30, event.timeoutSeconds)
        assertEquals("prod", event.payload?.getString("env"))
    }

    @Test
    fun `unknown type returns null without throwing`() {
        val json = JSONObject("""{"type":"future/event","sessionId":"s1","turnId":"t1"}""")
        val event = C2Event.fromJson(json)
        assertNull(event)
    }

    @Test
    fun `delegated session id format still parses agentName field independently`() {
        val json = JSONObject(
            """{"type":"progress","sessionId":"parent-child_agent-1","turnId":"t1",
                "agentName":"agent-1","message":"m"}""",
        )
        val event = C2Event.fromJson(json) as C2Event.Progress
        assertEquals("parent-child_agent-1", event.sessionId)
        assertEquals("agent-1", event.agentName)
    }
}
