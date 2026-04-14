package com.vela.app.ai.tools

import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultGitSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

class BashTool(
    private val gitSync: VaultGitSync,
    private val activeVault: suspend () -> VaultEntity?,
) : Tool {
    override val name = "bash"
    override val displayName = "Shell"
    override val icon = "💻"
    override val description = "Execute shell commands (limited set on mobile: git, date, ls, mkdir). git commands operate on the active vault."
    override val parameters = listOf(
        ToolParameter("command", "string", "Shell command to execute"),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val command = (args["command"] as? String)?.trim()
            ?: return@withContext "Error: command is required"
        route(command)
    }

    private suspend fun route(cmd: String): String = when {
        cmd.startsWith("git ") -> routeGit(cmd.removePrefix("git ").trim())
        cmd == "date" -> LocalDateTime.now().toString()
        cmd.startsWith("ls") -> routeLs(cmd)
        cmd.startsWith("mkdir") -> routeMkdir(cmd)
        else -> "Command not supported on mobile: $cmd\nSupported: git (add/commit/push/pull/status/log), date, ls, mkdir"
    }

    private suspend fun routeGit(gitArgs: String): String {
        val vault = activeVault()
            ?: return "Error: no vault configured"
        val vaultPath = File(vault.localPath)
        val vaultId = vault.id
        return when {
            gitArgs == "add ." || gitArgs == "add -A" -> gitSync.addAll(vaultPath)
            gitArgs == "status" -> gitSync.status(vaultPath)
            gitArgs.startsWith("commit") -> parseCommit(gitArgs, vaultId, vaultPath)
            gitArgs.startsWith("push") -> gitSync.push(vaultId, vaultPath)
            gitArgs.startsWith("pull") -> gitSync.pull(vaultId, vaultPath)
            gitArgs.startsWith("log") -> parseLog(gitArgs, vaultPath)
            else -> "Git subcommand not supported on mobile: git $gitArgs"
        }
    }

    private suspend fun parseCommit(gitArgs: String, vaultId: String, vaultPath: File): String {
        // Match: commit -m "msg", commit -am "msg", commit -m 'msg', commit -am 'msg', commit -m msg
        val regex = Regex("""commit\s+(-[am]+)\s+["']?(.+?)["']?\s*$""")
        val match = regex.find(gitArgs)
            ?: return "Error: could not parse commit message from: git $gitArgs"
        val flags = match.groupValues[1]
        val message = match.groupValues[2].trim()
        val addAll = flags.contains('a')
        return gitSync.commit(vaultId, vaultPath, message, addAll)
    }

    private suspend fun parseLog(gitArgs: String, vaultPath: File): String {
        val countRegex = Regex("""-(\d+)""")
        val count = countRegex.find(gitArgs)?.groupValues?.get(1)?.toIntOrNull() ?: 10
        return gitSync.log(vaultPath, count)
    }

    private fun routeLs(cmd: String): String {
        val parts = cmd.trim().split(Regex("\\s+"))
        val pathArg = parts.lastOrNull { !it.startsWith("-") && it != "ls" }
        val dir = if (pathArg != null) File(pathArg) else null
        if (dir != null && !dir.exists()) return "ls: $pathArg: No such file or directory"
        return (dir?.listFiles() ?: emptyArray())
            .joinToString("\n") { it.name }
            .ifEmpty { "(empty)" }
    }

    private fun routeMkdir(cmd: String): String {
        val parts = cmd.trim().split(Regex("\\s+"))
        val pathArg = parts.lastOrNull { !it.startsWith("-") && it != "mkdir" }
            ?: return "mkdir: missing operand"
        return if (File(pathArg).mkdirs()) "Created: $pathArg"
               else "mkdir: $pathArg: could not create (may already exist)"
    }
}
