package com.vela.app.ui.sessiondetail

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the TodoProgress variant of ContentBlock, plus TodoItem and TodoStatus.
 * Written BEFORE implementation (TDD RED phase).
 */
class ContentBlockTodoTest {

    // ── TodoStatus ────────────────────────────────────────────────────────────

    @Test fun `TodoStatus has PENDING value`() {
        assertThat(TodoStatus.PENDING).isNotNull()
    }

    @Test fun `TodoStatus has IN_PROGRESS value`() {
        assertThat(TodoStatus.IN_PROGRESS).isNotNull()
    }

    @Test fun `TodoStatus has COMPLETED value`() {
        assertThat(TodoStatus.COMPLETED).isNotNull()
    }

    @Test fun `TodoStatus has exactly three values`() {
        assertThat(TodoStatus.values()).hasLength(3)
    }

    // ── TodoItem ──────────────────────────────────────────────────────────────

    @Test fun `TodoItem stores content`() {
        val item = TodoItem(content = "Write tests", status = TodoStatus.PENDING, activeForm = "Writing tests")
        assertThat(item.content).isEqualTo("Write tests")
    }

    @Test fun `TodoItem stores status`() {
        val item = TodoItem(content = "Run build", status = TodoStatus.IN_PROGRESS, activeForm = "Running build")
        assertThat(item.status).isEqualTo(TodoStatus.IN_PROGRESS)
    }

    @Test fun `TodoItem stores activeForm`() {
        val item = TodoItem(content = "Run tests", status = TodoStatus.IN_PROGRESS, activeForm = "Running tests")
        assertThat(item.activeForm).isEqualTo("Running tests")
    }

    @Test fun `TodoItem equality is structural`() {
        val a = TodoItem("x", TodoStatus.COMPLETED, "Completing x")
        val b = TodoItem("x", TodoStatus.COMPLETED, "Completing x")
        assertThat(a).isEqualTo(b)
    }

    // ── ContentBlock.TodoProgress ─────────────────────────────────────────────

    @Test fun `ContentBlock TodoProgress is a ContentBlock`() {
        val block: ContentBlock = ContentBlock.TodoProgress(todos = emptyList())
        assertThat(block).isInstanceOf(ContentBlock::class.java)
    }

    @Test fun `ContentBlock TodoProgress stores todos list`() {
        val todos = listOf(
            TodoItem("Write tests", TodoStatus.COMPLETED, "Writing tests"),
            TodoItem("Implement feature", TodoStatus.IN_PROGRESS, "Implementing feature"),
            TodoItem("Deploy", TodoStatus.PENDING, "Deploying"),
        )
        val block = ContentBlock.TodoProgress(todos = todos)
        assertThat(block.todos).hasSize(3)
    }

    @Test fun `ContentBlock TodoProgress todos preserves order`() {
        val todos = listOf(
            TodoItem("First", TodoStatus.PENDING, "Doing first"),
            TodoItem("Second", TodoStatus.PENDING, "Doing second"),
        )
        val block = ContentBlock.TodoProgress(todos = todos)
        assertThat(block.todos[0].content).isEqualTo("First")
        assertThat(block.todos[1].content).isEqualTo("Second")
    }

    @Test fun `ContentBlock TodoProgress can be empty`() {
        val block = ContentBlock.TodoProgress(todos = emptyList())
        assertThat(block.todos).isEmpty()
    }

    @Test fun `ContentBlock TodoProgress equality is structural`() {
        val todos = listOf(TodoItem("a", TodoStatus.PENDING, "Doing a"))
        val a = ContentBlock.TodoProgress(todos)
        val b = ContentBlock.TodoProgress(todos)
        assertThat(a).isEqualTo(b)
    }

    // ── Existing variants still work ──────────────────────────────────────────

    @Test fun `ContentBlock Text still exists`() {
        val block: ContentBlock = ContentBlock.Text("hello")
        assertThat(block).isInstanceOf(ContentBlock.Text::class.java)
    }

    @Test fun `ContentBlock Thinking still exists`() {
        val block: ContentBlock = ContentBlock.Thinking("musing")
        assertThat(block).isInstanceOf(ContentBlock.Thinking::class.java)
    }

    @Test fun `ContentBlock ToolUse still exists`() {
        val block: ContentBlock = ContentBlock.ToolUse("id1", "bash", "{}")
        assertThat(block).isInstanceOf(ContentBlock.ToolUse::class.java)
    }

    @Test fun `ContentBlock ToolResult still exists`() {
        val block: ContentBlock = ContentBlock.ToolResult("id1", "output")
        assertThat(block).isInstanceOf(ContentBlock.ToolResult::class.java)
    }
}
