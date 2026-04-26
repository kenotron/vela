package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.TurnEventEntity
import org.junit.Test

/**
 * RED → GREEN tests for [buildTurnItems].
 *
 * Verifies that completed `delegate` tool events produce [TurnItem.AgentResponse]
 * items after the [TurnItem.Tools] group that contains them.
 */
class TurnItemsTest {

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun toolEvent(
        id: String,
        seq: Int,
        toolName: String,
        toolStatus: String,
        toolArgs: String? = null,
        toolResult: String? = null,
    ) = TurnEventEntity(
        id         = id,
        turnId     = "turn1",
        seq        = seq,
        type       = "tool",
        toolName   = toolName,
        toolStatus = toolStatus,
        toolArgs   = toolArgs,
        toolResult = toolResult,
    )

    private fun textEvent(id: String, seq: Int, text: String) = TurnEventEntity(
        id     = id,
        turnId = "turn1",
        seq    = seq,
        type   = "text",
        text   = text,
    )

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun `completed delegate tool event produces AgentResponse item after Tools item`() {
        val events = listOf(
            toolEvent(
                id         = "ev1",
                seq        = 1,
                toolName   = "delegate",
                toolStatus = "done",
                toolArgs   = """{"agent":"explorer","prompt":"find files"}""",
                toolResult = "I found 3 files in the project.",
            ),
        )

        val items = buildTurnItems(events)

        assertThat(items).hasSize(2)
        assertThat(items[0]).isInstanceOf(TurnItem.Tools::class.java)
        assertThat(items[1]).isInstanceOf(TurnItem.AgentResponse::class.java)

        val resp = items[1] as TurnItem.AgentResponse
        assertThat(resp.agentName).isEqualTo("explorer")
        assertThat(resp.text).isEqualTo("I found 3 files in the project.")
    }

    @Test
    fun `running delegate tool event does NOT produce AgentResponse`() {
        val events = listOf(
            toolEvent(
                id         = "ev1",
                seq        = 1,
                toolName   = "delegate",
                toolStatus = "running",
                toolArgs   = """{"agent":"explorer","prompt":"find files"}""",
                toolResult = null,
            ),
        )

        val items = buildTurnItems(events)

        assertThat(items).hasSize(1)
        assertThat(items[0]).isInstanceOf(TurnItem.Tools::class.java)
    }

    @Test
    fun `non-delegate completed tool does NOT produce AgentResponse`() {
        val events = listOf(
            toolEvent(
                id         = "ev1",
                seq        = 1,
                toolName   = "bash",
                toolStatus = "done",
                toolArgs   = """{"command":"ls"}""",
                toolResult = "file1.txt\nfile2.txt",
            ),
        )

        val items = buildTurnItems(events)

        assertThat(items).hasSize(1)
        assertThat(items[0]).isInstanceOf(TurnItem.Tools::class.java)
    }

    @Test
    fun `agent name defaults to agent string when toolArgs is null`() {
        val events = listOf(
            toolEvent(
                id         = "ev1",
                seq        = 1,
                toolName   = "delegate",
                toolStatus = "done",
                toolArgs   = null,
                toolResult = "result text",
            ),
        )

        val items = buildTurnItems(events)

        assertThat(items).hasSize(2)
        val resp = items[1] as TurnItem.AgentResponse
        assertThat(resp.agentName).isEqualTo("agent")
    }

    @Test
    fun `agent name defaults to agent string when toolArgs has no agent field`() {
        val events = listOf(
            toolEvent(
                id         = "ev1",
                seq        = 1,
                toolName   = "delegate",
                toolStatus = "done",
                toolArgs   = """{"prompt":"do something"}""",
                toolResult = "result text",
            ),
        )

        val items = buildTurnItems(events)

        assertThat(items).hasSize(2)
        val resp = items[1] as TurnItem.AgentResponse
        assertThat(resp.agentName).isEqualTo("agent")
    }

    @Test
    fun `delegate tool with blank toolResult does NOT produce AgentResponse`() {
        val events = listOf(
            toolEvent(
                id         = "ev1",
                seq        = 1,
                toolName   = "delegate",
                toolStatus = "done",
                toolArgs   = """{"agent":"explorer"}""",
                toolResult = "",
            ),
        )

        val items = buildTurnItems(events)

        assertThat(items).hasSize(1)
        assertThat(items[0]).isInstanceOf(TurnItem.Tools::class.java)
    }

    @Test
    fun `tool group flushed by text event also emits AgentResponse before the text item`() {
        val events = listOf(
            toolEvent(
                id         = "ev1",
                seq        = 1,
                toolName   = "delegate",
                toolStatus = "done",
                toolArgs   = """{"agent":"planner"}""",
                toolResult = "Plan created.",
            ),
            textEvent(id = "ev2", seq = 2, text = "Here is the result."),
        )

        val items = buildTurnItems(events)

        // [Tools, AgentResponse, Text]
        assertThat(items).hasSize(3)
        assertThat(items[0]).isInstanceOf(TurnItem.Tools::class.java)
        assertThat(items[1]).isInstanceOf(TurnItem.AgentResponse::class.java)
        assertThat(items[2]).isInstanceOf(TurnItem.Text::class.java)

        val resp = items[1] as TurnItem.AgentResponse
        assertThat(resp.agentName).isEqualTo("planner")
        assertThat(resp.text).isEqualTo("Plan created.")
    }

    @Test
    fun `AgentResponse id is deterministic and contains source event id`() {
        val events = listOf(
            toolEvent(
                id         = "myevent42",
                seq        = 1,
                toolName   = "delegate",
                toolStatus = "done",
                toolArgs   = """{"agent":"explorer"}""",
                toolResult = "done",
            ),
        )

        val items = buildTurnItems(events)

        val resp = items[1] as TurnItem.AgentResponse
        assertThat(resp.id).contains("myevent42")
    }
}
