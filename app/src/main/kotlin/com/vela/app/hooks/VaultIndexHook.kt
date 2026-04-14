package com.vela.app.hooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Reads _index.md from each active vault and injects a <lifeos-index vault="name"> block.
 * Matches the desktop lifeos hook-lifeos module behavior exactly.
 */
class VaultIndexHook @Inject constructor() : Hook {
    override val event = HookEvent.SESSION_START
    override val priority = 10   // after config (5), before personalization (15)

    companion object {
        private const val INDEX_INSTRUCTION =
            "<!-- _index.md rule: structural map only, ≤60 lines (10s reading rule). " +
            "Update only when a new folder area is created — never for individual files. -->"
    }

    override suspend fun execute(ctx: HookContext): HookResult = withContext(Dispatchers.IO) {
        val blocks = buildString {
            ctx.activeVaults.forEach { vault ->
                val indexFile = File(vault.localPath, "_index.md")
                if (indexFile.exists()) {
                    try {
                        appendLine("<lifeos-index vault=\"${vault.name}\">")
                        appendLine(INDEX_INSTRUCTION)
                        appendLine(indexFile.readText())
                        append("</lifeos-index>")
                    } catch (_: Exception) { /* skip unreadable */ }
                }
            }
        }.trim()

        if (blocks.isBlank()) HookResult.Continue
        else HookResult.SystemPromptAddendum(blocks)
    }
}
