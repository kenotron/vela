package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class VaultSyncHookTest {

    private val vaultA = VaultEntity(id = "a", name = "A", localPath = "/tmp/a")
    private val vaultB = VaultEntity(id = "b", name = "B", localPath = "/tmp/b")

    // Only vault "a" is configured for sync
    private val fakeSettings = object : VaultSettings {
        override fun isConfiguredForSync(vaultId: String) = vaultId == "a"
        override fun getRemoteUrl(vaultId: String) = ""
        override fun setRemoteUrl(vaultId: String, url: String) {}
        override fun getPat(vaultId: String) = ""
        override fun setPat(vaultId: String, pat: String) {}
        override fun getBranch(vaultId: String) = "main"
        override fun setBranch(vaultId: String, branch: String) {}
    }

    @Test fun `pull called only for vault configured for sync`() = runBlocking {
        val pulledIds = mutableListOf<String>()
        val hook = VaultSyncHook(
            pull          = { id, _ -> pulledIds.add(id) },
            vaultSettings = fakeSettings,
        )
        val ctx    = HookContext("conv", listOf(vaultA, vaultB), HookEvent.SESSION_START)
        val result = hook.execute(ctx)

        assertThat(pulledIds).containsExactly("a")
        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }

    @Test fun `no active vaults — pull never called`() = runBlocking {
        var pullCalled = false
        val hook = VaultSyncHook(
            pull          = { _, _ -> pullCalled = true },
            vaultSettings = fakeSettings,
        )
        hook.execute(HookContext("conv", emptyList(), HookEvent.SESSION_START))
        assertThat(pullCalled).isFalse()
    }
}
