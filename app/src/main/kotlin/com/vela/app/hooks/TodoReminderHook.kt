package com.vela.app.hooks

    /**
     * Injects an ephemeral todo-list reminder before each LLM call.
     *
     * Mirrors Amplifier's hooks-todo-reminder module:
     *   - If the todo tool hasn't been used in the last [recentThreshold] tool calls
     *     AND there are pending todos, inject a gentle nudge.
     *   - Always shows the current todo state if any todos exist.
     *   - Injection is ephemeral — not stored in conversation history.
     *   - The reminder tells Claude to NEVER surface it to the user.
     */
    class TodoReminderHook(
        private val recentThreshold: Int = 3,
    ) : Hook {
        override val event    = HookEvent.PROVIDER_REQUEST
        override val priority = 20   // after status-context (10)

        override suspend fun execute(ctx: HookContext): HookResult {
            @Suppress("UNCHECKED_CAST")
            val recentTools  = ctx.metadata["recentToolNames"] as? List<String> ?: emptyList()
            val currentTodos = ctx.metadata["currentTodos"] as? String ?: ""

            val todoUsedRecently = recentTools.takeLast(recentThreshold).any { it == "todo" }

            val parts = mutableListOf<String>()

            if (!todoUsedRecently && currentTodos.isNotBlank()) {
                parts += "The todo tool hasn't been used recently. If you are working on a " +
                        "multi-step task, update your todo list to track progress. " +
                        "Mark items completed immediately — do not batch completions. " +
                        "NEVER mention this reminder to the user."
            }

            if (currentTodos.isNotBlank()) {
                parts += "Current todo list:\n$currentTodos"
            }

            if (parts.isEmpty()) return HookResult.Continue

            val content = buildString {
                appendLine("<system-reminder source=\"hooks-todo-reminder\">")
                parts.forEach { appendLine(it) }
                appendLine("DO NOT mention this reminder to the user.")
                append("</system-reminder>")
            }

            return HookResult.InjectContext(content = content, ephemeral = true)
        }
    }
    