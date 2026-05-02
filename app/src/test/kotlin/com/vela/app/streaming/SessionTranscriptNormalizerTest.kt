package com.vela.app.streaming

import com.google.common.truth.Truth.assertThat
import com.vela.app.ui.sessiondetail.ContentBlock
import com.vela.app.ui.sessiondetail.TodoStatus
import com.vela.app.ui.sessiondetail.TurnContent
import org.junit.Test

/**
 * Tests for [SessionTranscriptNormalizer].
 *
 * Written BEFORE implementation (TDD RED phase).
 * Verifies transcript JSON → List<TurnContent> conversion including:
 * - empty/malformed input → emptyList
 * - user message parsing
 * - assistant text, thinking, tool_call content blocks
 * - tool result collection
 * - todo tool calls → TodoProgress
 */
class SessionTranscriptNormalizerTest {

    private val normalizer = SessionTranscriptNormalizer()

    // ── Error / edge cases ──────────────────────────────────────────────────

    @Test fun `normalize returns emptyList on blank string`() {
        assertThat(normalizer.normalize("")).isEmpty()
    }

    @Test fun `normalize returns emptyList on malformed JSON`() {
        assertThat(normalizer.normalize("{not valid json}")).isEmpty()
    }

    @Test fun `normalize returns emptyList on JSON without messages key`() {
        assertThat(normalizer.normalize("""{"other":"value"}""")).isEmpty()
    }

    @Test fun `normalize returns emptyList on empty messages array`() {
        val json = """{"messages":[]}"""
        assertThat(normalizer.normalize(json)).isEmpty()
    }

    // ── User message ─────────────────────────────────────────────────────────

