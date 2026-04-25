package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN tests for [buildAgentScopedInput].
 *
 * The helper lives in ConversationScreen.kt so it can be called from the
 * Composable send-path as well as unit-tested without Android dependencies.
 */
class AgentStateTest {

    @Test
    fun `null agent returns input unchanged`() {
        val result = buildAgentScopedInput(null, "hello")
        assertThat(result).isEqualTo("hello")
    }

    @Test
    fun `non-null agent wraps input with delegate directive`() {
        val result = buildAgentScopedInput("explorer", "find the README")
        assertThat(result).contains("delegate")
        assertThat(result).contains("agent=\"explorer\"")
        assertThat(result).contains("find the README")
    }

    @Test
    fun `agent name with special chars is preserved verbatim`() {
        val result = buildAgentScopedInput("foundation:explorer", "go")
        assertThat(result).contains("foundation:explorer")
    }

    @Test
    fun `empty user input still produces a directive when agent set`() {
        val result = buildAgentScopedInput("explorer", "")
        assertThat(result).contains("delegate")
        assertThat(result).contains("explorer")
    }
}
