package com.vela.app.hooks

class VaultConfigHook : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 20

    override suspend fun execute(ctx: HookContext): HookResult {
        if (ctx.activeVaults.isEmpty()) return HookResult.Continue
        val block = buildString {
            appendLine("<lifeos-config>")
            appendLine("vaults:")
            ctx.activeVaults.forEach { vault ->
                appendLine("  - name: ${vault.name}")
                appendLine("    type: personal")
                appendLine("    location: ${vault.localPath}")
            }
            append("</lifeos-config>")
        }
        return HookResult.SystemPromptAddendum(block)
    }
}
