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
        initialized.add(conversationId)
        val hookCtx = HookContext(conversationId, activeVaults, HookEvent.SESSION_START)
        val addenda = hookRegistry.collectAddenda(HookEvent.SESSION_START, hookCtx)
        buildString {
            append(loadSystemMd(activeVaults))
            if (addenda.isNotBlank()) {
                append("\n\n")
                append(addenda)
            }
        }
    }

    private fun loadSystemMd(activeVaults: List<VaultEntity>): String {
        activeVaults.forEach { vault ->
            val f = File(vault.localPath, "SYSTEM.md")
            if (f.exists()) return f.readText()
        }
        return fallbackPrompt
    }

    companion object {
        const val DEFAULT_FALLBACK =
            "# Vault session\n\nYou are a personal AI assistant with access to the user's vault."
    }
}
