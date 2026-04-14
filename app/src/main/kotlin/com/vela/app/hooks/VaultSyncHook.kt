package com.vela.app.hooks

import com.vela.app.vault.VaultSettings
import java.io.File

/**
 * Clones (if needed) and pulls every configured active vault at SESSION_START.
 *
 * [cloneIfNeeded] and [pull] are lambdas injected by AppModule:
 *   `cloneIfNeeded = { id, path -> gitSync.cloneIfNeeded(id, path) }`
 *   `pull          = { id, path -> gitSync.pull(id, path) }`
 * Injecting lambdas (not VaultGitSync directly) keeps the hook testable
 * without requiring a spy or open class — same pattern as Phase 1 BashTool.
 *
 * Failures from either lambda (network timeouts, auth errors, disk errors) are
 * caught and returned as [HookResult.Error] so a transient infrastructure issue
 * does not break the session. Same pattern as PersonalizationHook.
 */
class VaultSyncHook(
    private val cloneIfNeeded: suspend (vaultId: String, vaultPath: File) -> Unit,
    private val pull: suspend (vaultId: String, vaultPath: File) -> Unit,
    private val vaultSettings: VaultSettings,
) : Hook {
    override val event = HookEvent.SESSION_START
    override val priority = 0

    override suspend fun execute(ctx: HookContext): HookResult {
        ctx.activeVaults.forEach { vault ->
            if (vaultSettings.isConfiguredForSync(vault.id)) {
                val vaultPath = File(vault.localPath)
                runCatching {
                    cloneIfNeeded(vault.id, vaultPath)
                    pull(vault.id, vaultPath)
                }.onFailure { e ->
                    return HookResult.Error("Vault sync failed for ${vault.name}: ${e.message}")
                }
            }
        }
        return HookResult.Continue
    }
}
