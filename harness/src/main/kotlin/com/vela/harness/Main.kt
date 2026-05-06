package com.vela.harness

import com.vela.app.amplifierd.AmplifierdStreamClient
import com.vela.app.amplifierd.StreamEvent
import com.vela.app.streaming.SessionSseNormalizer
import com.vela.app.streaming.SessionState
import com.vela.app.ui.sessiondetail.ContentBlock
import com.vela.app.ui.sessiondetail.SessionStatus
import com.vela.app.ui.sessiondetail.TurnContent
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// ANSI colors
private const val RESET  = "\u001B[0m"
private const val BOLD   = "\u001B[1m"
private const val DIM    = "\u001B[2m"
private const val CYAN   = "\u001B[36m"
private const val GREEN  = "\u001B[32m"
private const val YELLOW = "\u001B[33m"
private const val PURPLE = "\u001B[35m"
private const val RED    = "\u001B[31m"

private val BASE  = System.getenv("VELA_HOST")  ?: "http://127.0.0.1:8410"
private val TOKEN = System.getenv("VELA_TOKEN") ?: "cjpOWhqUiF0hET9_Lj9qygN8P9JScIbU5EF3O3fFmIQ"

private val http = OkHttpClient.Builder()
    .connectTimeout(10, TimeUnit.SECONDS)
    .readTimeout(60, TimeUnit.SECONDS)   // session creation can be slow on cold bundle load
    .build()

private val JSON_MEDIA = "application/json".toMediaType()

private fun get(path: String): String {
    val req = Request.Builder().url("$BASE$path")
        .header("x-amplifier-token", TOKEN).get().build()
    return http.newCall(req).execute().use { it.body!!.string() }
}

private fun post(path: String, body: String): String {
    val req = Request.Builder().url("$BASE$path")
        .header("x-amplifier-token", TOKEN)
        .post(body.toRequestBody(JSON_MEDIA)).build()
    return http.newCall(req).execute().use { it.body!!.string() }
}

// ── Renderer ─────────────────────────────────────────────────────────────────

private fun clearScreen() = print("\u001B[H\u001B[2J")

private fun renderState(state: SessionState, eventLog: List<String>) {
    clearScreen()

    val statusDot = if (state.status == SessionStatus.EXECUTING) " ${YELLOW}●${RESET}" else ""
    println("${BOLD}${"═".repeat(60)}${RESET}")
    println("${BOLD}  Vela Harness${RESET}  ${DIM}session=${state.sessionId.take(8)}  ${state.status}${statusDot}${RESET}")
    println("${BOLD}${"═".repeat(60)}${RESET}")
    println()

    for (turn in state.turns) {
        if (turn.isUser) {
            println("${BOLD}${CYAN}[YOU]${RESET}  ${turn.text}")
        } else {
            val streamMark = if (turn.isStreaming) " ${YELLOW}●${RESET}" else ""
            println("${BOLD}${GREEN}[ASSISTANT]${RESET}$streamMark")
            if (turn.contentBlocks.isEmpty()) {
                if (turn.isStreaming) println("  ${DIM}thinking…${RESET}")
                else if (turn.text.isNotBlank()) println("  ${turn.text}")
            } else {
                for (block in turn.contentBlocks) {
                    when (block) {
                        is ContentBlock.Text -> {
                            if (block.markdown.isNotBlank()) println("  ${block.markdown}")
                        }
                        is ContentBlock.Thinking -> {
                            println("  ${DIM}[thinking] ${block.text.take(80)}…${RESET}")
                        }
                        is ContentBlock.ToolUse -> {
                            val icon = if (block.isRunning) "${YELLOW}●${RESET}" else "${GREEN}✓${RESET}"
                            val nameColor = if (block.name == "delegate") PURPLE else CYAN
                            println()
                            println("  ${nameColor}[${block.name}]${RESET}  ${DIM}${block.id.take(14)}${RESET}  $icon")
                            // Show abbreviated input
                            val inputPreview = try {
                                val j = JSONObject(block.inputJson)
                                when (block.name) {
                                    "bash"     -> "$ ${j.optString("command", "").take(80)}"
                                    "delegate" -> "→ ${j.optString("agent", "?")}  ${j.optString("instruction","").take(60)}"
                                    else       -> block.inputJson.take(80)
                                }
                            } catch (_: Exception) { block.inputJson.take(80) }
                            println("  ${DIM}  $inputPreview${RESET}")
                            if (block.streamingText.isNotEmpty()) {
                                println("  ${DIM}  ▸ ${block.streamingText.take(120)}${RESET}")
                            }
                        }
                        is ContentBlock.ToolResult -> {
                            val errMark = if (block.isError) " ${RED}[error]${RESET}" else ""
                            println("  ${DIM}  result$errMark: ${block.output.take(120)}${RESET}")
                        }
                        is ContentBlock.TodoProgress -> {
                            for (todo in block.todos) {
                                val mark = when (todo.status.name) {
                                    "COMPLETED"   -> "${GREEN}✓${RESET}"
                                    "IN_PROGRESS" -> "${YELLOW}●${RESET}"
                                    else          -> "○"
                                }
                                println("  $mark ${DIM}${todo.content}${RESET}")
                            }
                        }
                    }
                }
            }
        }
        println()
    }

    // Event log — last 12 events
    println("${DIM}${"─".repeat(60)}${RESET}")
    println("${DIM}Events:${RESET}")
    eventLog.takeLast(12).forEach { println("  ${DIM}$it${RESET}") }
}

