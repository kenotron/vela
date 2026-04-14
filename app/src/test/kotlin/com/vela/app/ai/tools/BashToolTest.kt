package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultGitSync
import com.vela.app.vault.VaultSettings
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BashToolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── Fake VaultSettings (interface) ─────────────────────────────────────────

    private val fakeSettings = object : VaultSettings {
        override fun getRemoteUrl(vaultId: String) = ""
        override fun setRemoteUrl(vaultId: String, url: String) = Unit
        override fun getPat(vaultId: String) = ""
        override fun setPat(vaultId: String, pat: String) = Unit
        override fun getBranch(vaultId: String) = "main"
        override fun setBranch(vaultId: String, branch: String) = Unit
    }

    // ── Stub VaultGitSync ──────────────────────────────────────────────────────

    private inner class StubGitSync : VaultGitSync(fakeSettings) {
        var lastAddAllPath: File? = null
        var lastCommitId: String? = null
        var lastCommitPath: File? = null
        var lastCommitMessage: String? = null
        var lastCommitAddAll: Boolean? = null
        var lastPushId: String? = null
        var lastPushPath: File? = null
        var lastPullId: String? = null
        var lastPullPath: File? = null
        var lastStatusPath: File? = null
        var lastLogPath: File? = null
        var lastLogCount: Int? = null

        override suspend fun addAll(vaultPath: File): String {
            lastAddAllPath = vaultPath
            return "Staged all changes."
        }

        override suspend fun commit(
            vaultId: String,
            vaultPath: File,
            message: String,
            addAll: Boolean,
        ): String {
            lastCommitId = vaultId
            lastCommitPath = vaultPath
            lastCommitMessage = message
            lastCommitAddAll = addAll
            return "[abc1234] $message"
        }

        override suspend fun push(vaultId: String, vaultPath: File): String {
            lastPushId = vaultId
            lastPushPath = vaultPath
            return "Pushed."
        }

        override suspend fun pull(vaultId: String, vaultPath: File): String {
            lastPullId = vaultId
            lastPullPath = vaultPath
            return "Pulled successfully."
        }

        override suspend fun status(vaultPath: File): String {
            lastStatusPath = vaultPath
            return "nothing to commit, working tree clean"
        }

        override suspend fun log(vaultPath: File, count: Int): String {
            lastLogPath = vaultPath
            lastLogCount = count
            return "(no commits yet)"
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun makeVault(): VaultEntity {
        val dir = tmp.newFolder("vault")
        return VaultEntity(id = "test-id", name = "test", localPath = dir.path)
    }

    private fun tool(stub: StubGitSync, vault: VaultEntity?): BashTool =
        BashTool(gitSync = stub, activeVault = { vault })

    // ── Tests ──────────────────────────────────────────────────────────────────

    @Test
    fun `git add dot routes to gitSync addAll`() = runTest {
        val stub = StubGitSync()
        val vault = makeVault()
        val result = tool(stub, vault).execute(mapOf("command" to "git add ."))

        assertThat(stub.lastAddAllPath).isNotNull()
        assertThat(stub.lastAddAllPath!!.path).isEqualTo(vault.localPath)
        assertThat(result).contains("Staged")
    }

    @Test
    fun `git commit -m routes to gitSync commit with addAll false`() = runTest {
        val stub = StubGitSync()
        val vault = makeVault()
        val result = tool(stub, vault).execute(mapOf("command" to "git commit -m \"update notes\""))

        assertThat(stub.lastCommitMessage).isEqualTo("update notes")
        assertThat(stub.lastCommitAddAll).isFalse()
        assertThat(stub.lastCommitId).isEqualTo("test-id")
        assertThat(result).contains("update notes")
    }

    @Test
    fun `git commit -am routes to gitSync commit with addAll true`() = runTest {
        val stub = StubGitSync()
        val vault = makeVault()
        val result = tool(stub, vault).execute(mapOf("command" to "git commit -am 'daily update'"))

        assertThat(stub.lastCommitMessage).isEqualTo("daily update")
        assertThat(stub.lastCommitAddAll).isTrue()
        assertThat(result).contains("daily update")
    }

    @Test
    fun `git status routes to gitSync status`() = runTest {
        val stub = StubGitSync()
        val vault = makeVault()
        val result = tool(stub, vault).execute(mapOf("command" to "git status"))

        assertThat(stub.lastStatusPath).isNotNull()
        assertThat(stub.lastStatusPath!!.path).isEqualTo(vault.localPath)
        assertThat(result).contains("nothing to commit")
    }

    @Test
    fun `git push routes to gitSync push`() = runTest {
        val stub = StubGitSync()
        val vault = makeVault()
        val result = tool(stub, vault).execute(mapOf("command" to "git push origin main"))

        assertThat(stub.lastPushId).isEqualTo("test-id")
        assertThat(stub.lastPushPath).isNotNull()
        assertThat(result).contains("Pushed")
    }

    @Test
    fun `git log with count routes to gitSync log with count`() = runTest {
        val stub = StubGitSync()
        val vault = makeVault()
        val result = tool(stub, vault).execute(mapOf("command" to "git log --oneline -5"))

        assertThat(stub.lastLogCount).isEqualTo(5)
        assertThat(stub.lastLogPath).isNotNull()
        assertThat(result).isNotEmpty()
    }

    @Test
    fun `date command returns a date string`() = runTest {
        val stub = StubGitSync()
        val result = tool(stub, null).execute(mapOf("command" to "date"))

        // Should be a non-empty string that isn't an error
        assertThat(result).doesNotContain("Error")
        assertThat(result).doesNotContain("not supported")
        assertThat(result).isNotEmpty()
    }

    @Test
    fun `unsupported command returns helpful error`() = runTest {
        val stub = StubGitSync()
        val result = tool(stub, null).execute(mapOf("command" to "rm -rf /"))

        assertThat(result).contains("Command not supported on mobile")
        assertThat(result).contains("rm -rf /")
    }

    @Test
    fun `no active vault returns error message`() = runTest {
        val stub = StubGitSync()
        val result = tool(stub, null).execute(mapOf("command" to "git status"))

        assertThat(result).contains("Error")
        assertThat(result).contains("no vault configured")
    }
}
