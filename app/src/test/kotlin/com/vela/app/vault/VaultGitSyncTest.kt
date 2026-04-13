package com.vela.app.vault

    import com.google.common.truth.Truth.assertThat
    import kotlinx.coroutines.runBlocking
    import org.junit.Before
    import org.junit.Rule
    import org.junit.Test
    import org.junit.rules.TemporaryFolder
    import java.io.File

    class VaultGitSyncTest {

        @get:Rule val tmp = TemporaryFolder()

        private lateinit var workDir: File
        private lateinit var sync: VaultGitSync

        // Anonymous VaultSettings stub — no Android Context needed
        private val stubSettings = object : VaultSettings {
            override fun getRemoteUrl(vaultId: String) = ""
            override fun setRemoteUrl(vaultId: String, url: String) {}
            override fun getPat(vaultId: String) = ""
            override fun setPat(vaultId: String, pat: String) {}
            override fun getBranch(vaultId: String) = "main"
            override fun setBranch(vaultId: String, branch: String) {}
        }

        @Before fun setUp() {
            workDir = tmp.newFolder("vault")
            sync = VaultGitSync(stubSettings)
            runBlocking {
                sync.initRepo(workDir)
                // Create initial commit so log() has something to return
                File(workDir, "README.md").writeText("init")
                sync.addAll(workDir)
                sync.commit("test-vault", workDir, "initial commit")
            }
        }

        @Test fun `addAll and commit returns commit hash and message`() = runBlocking {
            File(workDir, "notes.md").writeText("hello vault")
            sync.addAll(workDir)
            val result = sync.commit("test-vault", workDir, "add notes")
            assertThat(result).contains("add notes")
        }

        @Test fun `status reflects untracked changes`() = runBlocking {
            File(workDir, "new.md").writeText("new file")
            val result = sync.status(workDir)
            assertThat(result).contains("new.md")
        }

        @Test fun `log returns recent commit messages`() = runBlocking {
            val result = sync.log(workDir, count = 5)
            assertThat(result).contains("initial commit")
        }

        @Test fun `commit with addAll=true stages and commits modified files`() = runBlocking {
            File(workDir, "tracked.md").writeText("v1")
            sync.addAll(workDir)
            sync.commit("test-vault", workDir, "track file")
            File(workDir, "tracked.md").writeText("v2")
            // No explicit addAll call — rely on commit(addAll=true)
            val result = sync.commit("test-vault", workDir, "update file", addAll = true)
            assertThat(result).contains("update file")
        }
    }
    