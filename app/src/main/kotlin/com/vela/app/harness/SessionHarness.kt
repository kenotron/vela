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
 * Assembles the system prompt for vault-mode conversations.
 *
 * Deliberately thin — all generic context (date, env, git, delegation
 * instructions) is now handled by Rust hooks injected as SystemPromptAddenda:
 *
 *   - amplifier-module-hooks-status-context  → <env> block + git snapshot
 *   - amplifier-context-foundation           → delegation philosophy + agent list
 *   - amplifier-module-tool-delegate spec    → dynamic agent catalogue
 *
 * This file only handles Vela-specific content:
 *   1. <lifeos-config> — which vaults are active this turn
 *   2. SYSTEM.md       — user's own vault instructions (re-read every turn)
 *   3. SESSION_START Kotlin hooks — one-time side-effects (git pull, index)
 */
class SessionHarness(
    private val hookRegistry: HookRegistry,
    private val fallback: String = "",
) {
    private val initialized = ConcurrentHashMap.newKeySet<String>()

    fun isInitialized(conversationId: String): Boolean = conversationId in initialized

    suspend fun buildSystemPrompt(
        conversationId: String,
        activeVaults: List<VaultEntity>,
    ): String = withContext(Dispatchers.IO) {
        // SESSION_START hooks fire ONCE per conversation (git pull, index, etc.)
        val hookAddenda = if (!isInitialized(conversationId)) {
            val hookCtx = HookContext(conversationId, activeVaults, HookEvent.SESSION_START)
            hookRegistry.collectAddenda(HookEvent.SESSION_START, hookCtx)
                .also { initialized.add(conversationId) }
        } else ""

        buildString {
            // 1. Vault configuration — always fresh so vault toggling takes effect immediately.
            append(buildLifeosConfig(activeVaults))

            // 2. User's SYSTEM.md — their own instructions for this vault.
            //    Falls back to the constructor-supplied fallback string if not found.
            val systemMd = loadSystemMd(activeVaults).ifBlank { fallback }
            if (systemMd.isNotBlank()) {
                append("\n\n")
                append(systemMd)
            }

            // 3. Any one-time Kotlin hook addenda (git pull notice, index summary, etc.)
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
     * Loads SYSTEM.md from the first active vault that has one.
     * Returns blank if none found — Rust hooks cover the gap.
     * Strips any stale <lifeos-config> block so the fresh one above is authoritative.
     */
    private fun loadSystemMd(activeVaults: List<VaultEntity>): String {
        val raw = activeVaults.mapNotNull { vault ->
            File(vault.localPath, "SYSTEM.md").takeIf { it.exists() }?.readText()
        }.firstOrNull() ?: return ""

        return raw.replace(Regex("<lifeos-config>[\\s\\S]*?</lifeos-config>\\n?"), "").trim()
    }
}
