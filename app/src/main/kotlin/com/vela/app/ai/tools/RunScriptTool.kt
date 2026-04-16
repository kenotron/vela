package com.vela.app.ai.tools

import android.content.Context
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes a shell script in one shot, returning the combined stdout+stderr output.
 *
 * This is "code mode" for the agentic loop: instead of the LLM making N individual
 * tool calls (each result consuming context tokens), the LLM writes a single script
 * that performs all the operations locally. Only the script's final output flows back.
 *
 * The script runs via [ProcessBuilder] as Vela's own UID, giving it full read/write
 * access to the vault filesystem without cross-app sandbox issues.
 *
 * ### Why redirectErrorStream + reader thread
 *
 * Reading stdout and stderr sequentially deadlocks whenever stderr output exceeds the
 * OS pipe buffer (~64 KB): the process blocks trying to write stderr while we block
 * waiting for stdout to finish. Merging streams via [ProcessBuilder.redirectErrorStream]
 * eliminates the second pipe. Draining the merged stream on a daemon thread lets
 * [Process.waitFor] enforce the 30-second timeout even if the read is still in progress.
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

        Output: combined stdout + stderr. Timeout: 30s.
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
                .redirectErrorStream(true)   // merge stderr → stdout: one pipe, no deadlock
                .apply {
                    environment()["VAULT"]  = vaultPath
                    environment()["TMPDIR"] = context.cacheDir.absolutePath
                    environment()["HOME"]   = context.filesDir.absolutePath
                }
                .start()

            // Drain the merged stream on a daemon thread so waitFor() can fire as a real timeout.
            // If we read inline first, a hanging script never reaches waitFor.
            val outputRef = AtomicReference("")
            val readerThread = Thread {
                outputRef.set(process.inputStream.bufferedReader().readText())
            }.apply { isDaemon = true; start() }

            val exited = process.waitFor(30, TimeUnit.SECONDS)

            if (!exited) {
                process.destroyForcibly()
                readerThread.join(500)          // collect whatever was written before kill
                val partial = outputRef.get().trimEnd()
                return@withContext buildString {
                    append("Error: script timed out after 30s")
                    if (partial.isNotBlank()) {
                        appendLine()
                        append("Output before timeout:\n$partial")
                    }
                }
            }

            readerThread.join(2_000)            // should be instant — process already exited

            val output = outputRef.get().trimEnd()
            val rc     = process.exitValue()

            buildString {
                if (output.isNotBlank()) append(output)
                if (rc != 0) {
                    if (isNotEmpty()) appendLine()
                    append("[exit $rc]")
                }
            }.ifBlank { "(script completed — no output)" }

        } finally {
            tmpScript.delete()
        }
    }
}
