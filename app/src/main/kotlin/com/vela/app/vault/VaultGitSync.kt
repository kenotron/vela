package com.vela.app.vault

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import org.eclipse.jgit.api.Git
    import org.eclipse.jgit.lib.PersonIdent
    import org.eclipse.jgit.transport.RemoteRefUpdate
    import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
    import java.io.File

    open class VaultGitSync(private val vaultSettings: VaultSettings) {

        private fun credProvider(vaultId: String) =
            UsernamePasswordCredentialsProvider("token", vaultSettings.getPat(vaultId))

        // Hardcoded PersonIdent so commits work in environments with no system git config
        private val ident = PersonIdent("Vela", "vela@app.local")

        suspend fun initRepo(vaultPath: File): String = withContext(Dispatchers.IO) {
            runCatching {
                Git.init().setDirectory(vaultPath).call().close()
                "Initialized empty git repository in ${vaultPath.path}"
            }.getOrElse { "Error initializing repo: ${it.message}" }
        }

        suspend fun cloneIfNeeded(vaultId: String, vaultPath: File): String = withContext(Dispatchers.IO) {
            if (File(vaultPath, ".git").exists()) return@withContext "Already cloned."
            val remote = vaultSettings.getRemoteUrl(vaultId)
            if (remote.isBlank()) return@withContext "No remote configured."
            runCatching {
                Git.cloneRepository()
                    .setURI(remote)
                    .setDirectory(vaultPath)
                    .setBranch(vaultSettings.getBranch(vaultId))
                    .setCredentialsProvider(credProvider(vaultId))
                    .call().close()
                "Cloned $remote into ${vaultPath.path}"
            }.getOrElse { "Error cloning: ${it.message}" }
        }

        open suspend fun addAll(vaultPath: File): String = withContext(Dispatchers.IO) {
            runCatching {
                Git.open(vaultPath).use { git ->
                    git.add().addFilepattern(".").call()
                    "Staged all changes."
                }
            }.getOrElse { "Error staging: ${it.message}" }
        }

        open suspend fun commit(
            vaultId: String,
            vaultPath: File,
            message: String,
            addAll: Boolean = false,
        ): String = withContext(Dispatchers.IO) {
            runCatching {
                Git.open(vaultPath).use { git ->
                    val cmd = git.commit()
                        .setMessage(message)
                        .setAuthor(ident)
                        .setCommitter(ident)
                    if (addAll) cmd.setAll(true)
                    val result = cmd.call()
                    "[${result.id.abbreviate(7).name()}] $message"
                }
            }.getOrElse { "Error committing: ${it.message}" }
        }

        open suspend fun push(vaultId: String, vaultPath: File): String = withContext(Dispatchers.IO) {
            runCatching {
                Git.open(vaultPath).use { git ->
                    val results = git.push()
                        .setCredentialsProvider(credProvider(vaultId))
                        .call()
                    val rejected = results.flatMap { it.remoteUpdates }
                        .filter { it.status != RemoteRefUpdate.Status.OK
                               && it.status != RemoteRefUpdate.Status.UP_TO_DATE }
                    if (rejected.isNotEmpty()) {
                        "Push rejected: ${rejected.joinToString { "${it.remoteName}: ${it.status}" }}"
                    } else {
                        "Pushed to ${vaultSettings.getRemoteUrl(vaultId)}"
                    }
                }
            }.getOrElse { "Error pushing: ${it.message}" }
        }

        open suspend fun pull(vaultId: String, vaultPath: File): String = withContext(Dispatchers.IO) {
            if (!File(vaultPath, ".git").exists()) return@withContext "Not cloned yet — configure sync in Settings"
            runCatching {
                Git.open(vaultPath).use { git ->
                    val result = git.pull()
                        .setCredentialsProvider(credProvider(vaultId))
                        .setRebase(true)
                        .call()
                    if (result.isSuccessful) "Pulled successfully." else "Pull failed: ${result.rebaseResult?.status}"
                }
            }.getOrElse { "Error pulling: ${it.message}" }
        }

        open suspend fun status(vaultPath: File): String = withContext(Dispatchers.IO) {
            runCatching {
                Git.open(vaultPath).use { git ->
                    val s = git.status().call()
                    if (s.isClean) "nothing to commit, working tree clean"
                    else buildString {
                        if (s.added.isNotEmpty()) appendLine("Added: ${s.added.joinToString(", ")}")
                        if (s.modified.isNotEmpty()) appendLine("Modified: ${s.modified.joinToString(", ")}")
                        if (s.removed.isNotEmpty()) appendLine("Removed: ${s.removed.joinToString(", ")}")
                        if (s.untracked.isNotEmpty()) appendLine("Untracked: ${s.untracked.joinToString(", ")}")
                    }.trim()
                }
            }.getOrElse { "Error getting status: ${it.message}" }
        }

        open suspend fun log(vaultPath: File, count: Int = 10): String = withContext(Dispatchers.IO) {
            runCatching {
                Git.open(vaultPath).use { git ->
                    git.log().setMaxCount(count).call()
                        .joinToString("\n") { "${it.id.abbreviate(7).name()} ${it.shortMessage}" }
                        .ifEmpty { "(no commits yet)" }
                }
            }.getOrElse { "Error reading log: ${it.message}" }
        }
    }
    