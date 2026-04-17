package com.vela.app.hooks

    import com.vela.app.data.db.VaultEntity

    interface Hook {
        val event: HookEvent
        val priority: Int get() = 0
        suspend fun execute(ctx: HookContext): HookResult
    }

    enum class HookEvent {
        // ── Session lifecycle ──────────────────────────────────────────────────
        SESSION_START,       // once per conversation, first turn only
        SESSION_END,
        VAULT_TOGGLED,
        AFTER_WRITE_FILE,

        // ── Turn lifecycle ─────────────────────────────────────────────────────
        TURN_START,          // start of every user turn (fires from InferenceEngine)
        TURN_END,            // end of every user turn

        // ── Agent loop (fires once per LLM call within a turn) ─────────────────
        PROVIDER_REQUEST,    // before each LLM call — returns can inject ephemeral context

        // ── Tool lifecycle ─────────────────────────────────────────────────────
        TOOL_PRE,            // before tool execution — Deny result blocks the call
        TOOL_POST,           // after tool execution — InjectContext result queued for next LLM call
    }

    /**
     * Context passed to every hook execution.
     *
     * [metadata] carries event-specific values:
     *   TOOL_PRE/POST:        "toolName", "toolArgsJson", "toolResult" (POST only)
     *   PROVIDER_REQUEST:     "recentToolNames" (List<String>), "currentTodos" (String)
     *   TURN_START/END:       "turnId"
     */
    data class HookContext(
        val conversationId: String,
        val activeVaults: List<VaultEntity>,
        val event: HookEvent,
        val metadata: Map<String, Any> = emptyMap(),
    )

    sealed class HookResult {
        /** No action — proceed normally. */
        object Continue : HookResult()

        /** Append text to the session system prompt (SESSION_START only). */
        data class SystemPromptAddendum(val text: String) : HookResult()

        /**
         * Inject ephemeral context before the next LLM call.
         * [ephemeral] = true → not stored in conversation history.
         * Valid on: PROVIDER_REQUEST, TOOL_POST.
         */
        data class InjectContext(
            val content: String,
            val ephemeral: Boolean = true,
        ) : HookResult()

        /**
         * Deny the current operation with a reason.
         * On TOOL_PRE: the tool call is skipped, reason returned as tool result.
         * On PROVIDER_REQUEST: the LLM call is replaced with the reason string.
         */
        data class Deny(val reason: String) : HookResult()

        /** Hook failed — log and continue. */
        data class Error(val message: String) : HookResult()
    }
    