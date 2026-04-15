package com.vela.app.ai.tools

import com.vela.app.vault.VaultRegistry
import com.vela.app.vault.VaultSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GitTool @Inject constructor(
    private val vaultRegistry: VaultRegistry,
    private val vaultSettings: VaultSettings,
) : Tool {

    override val name        = "git"
    override val displayName = "Git"
    override val icon        = "⎇"
    override val description = "Run git operations on the active vault repository. Supports: status, log, diff, add, commit, push, branch, pull."
    override val parameters  = listOf(
        ToolParameter(
            name        = "command",
            type        = "string",
            description = "Git command to run: status | log | diff | add | commit | push | pull | branch",
            required    = true,
        ),
        ToolParameter(
            name        = "args",
            type        = "string",
            description = "Arguments: log N (number of commits), diff/add PATH, commit MESSAGE, pull/push (no args needed)",
            required    = false,
        ),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val command = (args["command"] as? String)?.lowercase()?.trim()
            ?: return@withContext "Error: 'command' is required. Options: status, log, diff, add, commit, push, pull, branch"
        val extraArgs = (args["args"] as? String)?.trim() ?: ""

        val vaults = vaultRegistry.getEnabledVaults()
        if (vaults.isEmpty()) return@withContext "No enabled vault found. Add and enable a vault in Settings → Vaults."

        val vault = vaults.first()
        val vaultPath = File(vault.localPath)
        if (!File(vaultPath, ".git").exists()) return@withContext "Vault '${vault.name}' is not a git repository. Configure a GitHub remote in Settings → Vaults."

        runCatching {
            Git.open(vaultPath).use { git ->
                when (command) {
                    "status" -> {
                        val status = git.status().call()
                        buildString {
                            appendLine("Branch: ${git.repository.branch}")
                            if (status.added.isNotEmpty())     appendLine("Staged (new):      ${status.added.sorted().joinToString()}")
                            if (status.changed.isNotEmpty())   appendLine("Staged (modified): ${status.changed.sorted().joinToString()}")
                            if (status.removed.isNotEmpty())   appendLine("Staged (deleted):  ${status.removed.sorted().joinToString()}")
                            if (status.modified.isNotEmpty())  appendLine("Modified:          ${status.modified.sorted().joinToString()}")
                            if (status.missing.isNotEmpty())   appendLine("Missing:           ${status.missing.sorted().joinToString()}")
                            if (status.untracked.isNotEmpty()) appendLine("Untracked:         ${status.untracked.sorted().take(20).joinToString()}")
                            if (isClean(status)) append("Working tree clean.")
                        }.trim()
                    }

                    "branch" -> "Current branch: ${git.repository.branch}"

                    "log" -> {
                        val limit = extraArgs.toIntOrNull()?.coerceIn(1, 50) ?: 10
                        // git.log() throws NoHeadException on a repo with zero commits;
                        // treat that the same as an empty result.
                        val commits = try {
                            git.log().setMaxCount(limit).call().toList()
                        } catch (_: org.eclipse.jgit.api.errors.NoHeadException) {
                            emptyList()
                        }
                        if (commits.isEmpty()) return@use "No commits."
                        commits.joinToString("\n") { c ->
                            "${c.abbreviate(7).name()} ${c.shortMessage}"
                        }
                    }

                    "diff" -> {
                        val diffs = git.diff().call()
                        if (diffs.isEmpty()) return@use "No unstaged changes."
                        diffs.take(30).joinToString("\n") { entry ->
                            "${entry.changeType.name.take(3)} ${entry.newPath.takeIf { it != "/dev/null" } ?: entry.oldPath}"
                        }.let { if (diffs.size > 30) "$it\n…and ${diffs.size - 30} more" else it }
                    }

                    "add" -> {
                        val pattern = extraArgs.ifBlank { "." }
                        git.add().addFilepattern(pattern).call()
                        "Staged: $pattern"
                    }

                    "commit" -> {
                        if (extraArgs.isBlank()) return@use "Error: provide a commit message in 'args'"
                        val result = git.commit().setMessage(extraArgs).call()
                        "Committed ${result.abbreviate(7).name()}: ${result.shortMessage}"
                    }

                    "push" -> {
                        val remote = vaultSettings.getRemoteUrl(vault.id)
                        if (remote.isBlank()) return@use "No remote configured for vault '${vault.name}'."
                        val pat = vaultSettings.getPat(vault.id)
                        val creds = UsernamePasswordCredentialsProvider("token", pat)
                        val results = git.push().setCredentialsProvider(creds).call()
                        results.flatMap { r -> r.remoteUpdates.map { u -> "${u.remoteName}: ${u.status.name}" } }
                            .joinToString("\n")
                            .ifBlank { "Push complete." }
                    }

                    "pull" -> {
                        val remote = vaultSettings.getRemoteUrl(vault.id)
                        if (remote.isBlank()) return@use "No remote configured for vault '${vault.name}'."
                        val pat = vaultSettings.getPat(vault.id)
                        val creds = UsernamePasswordCredentialsProvider("token", pat)
                        val branch = vaultSettings.getBranch(vault.id)
                        val cmd = git.pull().setCredentialsProvider(creds).setRebase(true)
                        if (branch.isNotBlank()) cmd.setRemoteBranchName(branch)
                        val result = cmd.call()
                        if (result.isSuccessful) "Pulled successfully." else "Pull failed: ${result.rebaseResult?.status}"
                    }

                    else -> "Unknown command: '$command'. Available: status, log, diff, add, commit, push, pull, branch"
                }
            }
        }.getOrElse { e -> "Git error: ${e.message ?: e.javaClass.simpleName}" }
    }

    private fun isClean(status: org.eclipse.jgit.api.Status): Boolean =
        status.added.isEmpty() && status.changed.isEmpty() && status.removed.isEmpty() &&
        status.modified.isEmpty() && status.missing.isEmpty() && status.untracked.isEmpty()
}
