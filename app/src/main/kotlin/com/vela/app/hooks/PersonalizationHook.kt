package com.vela.app.hooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PersonalizationHook : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 10

    override suspend fun execute(ctx: HookContext): HookResult = withContext(Dispatchers.IO) {
        val text = buildString {
            ctx.activeVaults.forEach { vault ->
                val dir = File(vault.localPath, "_personalization")
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f -> f.extension == "md" }
                        ?.sortedBy { it.name }
                        ?.forEach { file ->
                            appendLine("## ${file.nameWithoutExtension}")
                            appendLine(file.readText())
                            appendLine()
                        }
                }
            }
        }.trim()
        if (text.isBlank()) HookResult.Continue
        else HookResult.SystemPromptAddendum(text)
    }
}
