package com.vela.app.hooks

    import javax.inject.Inject
    import javax.inject.Singleton

    @Singleton
    class HookRegistry @Inject constructor(
        private val hooks: @JvmSuppressWildcards List<Hook>
    ) {
        /** Fire all hooks for [event] in priority order. */
        suspend fun fire(event: HookEvent, ctx: HookContext): List<HookResult> =
            hooks.filter { it.event == event }
                 .sortedBy { it.priority }
                 .map { it.execute(ctx) }

        /** Collect all [HookResult.SystemPromptAddendum] results for SESSION_START hooks. */
        suspend fun collectAddenda(event: HookEvent, ctx: HookContext): String =
            fire(event, ctx)
                .filterIsInstance<HookResult.SystemPromptAddendum>()
                .joinToString("\n\n") { it.text }

        /**
         * Collect all [HookResult.InjectContext] results and combine into a single
         * injection string, or return null if no hooks produced an injection.
         *
         * Used for PROVIDER_REQUEST hooks — the combined string is sent to the Rust
         * orchestrator as an ephemeral message prepended to the next LLM call.
         */
        suspend fun collectEphemeralInjection(event: HookEvent, ctx: HookContext): String? {
            val injections = fire(event, ctx)
                .filterIsInstance<HookResult.InjectContext>()
                .filter { it.content.isNotBlank() }
            if (injections.isEmpty()) return null
            return injections.joinToString("\n\n") { it.content }
        }

        /**
         * Check if any TOOL_PRE hook denies execution of the tool.
         * Returns the deny reason string if denied, null if allowed.
         */
        suspend fun checkToolPre(ctx: HookContext): String? =
            fire(HookEvent.TOOL_PRE, ctx)
                .filterIsInstance<HookResult.Deny>()
                .firstOrNull()
                ?.reason
    }
    