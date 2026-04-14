package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VaultConfigHookTest {

    private val hook = VaultConfigHook()

    @Test fun `two vaults produce block containing both names and paths`() = runBlocking {
        val vaults = listOf(
            VaultEntity(id = "1", name = "Personal", localPath = "/data/vaults/personal"),
            VaultEntity(id = "2", name = "Work",     localPath = "/data/vaults/work"),
        )
        val result = hook.execute(HookContext("conv", vaults, HookEvent.SESSION_START))
                         as HookResult.SystemPromptAddendum

        assertThat(result.text).contains("<lifeos-config>")
        assertThat(result.text).contains("</lifeos-config>")
        assertThat(result.text).contains("name: Personal")
        assertThat(result.text).contains("location: /data/vaults/personal")
        assertThat(result.text).contains("name: Work")
        assertThat(result.text).contains("location: /data/vaults/work")
    }

    @Test fun `empty vault list returns Continue`() = runBlocking {
        val result = hook.execute(HookContext("conv", emptyList(), HookEvent.SESSION_START))
        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }
}
