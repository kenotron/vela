package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonalizationHookTest {

    @get:Rule val tmp = TemporaryFolder()

    private val hook = PersonalizationHook()

    @Test fun `_personalization md files included in addendum`() = runBlocking {
        val vaultDir = tmp.newFolder("vault")
        File(vaultDir, "_personalization").mkdirs()
        File(vaultDir, "_personalization/profile.md").writeText("I am Ken")

        val vault  = VaultEntity(id = "v1", name = "Test", localPath = vaultDir.absolutePath)
        val result = hook.execute(HookContext("conv", listOf(vault), HookEvent.SESSION_START))

        assertThat(result).isInstanceOf(HookResult.SystemPromptAddendum::class.java)
        assertThat((result as HookResult.SystemPromptAddendum).text).contains("I am Ken")
    }

    @Test fun `vault without _personalization returns Continue`() = runBlocking {
        val vaultDir = tmp.newFolder("empty-vault")
        val vault    = VaultEntity(id = "v2", name = "Empty", localPath = vaultDir.absolutePath)
        val result   = hook.execute(HookContext("conv", listOf(vault), HookEvent.SESSION_START))

        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }

    @Test fun `multiple md files sorted alphabetically by filename`() = runBlocking {
        val vaultDir = tmp.newFolder("multi")
        File(vaultDir, "_personalization").mkdirs()
        File(vaultDir, "_personalization/b-prefs.md").writeText("Pref B")
        File(vaultDir, "_personalization/a-profile.md").writeText("Profile A")

        val vault  = VaultEntity(id = "v3", name = "Multi", localPath = vaultDir.absolutePath)
        val result = hook.execute(HookContext("conv", listOf(vault), HookEvent.SESSION_START))
                         as HookResult.SystemPromptAddendum

        // a-profile (sorted first) must appear before b-prefs
        assertThat(result.text.indexOf("Profile A")).isLessThan(result.text.indexOf("Pref B"))
    }

    @Test fun `unreadable personalization file returns Error`() = runBlocking<Unit> {
        val vaultDir = tmp.newFolder("locked-vault")
        val personDir = File(vaultDir, "_personalization").also { it.mkdirs() }
        val file = File(personDir, "profile.md").also { it.writeText("secret") }
        file.setReadable(false)
        try {
            val vault  = VaultEntity(id = "v4", name = "Locked", localPath = vaultDir.absolutePath)
            val result = hook.execute(HookContext("conv", listOf(vault), HookEvent.SESSION_START))
            assertThat(result).isInstanceOf(HookResult.Error::class.java)
        } finally {
            file.setReadable(true) // restore so TemporaryFolder can clean up
        }
    }
}
