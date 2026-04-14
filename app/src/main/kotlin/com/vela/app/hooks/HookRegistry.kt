package com.vela.app.hooks

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HookRegistry @Inject constructor(
    private val hooks: @JvmSuppressWildcards List<Hook>
) {
    suspend fun fire(event: HookEvent, ctx: HookContext): List<HookResult> =
        hooks.filter { it.event == event }
             .sortedBy { it.priority }
             .map { it.execute(ctx) }

    suspend fun collectAddenda(event: HookEvent, ctx: HookContext): String =
        fire(event, ctx)
            .filterIsInstance<HookResult.SystemPromptAddendum>()
            .joinToString("\n\n") { it.text }
}
