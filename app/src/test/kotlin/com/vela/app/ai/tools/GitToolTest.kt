package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultRegistry
import com.vela.app.vault.VaultSettings
import com.vela.app.data.db.VaultDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GitToolTest {

    @get:Rule val tmp = TemporaryFolder()

    // ── Fakes ────────────────────────────────────────────────────────────────

    private val fakeSettings = object : VaultSettings {
        override fun getRemoteUrl(vaultId: String) = ""
        override fun setRemoteUrl(vaultId: String, url: String) = Unit
        override fun getPat(vaultId: String) = ""
        override fun setPat(vaultId: String, pat: String) = Unit
        override fun getBranch(vaultId: String) = "main"
        override fun setBranch(vaultId: String, branch: String) = Unit
    }

    private fun fakeRegistry(vaults: List<VaultEntity>): VaultRegistry {
        val dao = object : VaultDao {
            override fun observeAll(): Flow<List<VaultEntity>> = flowOf(vaults)
            override suspend fun getEnabled(): List<VaultEntity> = vaults
            override suspend fun getById(id: String): VaultEntity? = vaults.firstOrNull { it.id == id }
            override suspend fun insert(vault: VaultEntity) = Unit
            override suspend fun update(vault: VaultEntity) = Unit
            override suspend fun delete(vault: VaultEntity) = Unit
        }
        return VaultRegistry(dao, tmp.newFolder("vaults"))
    }

    private fun makeGitTool(vaults: List<VaultEntity>): GitTool =
        GitTool(vaultRegistry = fakeRegistry(vaults), vaultSettings = fakeSettings)

    // ── Error paths ──────────────────────────────────────────────────────────

    @Test
    fun execute_missingCommand_returnsError() = runTest {
        val tool = makeGitTool(emptyList())

        val result = tool.execute(emptyMap())

        assertThat(result).contains("Error")
        assertThat(result).contains("command")
    }

    @Test
    fun execute_noEnabledVault_returnsHelpfulMessage() = runTest {
        val tool = makeGitTool(emptyList())

        val result = tool.execute(mapOf("command" to "status"))

        assertThat(result).contains("No enabled vault")
    }

    @Test
    fun execute_vaultNotAGitRepo_returnsHelpfulMessage() = runTest {
        val vaultDir = tmp.newFolder("plain-vault")
        val vault = VaultEntity(id = "v1", name = "Plain", localPath = vaultDir.absolutePath)
        val tool = makeGitTool(listOf(vault))

        val result = tool.execute(mapOf("command" to "status"))

        assertThat(result).contains("not a git repository")
    }

    @Test
    fun execute_unknownCommand_returnsHelpfulMessage() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "rebase"))

        assertThat(result).contains("Unknown command")
        assertThat(result).contains("rebase")
    }

    // ── status ───────────────────────────────────────────────────────────────

    @Test
    fun status_onCleanInitialRepo_returnsCleanMessage() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "status"))

        assertThat(result).contains("Branch:")
        assertThat(result).contains("Working tree clean")
    }

    @Test
    fun status_withUntrackedFile_listsUntrackedFile() = runTest {
        val vaultDir = initGitRepo()
        File(vaultDir, "hello.md").writeText("hello")
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "status"))

        assertThat(result).contains("hello.md")
        assertThat(result).contains("Untracked")
    }

    // ── branch ───────────────────────────────────────────────────────────────

    @Test
    fun branch_returnsCurrentBranch() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "branch"))

        assertThat(result).contains("Current branch:")
        assertThat(result).isNotEmpty()
    }

    // ── log ──────────────────────────────────────────────────────────────────

    @Test
    fun log_noCommits_returnsNoCommitsMessage() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "log"))

        assertThat(result).contains("No commits")
    }

    @Test
    fun log_withCommit_returnsCommitLine() = runTest {
        val vaultDir = initGitRepo()
        val git = Git.open(vaultDir)
        File(vaultDir, "note.md").writeText("# note")
        git.add().addFilepattern(".").call()
        git.commit().setMessage("initial commit").call()
        git.close()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "log"))

        assertThat(result).contains("initial commit")
    }

    @Test
    fun log_withCustomCount_respectsLimit() = runTest {
        val vaultDir = initGitRepo()
        val git = Git.open(vaultDir)
        repeat(5) { i ->
            File(vaultDir, "note$i.md").writeText("note $i")
            git.add().addFilepattern(".").call()
            git.commit().setMessage("commit $i").call()
        }
        git.close()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "log", "args" to "2"))

        // Only 2 commits should be shown — commit 3 and 4 (most recent 2)
        assertThat(result.lines().filter { it.isNotBlank() }).hasSize(2)
    }

    // ── diff ─────────────────────────────────────────────────────────────────

    @Test
    fun diff_noChanges_returnsNoChangesMessage() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "diff"))

        assertThat(result).contains("No unstaged changes")
    }

    // ── add ──────────────────────────────────────────────────────────────────

    @Test
    fun add_stagesToDot_returnsStagedConfirmation() = runTest {
        val vaultDir = initGitRepo()
        File(vaultDir, "note.md").writeText("# note")
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "add"))

        assertThat(result).contains("Staged")
    }

    // ── commit ───────────────────────────────────────────────────────────────

    @Test
    fun commit_missingMessage_returnsError() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "commit"))

        assertThat(result).contains("Error")
        assertThat(result).contains("message")
    }

    @Test
    fun commit_withStagedFile_returnsCommitHash() = runTest {
        val vaultDir = initGitRepo()
        val git = Git.open(vaultDir)
        File(vaultDir, "note.md").writeText("# note")
        git.add().addFilepattern(".").call()
        git.close()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "commit", "args" to "my commit"))

        assertThat(result).contains("my commit")
        // Abbreviated hash: 7 hex chars
        assertThat(result).containsMatch("[0-9a-f]{7}")
    }

    // ── push (no remote) ─────────────────────────────────────────────────────

    @Test
    fun push_noRemoteConfigured_returnsNoRemoteMessage() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "push"))

        assertThat(result).contains("No remote")
    }

    // ── pull (no remote) ─────────────────────────────────────────────────────

    @Test
    fun pull_noRemoteConfigured_returnsNoRemoteMessage() = runTest {
        val vaultDir = initGitRepo()
        val tool = makeGitTool(listOf(vaultEntity(vaultDir)))

        val result = tool.execute(mapOf("command" to "pull"))

        assertThat(result).contains("No remote")
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun initGitRepo(): File {
        val dir = tmp.newFolder()
        Git.init().setDirectory(dir).call().close()
        return dir
    }

    private fun vaultEntity(dir: File): VaultEntity =
        VaultEntity(id = "v1", name = "TestVault", localPath = dir.absolutePath)
}