    @Test fun `normalize parses user message with string content`() {
        val json = """
            {
              "messages": [
                {"role":"user","content":"Hello world"}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        assertThat(turns[0].isUser).isTrue()
        assertThat(turns[0].text).isEqualTo("Hello world")
    }

    @Test fun `normalize skips user message with blank content`() {
        val json = """
            {
              "messages": [
                {"role":"user","content":"   "}
              ]
            }
        """.trimIndent()
        assertThat(normalizer.normalize(json)).isEmpty()
    }

    @Test fun `normalize parses multiple user messages`() {
        val json = """
            {
              "messages": [
                {"role":"user","content":"First"},
                {"role":"user","content":"Second"}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(2)
        assertThat(turns[0].text).isEqualTo("First")
        assertThat(turns[1].text).isEqualTo("Second")
    }

    // ── Assistant — string content ───────────────────────────────────────────

    @Test fun `normalize parses assistant message with plain string content`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":"Here is my answer"}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        assertThat(turns[0].isUser).isFalse()
        assertThat(turns[0].text).isEqualTo("Here is my answer")
    }

    @Test fun `normalize skips assistant turn with blank string content and no blocks`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":"  "}
              ]
            }
        """.trimIndent()
        assertThat(normalizer.normalize(json)).isEmpty()
    }

    // ── Assistant — JSONArray content blocks ─────────────────────────────────

    @Test fun `normalize parses assistant text block`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[{"type":"text","text":"My response"}]}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        val turn = turns[0]
        assertThat(turn.isUser).isFalse()
        assertThat(turn.contentBlocks).hasSize(1)
        assertThat(turn.contentBlocks[0]).isInstanceOf(ContentBlock.Text::class.java)
        val textBlock = turn.contentBlocks[0] as ContentBlock.Text
        assertThat(textBlock.markdown).isEqualTo("My response")
    }

    @Test fun `normalize captures plainText from first text block`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"text","text":"First text"},
                  {"type":"text","text":"Second text"}
                ]}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns[0].text).isEqualTo("First text")
    }

    @Test fun `normalize parses assistant thinking block`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[{"type":"thinking","thinking":"Internal thoughts"}]}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        val block = turns[0].contentBlocks[0]
        assertThat(block).isInstanceOf(ContentBlock.Thinking::class.java)
        assertThat((block as ContentBlock.Thinking).text).isEqualTo("Internal thoughts")
    }

    @Test fun `normalize parses assistant tool_call block as ToolUse`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_call","id":"call-1","name":"bash","input":{"command":"ls"}}
                ]}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        val block = turns[0].contentBlocks[0]
        assertThat(block).isInstanceOf(ContentBlock.ToolUse::class.java)
        val toolUse = block as ContentBlock.ToolUse
        assertThat(toolUse.id).isEqualTo("call-1")
        assertThat(toolUse.name).isEqualTo("bash")
        assertThat(toolUse.isRunning).isFalse()
    }

    @Test fun `normalize parses assistant tool_use block as ToolUse`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_use","id":"use-1","name":"bash","input":{"command":"pwd"}}
                ]}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        val block = turns[0].contentBlocks[0]
        assertThat(block).isInstanceOf(ContentBlock.ToolUse::class.java)
        assertThat((block as ContentBlock.ToolUse).name).isEqualTo("bash")
    }

    // ── Tool results ─────────────────────────────────────────────────────────

    @Test fun `normalize collects tool result message after assistant turn`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_call","id":"call-1","name":"bash","input":{}}
                ]},
                {"role":"tool","content":"hello from bash"}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        val blocks = turns[0].contentBlocks
        assertThat(blocks).hasSize(2)
        val result = blocks[1]
        assertThat(result).isInstanceOf(ContentBlock.ToolResult::class.java)
        val toolResult = result as ContentBlock.ToolResult
        assertThat(toolResult.toolUseId).isEqualTo("call-1")
        assertThat(toolResult.output).isEqualTo("hello from bash")
        assertThat(toolResult.isError).isFalse()
    }

    @Test fun `normalize handles tool result with is_error flag`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_call","id":"err-1","name":"bash","input":{}}
                ]},
                {"role":"tool","content":"error occurred","is_error":true}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        val blocks = turns[0].contentBlocks
        val toolResult = blocks[1] as ContentBlock.ToolResult
        assertThat(toolResult.isError).isTrue()
    }

    @Test fun `normalize handles tool result with JSONArray content joined by newline`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_call","id":"arr-1","name":"bash","input":{}}
                ]},
                {"role":"tool","content":[
                  {"type":"text","text":"line one"},
                  {"type":"text","text":"line two"}
                ]}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        val blocks = turns[0].contentBlocks
        val toolResult = blocks[1] as ContentBlock.ToolResult
        assertThat(toolResult.output).isEqualTo("line one\nline two")
    }

    @Test fun `normalize pairs multiple tool calls with tool results in order`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_call","id":"id-1","name":"bash","input":{}},
                  {"type":"tool_call","id":"id-2","name":"read_file","input":{}}
                ]},
                {"role":"tool","content":"output 1"},
                {"role":"tool","content":"output 2"}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        val blocks = turns[0].contentBlocks
        // 2 ToolUse + 2 ToolResult = 4
        assertThat(blocks).hasSize(4)
        val result1 = blocks[2] as ContentBlock.ToolResult
        val result2 = blocks[3] as ContentBlock.ToolResult
        assertThat(result1.toolUseId).isEqualTo("id-1")
        assertThat(result1.output).isEqualTo("output 1")
        assertThat(result2.toolUseId).isEqualTo("id-2")
        assertThat(result2.output).isEqualTo("output 2")
    }

    @Test fun `normalize does not include consumed tool messages as separate turns`() {
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_call","id":"call-1","name":"bash","input":{}}
                ]},
                {"role":"tool","content":"result"},
                {"role":"user","content":"Thanks"}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        // Should be: 1 assistant turn + 1 user turn (not 2 assistant + 1 user)
        assertThat(turns).hasSize(2)
        assertThat(turns[0].isUser).isFalse()
        assertThat(turns[1].isUser).isTrue()
    }

    // ── Todo tool call → TodoProgress ────────────────────────────────────────

    @Test fun `normalize converts todo tool_call to TodoProgress block`() {
        val todoInput = """{"todos":[
            {"content":"Write tests","status":"pending","activeForm":"Writing tests"},
            {"content":"Implement","status":"in_progress","activeForm":"Implementing"},
            {"content":"Deploy","status":"completed","activeForm":"Deploying"}
        ]}"""
        val json = """
            {
              "messages": [
                {"role":"assistant","content":[
                  {"type":"tool_call","id":"todo-1","name":"todo","input":$todoInput}
                ]}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(1)
        val block = turns[0].contentBlocks[0]
        assertThat(block).isInstanceOf(ContentBlock.TodoProgress::class.java)
        val progress = block as ContentBlock.TodoProgress
        assertThat(progress.todos).hasSize(3)
        assertThat(progress.todos[0].status).isEqualTo(TodoStatus.PENDING)
        assertThat(progress.todos[1].status).isEqualTo(TodoStatus.IN_PROGRESS)
        assertThat(progress.todos[2].status).isEqualTo(TodoStatus.COMPLETED)
    }

    // ── parseTodoBlock ────────────────────────────────────────────────────────

    @Test fun `parseTodoBlock maps status strings correctly`() {
        val inputJson = """{"todos":[
            {"content":"A","status":"pending","activeForm":"Doing A"},
            {"content":"B","status":"in_progress","activeForm":"Doing B"},
            {"content":"C","status":"completed","activeForm":"Doing C"}
        ]}"""
        val block = normalizer.parseTodoBlock(inputJson)
        assertThat(block.todos[0].status).isEqualTo(TodoStatus.PENDING)
        assertThat(block.todos[1].status).isEqualTo(TodoStatus.IN_PROGRESS)
        assertThat(block.todos[2].status).isEqualTo(TodoStatus.COMPLETED)
    }

    @Test fun `parseTodoBlock maps unknown status to PENDING`() {
        val inputJson = """{"todos":[
            {"content":"X","status":"unknown","activeForm":"Doing X"}
        ]}"""
        val block = normalizer.parseTodoBlock(inputJson)
        assertThat(block.todos[0].status).isEqualTo(TodoStatus.PENDING)
    }

    @Test fun `parseTodoBlock reads activeForm field`() {
        val inputJson = """{"todos":[
            {"content":"Run tests","status":"in_progress","activeForm":"Running tests"}
        ]}"""
        val block = normalizer.parseTodoBlock(inputJson)
        assertThat(block.todos[0].activeForm).isEqualTo("Running tests")
    }

    @Test fun `parseTodoBlock falls back to active_form when activeForm absent`() {
        val inputJson = """{"todos":[
            {"content":"Run tests","status":"in_progress","active_form":"Running tests"}
        ]}"""
        val block = normalizer.parseTodoBlock(inputJson)
        assertThat(block.todos[0].activeForm).isEqualTo("Running tests")
    }

    @Test fun `parseTodoBlock returns emptyList on invalid JSON`() {
        val block = normalizer.parseTodoBlock("not json")
        assertThat(block.todos).isEmpty()
    }

    @Test fun `parseTodoBlock returns emptyList on missing todos key`() {
        val block = normalizer.parseTodoBlock("{}")
        assertThat(block.todos).isEmpty()
    }

    @Test fun `parseTodoBlock stores todo content`() {
        val inputJson = """{"todos":[
            {"content":"Write failing test","status":"pending","activeForm":"Writing failing test"}
        ]}"""
        val block = normalizer.parseTodoBlock(inputJson)
        assertThat(block.todos[0].content).isEqualTo("Write failing test")
    }

    // ── Mixed turn sequences ─────────────────────────────────────────────────

    @Test fun `normalize handles user + assistant + user sequence`() {
        val json = """
            {
              "messages": [
                {"role":"user","content":"Hello"},
                {"role":"assistant","content":"Hi there"},
                {"role":"user","content":"Goodbye"}
              ]
            }
        """.trimIndent()
        val turns = normalizer.normalize(json)
        assertThat(turns).hasSize(3)
        assertThat(turns[0].isUser).isTrue()
        assertThat(turns[1].isUser).isFalse()
        assertThat(turns[2].isUser).isTrue()
    }

    // ── Source file structural checks ─────────────────────────────────────────

    @Test fun `source file exists at correct path`() {
        val file = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionTranscriptNormalizer.kt"
        )
        assertThat(file.exists()).isTrue()
    }

    @Test fun `source file declares Singleton annotation`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionTranscriptNormalizer.kt"
        ).readText()
        assertThat(src).contains("@Singleton")
    }

    @Test fun `source file declares Inject constructor`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionTranscriptNormalizer.kt"
        ).readText()
        assertThat(src).contains("@Inject constructor")
    }

    @Test fun `source file contains normalize fun`() {
        val src = java.io.File(
            "src/main/kotlin/com/vela/app/streaming/SessionTranscriptNormalizer.kt"
        ).readText()
        assertThat(src).contains("fun normalize(transcriptJson: String): List<TurnContent>")
    }
}
