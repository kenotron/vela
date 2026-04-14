package com.vela.app.hooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PersonalizationHook : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 15

    override suspend fun execute(ctx: HookContext): HookResult = withContext(Dispatchers.IO) {
        runCatching {
            buildString {
                appendLine("<vault_context>")
                ctx.activeVaults.forEach { vault ->
                    val dir = File(vault.localPath, "_personalization")
                    if (dir.exists() && dir.isDirectory) {
                        appendLine("## ${vault.name}")
                        dir.listFiles { f -> f.extension == "md" }
                            ?.sortedBy { it.name }
                            ?.forEach { file ->
                                appendLine("### ${file.nameWithoutExtension}")
                                appendLine(file.readText())
                                appendLine()
                            }
                    }
                }
                append("</vault_context>")
            }.trim()
        }.fold(
            onSuccess = { text ->
                if (text.isBlank()) HookResult.Continue
                else HookResult.SystemPromptAddendum(text)
            },
            onFailure = { e -> HookResult.Error("Failed to read personalization: ${e.message}") }
        )
    }
}
