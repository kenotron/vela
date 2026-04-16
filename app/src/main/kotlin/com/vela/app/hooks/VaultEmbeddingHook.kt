package com.vela.app.hooks

import com.vela.app.vault.EmbeddingEngine

/**
 * Kicks off background embedding for every active vault at session start.
 *
 * startIndexing() is cheap to call repeatedly — it exits in milliseconds if
 * nothing on disk changed since the last completed run. The actual embedding
 * work (ONNX inference) runs on EmbeddingEngine.engineScope (IO dispatcher)
 * so this hook returns immediately and never blocks the session.
 */
class VaultEmbeddingHook(
    private val embeddingEngine: EmbeddingEngine,
) : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 20   // after VaultSyncHook (0) so we index post-pull content

    override suspend fun execute(ctx: HookContext): HookResult {
        ctx.activeVaults.forEach { vault ->
            embeddingEngine.startIndexing(vault)   // fire-and-forget
        }
        return HookResult.Continue
    }
}
