package com.vela.app.hooks

import com.vela.app.data.db.VaultEntity

interface Hook {
    val event: HookEvent
    val priority: Int get() = 0
    suspend fun execute(ctx: HookContext): HookResult
}

enum class HookEvent { SESSION_START, AFTER_WRITE_FILE, SESSION_END, VAULT_TOGGLED }

data class HookContext(
    val conversationId: String,
    val activeVaults: List<VaultEntity>,
    val event: HookEvent,
    val metadata: Map<String, Any> = emptyMap(),
)

sealed class HookResult {
    object Continue : HookResult()
    data class SystemPromptAddendum(val text: String) : HookResult()
    data class Error(val message: String) : HookResult()
}
