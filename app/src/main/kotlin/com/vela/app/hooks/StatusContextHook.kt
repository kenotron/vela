package com.vela.app.hooks

    import java.text.SimpleDateFormat
    import java.util.Date
    import java.util.Locale

    /**
     * Injects a lightweight environment block at every PROVIDER_REQUEST.
     *
     * Mirrors Amplifier's hooks-status-context module: tells the LLM the current
     * date and active vault names so it doesn't need to ask or guess.
     *
     * Injection is ephemeral — not stored in conversation history.
     */
    class StatusContextHook : Hook {
        override val event    = HookEvent.PROVIDER_REQUEST
        override val priority = 10   // runs before todo-reminder (20)

        override suspend fun execute(ctx: HookContext): HookResult {
            val date  = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
            val vaults = ctx.activeVaults.joinToString(", ") { it.name }

            val content = buildString {
                appendLine("<system-reminder source=\"hooks-status-context\">")
                appendLine("<env>")
                appendLine("Today's date: $date")
                if (vaults.isNotBlank()) appendLine("Active vaults: $vaults")
                appendLine("</env>")
                append("</system-reminder>")
            }

            return HookResult.InjectContext(content = content, ephemeral = true)
        }
    }
    