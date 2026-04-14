package com.vela.app.harness

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.hooks.Hook
import com.vela.app.hooks.HookContext
import com.vela.app.hooks.HookEvent
import com.vela.app.hooks.HookRegistry
import com.vela.app.hooks.HookResult
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionHarnessTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makeHarness(addendum: String = "", fallback: String = "FALLBACK"): SessionHarness {
        val hook = object : Hook {
            override val event = HookEvent.SESSION_START
            override suspend fun execute(ctx: HookContext): HookResult =
                if (addendum.isBlank()) HookResult.Continue
                else HookResult.SystemPromptAddendum(addendum)
        }
        return SessionHarness(HookRegistry(listOf(hook)), fallback)
    }

    @Test fun `uses vault SYSTEM_md when present`() = runBlocking {
        val vaultDir = tmp.newFolder("vault")
        File(vaultDir, "SYSTEM.md").writeText("VAULT SYSTEM PROMPT")
        val vault  = VaultEntity(id = "v", name = "V", localPath = vaultDir.absolutePath)
        val harness = makeHarness(fallback = "FALLBACK")

        val prompt = harness.buildSystemPrompt("conv-1", listOf(vault))

        assertThat(prompt).contains("VAULT SYSTEM PROMPT")
        assertThat(prompt).doesNotContain("FALLBACK")
    }

    @Test fun `falls back to constructor string when no vault SYSTEM_md`() = runBlocking {
        val vaultDir = tmp.newFolder("empty")
        val vault    = VaultEntity(id = "v", name = "V", localPath = vaultDir.absolutePath)
        val harness  = makeHarness(fallback = "INJECTED FALLBACK")

        val prompt = harness.buildSystemPrompt("conv-2", listOf(vault))

        assertThat(prompt).contains("INJECTED FALLBACK")
    }

    @Test fun `hook addenda appended after system prompt`() = runBlocking {
        val vaultDir = tmp.newFolder("vault2")
        File(vaultDir, "SYSTEM.md").writeText("BASE PROMPT")
        val vault   = VaultEntity(id = "v", name = "V", localPath = vaultDir.absolutePath)
        val harness = makeHarness(addendum = "PERSONALIZATION DATA")

        val prompt = harness.buildSystemPrompt("conv-3", listOf(vault))

        assertThat(prompt).contains("BASE PROMPT")
        assertThat(prompt).contains("PERSONALIZATION DATA")
        assertThat(prompt.indexOf("BASE PROMPT")).isLessThan(prompt.indexOf("PERSONALIZATION DATA"))
    }

    @Test fun `isInitialized is false before first build, true after`() = runBlocking {
        val harness = makeHarness()
        assertThat(harness.isInitialized("conv-4")).isFalse()
        harness.buildSystemPrompt("conv-4", emptyList())
        assertThat(harness.isInitialized("conv-4")).isTrue()
    }

    @Test fun `different conversation IDs tracked independently`() = runBlocking {
        val harness = makeHarness()
        harness.buildSystemPrompt("conv-a", emptyList())
        assertThat(harness.isInitialized("conv-a")).isTrue()
        assertThat(harness.isInitialized("conv-b")).isFalse()
    }
}
