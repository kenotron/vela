package com.vela.app.harness

import com.vela.app.data.db.VaultEntity
import com.vela.app.hooks.HookContext
import com.vela.app.hooks.HookEvent
import com.vela.app.hooks.HookRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Assembles the composite system prompt for vault-mode conversations.
 *
 * [fallbackPrompt] is the content of assets/lifeos/SYSTEM.md, loaded by
 * AppModule at construction time — no Android Context needed here.
 */
class SessionHarness(
    private val hookRegistry: HookRegistry,
    private val fallbackPrompt: String = DEFAULT_FALLBACK,
) {
    private val initialized = ConcurrentHashMap.newKeySet<String>()

    fun isInitialized(conversationId: String): Boolean = conversationId in initialized

    suspend fun buildSystemPrompt(
        conversationId: String,
        activeVaults: List<VaultEntity>,
    ): String = withContext(Dispatchers.IO) {
        // Hooks (sync, index, personalization) fire ONCE per conversation on the first
        // turn — they have side-effects (git pull, indexing) that must not repeat.
        // SYSTEM.md is re-read every turn so vault toggling takes effect immediately
        // without the user having to start a new conversation.
        val hookAddenda = if (!isInitialized(conversationId)) {
            val hookCtx = HookContext(conversationId, activeVaults, HookEvent.SESSION_START)
            hookRegistry.collectAddenda(HookEvent.SESSION_START, hookCtx)
                .also { initialized.add(conversationId) }
        } else ""

        buildString {
            // A fresh <lifeos-config> block is prepended every turn so the model
            // always reads the current vault state from this turn's system prompt,
            // overriding any stale config it may have seen in conversation history.
            append(buildLifeosConfig(activeVaults))
            append("\n\n")
            append(loadSystemMd(activeVaults))
            if (hookAddenda.isNotBlank()) {
                append("\n\n")
                append(hookAddenda)
            }
        }
    }

    /**
     * Builds a <lifeos-config> block reflecting the currently active vaults.
     * Empty vaults list → vaults: [] which tells the model no vaults are available.
     */
    private fun buildLifeosConfig(activeVaults: List<VaultEntity>): String = buildString {
        appendLine("<lifeos-config>")
        if (activeVaults.isEmpty()) {
            appendLine("vaults: []")
        } else {
            appendLine("vaults:")
            activeVaults.forEach { vault ->
                appendLine("  - name: ${vault.name}")
                appendLine("    type: personal")
                appendLine("    location: ${vault.localPath}")
            }
        }
        append("</lifeos-config>")
    }

    /**
     * Loads the SYSTEM.md from the first active vault that has one, stripping
     * any existing <lifeos-config> block so the dynamically-generated one above
     * is always the authoritative source.
     */
    private fun loadSystemMd(activeVaults: List<VaultEntity>): String {
        val raw = activeVaults.mapNotNull { vault ->
            File(vault.localPath, "SYSTEM.md").takeIf { it.exists() }?.readText()
        }.firstOrNull() ?: fallbackPrompt

        // Remove any <lifeos-config>...</lifeos-config> block already in the file
        // so we don't end up with two conflicting config blocks.
        return raw.replace(Regex("<lifeos-config>[\\s\\S]*?</lifeos-config>\\n?"), "").trim()
    }

    companion object {
        val DEFAULT_FALLBACK = """
                You are a personal AI assistant. Use the vault configuration above to determine what files and vaults are available.

                ## Task tracking
                For any request that involves more than two steps, use the todo tool:
                - Create todos at the start with all planned steps
                - Mark each todo in_progress when you begin it
                - Mark it completed immediately when done — never batch completions
                - Always write a clear summary response after completing tool work

                ## Research workflow
                When asked to research a topic:
                1. Create todos for each research step (search queries, pages to read, synthesis)
                2. Use search_web to find relevant sources
                3. Use fetch_url to read the full content of promising pages
                4. Synthesize findings into a clear, structured response
            """.trimIndent()
    }
}
