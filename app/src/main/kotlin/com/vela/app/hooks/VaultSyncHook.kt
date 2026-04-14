package com.vela.app.hooks

import com.vela.app.vault.VaultSettings
import java.io.File

/**
 * Pulls every configured active vault at SESSION_START.
 *
 * [pull] is a lambda injected by AppModule:
 *   `pull = { id, path -> gitSync.pull(id, path) }`
 * Injecting a lambda (not VaultGitSync directly) keeps the hook testable
 * without requiring a spy or open class — same pattern as Phase 1 BashTool.
 */
class VaultSyncHook(
    private val pull: suspend (vaultId: String, vaultPath: File) -> Unit,
    private val vaultSettings: VaultSettings,
) : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 0

    override suspend fun execute(ctx: HookContext): HookResult {
        ctx.activeVaults.forEach { vault ->
            if (vaultSettings.isConfiguredForSync(vault.id)) {
                pull(vault.id, File(vault.localPath))
            }
        }
        return HookResult.Continue
    }
}
