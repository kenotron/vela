package com.vela.app.ai.tools

import android.content.Context
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Executes a shell script in one shot, returning only the final stdout.
 *
 * This is "code mode" for the agentic loop: instead of the LLM making N individual
 * tool calls (each result consuming context tokens), the LLM writes a single script
 * that performs all the operations locally. Intermediate results never flow back
 * through Claude — only the script's final stdout does.
 *
 * The script runs via [ProcessBuilder] as Vela's own UID, giving it full read/write
 * access to the vault filesystem without cross-app sandbox issues.
 *
 * Environment variables injected for every script:
 *   VAULT  — absolute path to the active vault (may be empty if none configured)
 *   TMPDIR — writable scratch space (app cache dir)
 *   HOME   — app files directory
 *
 * Available commands (Android toybox): cat, grep, find, awk, sed, sort, wc, head,
 * tail, cut, tr, echo, printf, cp, mv, rm, mkdir, chmod, stat, date, ls, ps, id.
 * Pipes, redirects, for/while loops, if/else, and here-docs all work.
 */
class RunScriptTool(
    private val context: Context,
    private val vaultRegistry: VaultRegistry,
) : Tool {

    override val name        = "run_script"
    override val displayName = "Run Script"
    override val icon        = "⚙️"
    override val description = """
        Execute a shell script that performs multiple operations in one call.
        Use this instead of many individual tool calls when you need to:
          • Read and process multiple files
          • Search across the vault and aggregate results
          • Transform, filter, or count content
          • Chain operations with pipes

        Environment:
          VAULT  = path to the active vault (use ${'$'}VAULT in your script)
          TMPDIR = writable scratch space

        Shell: /system/bin/sh (POSIX-compatible, Android toybox)
        Commands: cat, grep, find, awk, sed, sort, wc, head, tail, cut, tr,
                  cp, mv, rm, mkdir, echo, printf, stat, date, ls — and pipes/loops.

        Output: script stdout. Timeout: 30s.
    """.trimIndent()

    override val parameters = listOf(
        ToolParameter(
            name        = "script",
            type        = "string",
            description = "Shell script to execute. Use \$VAULT for the vault path.",
        ),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val script = args["script"] as? String
            ?: return@withContext "Error: 'script' is required"

        val vaultPath = vaultRegistry.getEnabledVaults().firstOrNull()?.localPath ?: ""
        val tmpScript = File.createTempFile("vela_script_", ".sh", context.cacheDir)

        try {
            tmpScript.writeText("#!/system/bin/sh\n$script")

            val process = ProcessBuilder("/system/bin/sh", tmpScript.absolutePath)
                .apply {
                    environment()["VAULT"]  = vaultPath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"]   = context.filesDir.absolutePath
                }
                .start()

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val done   = process.waitFor(30, TimeUnit.SECONDS)

            if (!done) {
                process.destroyForcibly()
                return@withContext "Error: script timed out after 30s"
            }

            val rc = process.exitValue()
            buildString {
                if (stdout.isNotBlank()) append(stdout.trimEnd())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) appendLine()
                    append("[stderr] ${stderr.trimEnd()}")
                }
                if (rc != 0) {
                    if (isNotEmpty()) appendLine()
                    append("[exit $rc]")
                }
            }.ifBlank { if (rc == 0) "(no output)" else "[exit $rc]" }

        } finally {
            tmpScript.delete()
        }
    }
}
