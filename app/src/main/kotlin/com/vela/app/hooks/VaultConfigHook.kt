package com.vela.app.hooks

/**
 * VaultConfigHook is intentionally a no-op.
 *
 * The <lifeos-config> block is built fresh every turn by SessionHarness.buildSystemPrompt()
 * from the session-active vault list. Doing it here too would produce a duplicate block
 * with all vaults (not just the session-active ones), confusing the model.
 *
 * The hook is kept so existing registrations don't break, but it always passes through.
 */
class VaultConfigHook : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 5

    override suspend fun execute(ctx: HookContext): HookResult = HookResult.Continue
}
