package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ToolRegistryTest {

    private fun stubTool(toolName: String, result: String = "ok") = object : Tool {
        override val name = toolName
        override val description = "stub for $toolName"
        override suspend fun execute(args: Map<String, Any>) = result
    }

    @Test
    fun containsReturnsTrueForRegisteredTool() {
        val registry = ToolRegistry(listOf(stubTool("get_time")))
        assertThat(registry.contains("get_time")).isTrue()
    }

    @Test
    fun containsReturnsFalseForUnknownTool() {
        val registry = ToolRegistry(listOf(stubTool("get_time")))
        assertThat(registry.contains("unknown_tool")).isFalse()
    }

    @Test
    fun executeCallsCorrectTool() = runTest {
        val registry = ToolRegistry(listOf(stubTool("get_time", "2:00 PM")))
        val result = registry.execute("get_time", emptyMap())
        assertThat(result).isEqualTo("2:00 PM")
    }

    @Test
    fun executeThrowsForUnknownTool() = runTest {
        val registry = ToolRegistry(emptyList())
        var threw = false
        try { registry.execute("nonexistent", emptyMap()) }
        catch (_: IllegalArgumentException) { threw = true }
        assertThat(threw).isTrue()
    }

    @Test
    fun descriptionsListsAllTools() {
        val registry = ToolRegistry(listOf(
            stubTool("get_time"),
            stubTool("get_date"),
        ))
        val desc = registry.descriptions()
        assertThat(desc).contains("get_time")
        assertThat(desc).contains("get_date")
    }

    @Test
    fun emptyRegistryProducesEmptyDescriptions() {
        val registry = ToolRegistry(emptyList())
        assertThat(registry.descriptions()).isEmpty()
    }

    @Test
    fun allReturnsAllRegisteredTools() {
        val t1 = stubTool("a")
        val t2 = stubTool("b")
        val registry = ToolRegistry(listOf(t1, t2))
        assertThat(registry.all()).containsExactly(t1, t2).inOrder()
    }
}
