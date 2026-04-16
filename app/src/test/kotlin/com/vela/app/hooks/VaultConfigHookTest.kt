package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test

/**
 * VaultConfigHook is intentionally a no-op — the <lifeos-config> block is
 * built by SessionHarness every turn. These tests verify it always returns
 * Continue and never injects a duplicate config block.
 */
class VaultConfigHookTest {

    private val hook = VaultConfigHook()

    @Test fun `two vaults returns Continue not a config block`() = runBlocking {
        val vaults = listOf(
            VaultEntity(id = "1", name = "Personal", localPath = "/data/vaults/personal"),
            VaultEntity(id = "2", name = "Work",     localPath = "/data/vaults/work"),
        )
        val result = hook.execute(HookContext("conv", vaults, HookEvent.SESSION_START))
        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }

    @Test fun `empty vault list returns Continue`() = runBlocking {
        val result = hook.execute(HookContext("conv", emptyList(), HookEvent.SESSION_START))
        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }
}
