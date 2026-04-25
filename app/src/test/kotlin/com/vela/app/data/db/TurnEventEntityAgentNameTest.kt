package com.vela.app.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies that [TurnEventEntity] carries an optional [agentName]
 * field used to tag text responses that arrived via a delegation sub-call.
 */
class TurnEventEntityAgentNameTest {

    @Test
    fun `agentName defaults to null for ordinary text events`() {
        val entity = TurnEventEntity(
            id     = "e1",
            turnId = "t1",
            seq    = 0,
            type   = "text",
            text   = "Hello",
        )
        assertThat(entity.agentName).isNull()
    }

    @Test
    fun `agentName can be set to a non-null agent name`() {
        val entity = TurnEventEntity(
            id        = "e2",
            turnId    = "t1",
            seq       = 1,
            type      = "text",
            text      = "Here is the summary",
            agentName = "explorer",
        )
        assertThat(entity.agentName).isEqualTo("explorer")
    }

    @Test
    fun `agentName is distinct from toolName`() {
        val entity = TurnEventEntity(
            id        = "e3",
            turnId    = "t1",
            seq       = 2,
            type      = "text",
            agentName = "writer",
        )
        assertThat(entity.agentName).isEqualTo("writer")
        assertThat(entity.toolName).isNull()
    }
}
