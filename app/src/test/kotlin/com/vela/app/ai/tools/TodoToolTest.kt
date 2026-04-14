package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TodoToolTest {

    // —— create action ——————————————————————————————————————————————————————————————

    @Test
    fun create_withValidJson_returnsCountSummary() = runTest {
        val tool = TodoTool()
        val json = """[
            {"content": "Task 1", "status": "pending", "activeForm": "Creating"},
            {"content": "Task 2", "status": "completed", "activeForm": ""}
        ]"""

        val result = tool.execute(mapOf("action" to "create", "todos" to json))

        assertThat(result).contains("Todo list updated:")
        assertThat(result).contains("2 items")
        assertThat(result).contains("1 pending")
        assertThat(result).contains("1 completed")
    }

    @Test
    fun create_withInvalidJson_returnsErrorWithDetails() = runTest {
        val tool = TodoTool()
        val invalidJson = "{invalid json"

        val result = tool.execute(mapOf("action" to "create", "todos" to invalidJson))

        assertThat(result).startsWith("Error: todos must be a valid JSON array —")
        // Should contain error message details
        assertThat(result).contains("Error")
    }

    @Test
    fun create_withMissingTodosParam_returnsError() = runTest {
        val tool = TodoTool()

        val result = tool.execute(mapOf("action" to "create"))

        assertThat(result).isEqualTo("Error: todos is required for 'create'")
    }

    // —— update action ——————————————————————————————————————————————————————————————

    @Test
    fun update_replacesExistingList_notAppend() = runTest {
        val tool = TodoTool()
        val json1 = """[{"content": "Task 1", "status": "pending", "activeForm": ""}]"""
        val json2 = """[{"content": "Task 2", "status": "in_progress", "activeForm": ""}]"""

        // Create initial list
        tool.execute(mapOf("action" to "create", "todos" to json1))

        // Update replaces, not appends
        val result = tool.execute(mapOf("action" to "update", "todos" to json2))

        assertThat(result).contains("1 items")
        assertThat(result).contains("1 in progress")
        assertThat(result).contains("0 pending")

        // Verify via list
        val listResult = tool.execute(mapOf("action" to "list"))
        assertThat(listResult).contains("Task 2")
        assertThat(listResult).doesNotContain("Task 1")
    }

    // —— list action ——————————————————————————————————————————————————————————————

    @Test
    fun list_withEmptyState_returnsNoTodosMessage() = runTest {
        val tool = TodoTool()

        val result = tool.execute(mapOf("action" to "list"))

        assertThat(result).isEqualTo("(no todos)")
    }

    @Test
    fun list_showsCorrectIcons() = runTest {
        val tool = TodoTool()
        val json = """[
            {"content": "Pending task", "status": "pending", "activeForm": ""},
            {"content": "In progress task", "status": "in_progress", "activeForm": ""},
            {"content": "Completed task", "status": "completed", "activeForm": ""}
        ]"""

        tool.execute(mapOf("action" to "create", "todos" to json))
        val result = tool.execute(mapOf("action" to "list"))

        // Check for correct icons
        assertThat(result).contains("○ Pending task")
        assertThat(result).contains("→ In progress task")
        assertThat(result).contains("✓ Completed task")
    }

    // —— error handling ——————————————————————————————————————————————————————————————

    @Test
    fun execute_withUnknownAction_returnsError() = runTest {
        val tool = TodoTool()

        val result = tool.execute(mapOf("action" to "invalid"))

        assertThat(result).startsWith("Error: unknown action")
        assertThat(result).contains("'invalid'")
        assertThat(result).contains("create")
        assertThat(result).contains("update")
        assertThat(result).contains("list")
    }

    @Test
    fun execute_withMissingAction_returnsError() = runTest {
        val tool = TodoTool()

        val result = tool.execute(emptyMap())

        assertThat(result).isEqualTo("Error: action is required")
    }

    // —— state isolation ——————————————————————————————————————————————————————————————

    @Test
    fun execute_multipleInstances_doNotShareState() = runTest {
        val tool1 = TodoTool()
        val tool2 = TodoTool()
        val json1 = """[{"content": "Tool1 Task", "status": "pending", "activeForm": ""}]"""
        val json2 = """[{"content": "Tool2 Task", "status": "completed", "activeForm": ""}]"""

        tool1.execute(mapOf("action" to "create", "todos" to json1))
        tool2.execute(mapOf("action" to "create", "todos" to json2))

        val list1 = tool1.execute(mapOf("action" to "list"))
        val list2 = tool2.execute(mapOf("action" to "list"))

        assertThat(list1).contains("Tool1 Task")
        assertThat(list1).doesNotContain("Tool2 Task")
        assertThat(list2).contains("Tool2 Task")
        assertThat(list2).doesNotContain("Tool1 Task")
    }

    // —— edge cases ——————————————————————————————————————————————————————————————

    @Test
    fun create_withEmptyArray_returnsZeroCountSummary() = runTest {
        val tool = TodoTool()
        val json = "[]"

        val result = tool.execute(mapOf("action" to "create", "todos" to json))

        assertThat(result).contains("Todo list updated:")
        assertThat(result).contains("0 items")
    }

    @Test
    fun create_withMissingOptionalFields_usesDefaults() = runTest {
        val tool = TodoTool()
        // status and activeForm are optional
        val json = """[{"content": "Minimal Task"}]"""

        val result = tool.execute(mapOf("action" to "create", "todos" to json))

        assertThat(result).contains("1 items")
        assertThat(result).contains("1 pending") // default status

        val listResult = tool.execute(mapOf("action" to "list"))
        assertThat(listResult).contains("○ Minimal Task")
    }
}