// ── Main ─────────────────────────────────────────────────────────────────────

fun main(args: Array<String>): Unit = runBlocking {
    // Args come in via JVM system properties (set by build.gradle.kts from -Pprompt / -PsessionId)
    // to avoid Gradle word-splitting --args on spaces, which mistakes words like "bash" as tasks.
    // Read prompt from a temp file written by the harness shell script.
    // This avoids ALL shell/Gradle word-splitting issues with --args and -P flags.
    var sessionId: String? = System.getenv("VELA_SESSION_ID")?.takeIf { it.isNotBlank() }
        ?: System.getProperty("vela.sessionId")
    var prompt: String?    = System.getenv("VELA_PROMPT_FILE")
        ?.let { java.io.File(it).readText().trim().takeIf { s -> s.isNotEmpty() } }
        ?: System.getProperty("vela.prompt")

    // Fallback: if run directly via kotlinc or IDE, accept positional args
    if (prompt == null) {
        var i = 0
        while (i < args.size) {
            when {
                args[i] == "--session" && i + 1 < args.size -> { sessionId = args[i + 1]; i += 2 }
                else -> { prompt = args[i]; i++ }
            }
        }
    }

    if (prompt == null) {
        println("Usage: ./harness/harness [--session SESS_ID] \"your message\"")
        println("  VELA_HOST  = server URL (default http://127.0.0.1:8410)")
        println("  VELA_TOKEN = auth token")
        return@runBlocking
    }

    // Create or reuse session
    if (sessionId == null) {
        val resp = JSONObject(post("/sessions", """{"bundle_name":"vela"}"""))
        sessionId = resp.getString("session_id")
        System.err.println("Created session: $sessionId")
    }

    val normalizer = SessionSseNormalizer()
    var state = SessionState(
        sessionId             = sessionId!!,
        nodeId                = "",
        status                = SessionStatus.IDLE,
        turns                 = listOf(TurnContent(text = prompt!!, isUser = true)),
        activeTurnIndex       = null,
        pendingApproval       = null,
        lastUserMessage       = prompt,
        currentTodoActiveForm = null,
        projectName           = null,
    )
    val eventLog = mutableListOf<String>()
    renderState(state, eventLog)

    val streamClient = AmplifierdStreamClient(BASE, TOKEN)
    val done = CompletableDeferred<Unit>()

    // Collect stream in background
    launch(Dispatchers.IO) {
        try {
            streamClient.stream(sessionId!!, prompt!!).collect { event ->
                val label = when (event) {
                    is StreamEvent.Thinking       -> "execution:start       → new turn"
                    is StreamEvent.TextDelta      -> "content_block:delta   tok=${event.token.take(20).replace("\n","↵")}"
                    is StreamEvent.TextBlock      -> "content_block:end     [text] len=${event.text.length}"
                    is StreamEvent.ToolUse        -> "content_block:end     [tool_use] ${event.name} ${event.id.take(12)}"
                    is StreamEvent.ThinkingBlock  -> "content_block:end     [thinking]"
                    is StreamEvent.ToolResult     -> "tool:result           id=${event.toolCallId.take(12)} ${event.toolName}"
                    is StreamEvent.DelegateDelta  -> "content_block:delta   [child] tok=${event.token.take(20)}"
                    is StreamEvent.ProviderRetry  -> "${RED}provider:retry        attempt=${event.attempt}${RESET}"
                    is StreamEvent.ApprovalRequest-> "approval:request      ${event.question.take(40)}"
                    is StreamEvent.Named          -> "session:named         ${event.name}"
                    StreamEvent.Done              -> "${GREEN}orchestrator:complete ✓${RESET}"
                    is StreamEvent.Error          -> "${RED}error: ${event.message}${RESET}"
                }
                eventLog.add(label)
                state = normalizer.applyEvent(state, event)
                renderState(state, eventLog)

                if (event == StreamEvent.Done) done.complete(Unit)
            }
        } catch (e: Exception) {
            eventLog.add("${RED}stream error: ${e.message}${RESET}")
            renderState(state, eventLog)
            done.complete(Unit)
        }
    }

    done.await()

    println()
    println("${DIM}─────────────────────────────────${RESET}")
    println("${DIM}Session: $sessionId${RESET}")
    println("${DIM}Reuse:   ./harness --session $sessionId \"follow-up\"${RESET}")
}
