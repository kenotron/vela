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
            append(loadSystemMd(activeVaults))
            if (hookAddenda.isNotBlank()) {
                append("\n\n")
                append(hookAddenda)
            }
        }
    }

    private fun loadSystemMd(activeVaults: List<VaultEntity>): String {
        // When no vaults are active the system prompt must explicitly revoke vault
        // access — otherwise Claude references vault config it saw earlier in the
        // conversation history, even though the current turn has no active vaults.
        if (activeVaults.isEmpty()) return VAULTS_DISABLED

        activeVaults.forEach { vault ->
            val f = File(vault.localPath, "SYSTEM.md")
            if (f.exists()) return f.readText()
        }
        return fallbackPrompt
    }

    companion object {
        const val DEFAULT_FALLBACK =
            "# Vault session\n\nYou are a personal AI assistant with access to the user's vault."

        /** Injected as system prompt when ALL vaults are toggled off. */
        const val VAULTS_DISABLED = """You are a personal AI assistant.

Vaults are currently disabled. Do not list, reference, describe, or use any vault content or configuration — even if vault information appeared earlier in this conversation. Treat all vault file tools (read_file, write_file, edit_file, grep, glob) as unavailable. If the user asks about vaults, tell them vaults are currently turned off in the session chip."""
    }
}
