package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.events.EventBus
import com.vela.app.vault.VaultSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException

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

    @Test fun `cloneIfNeeded called before pull for sync-configured vault`() = runBlocking {
        val callOrder = mutableListOf<String>()
        val hook = VaultSyncHook(
            cloneIfNeeded = { id, _ -> callOrder.add("clone:$id") },
            pull          = { id, _ -> callOrder.add("pull:$id") },
            vaultSettings = fakeSettings,
            eventBus      = EventBus(),
        )
        val ctx = HookContext("conv", listOf(vaultA), HookEvent.SESSION_START)
        hook.execute(ctx)

        assertThat(callOrder).containsExactly("clone:a", "pull:a").inOrder()
    }

    @Test fun `pull called only for vault configured for sync`() = runBlocking {
        val pulledIds = mutableListOf<String>()
        val hook = VaultSyncHook(
            cloneIfNeeded = { _, _ -> },
            pull          = { id, _ -> pulledIds.add(id) },
            vaultSettings = fakeSettings,
            eventBus      = EventBus(),
        )
        val ctx = HookContext("conv", listOf(vaultA, vaultB), HookEvent.SESSION_START)
        val result = hook.execute(ctx)

        assertThat(pulledIds).containsExactly("a")
        assertThat(result).isEqualTo(HookResult.Continue)
    }

    @Test fun `no active vaults — pull never called`() = runBlocking {
        var pullCalled = false
        val hook = VaultSyncHook(
            cloneIfNeeded = { _, _ -> },
            pull          = { _, _ -> pullCalled = true },
            vaultSettings = fakeSettings,
            eventBus      = EventBus(),
        )
        hook.execute(HookContext("conv", emptyList(), HookEvent.SESSION_START))
        assertThat(pullCalled).isFalse()
    }

    @Test fun `pull throwing returns HookResult_Error`() = runBlocking {
        val hook = VaultSyncHook(
            cloneIfNeeded = { _, _ -> },
            pull          = { _, _ -> throw IOException("network unreachable") },
            vaultSettings = fakeSettings,
            eventBus      = EventBus(),
        )
        val result = hook.execute(HookContext("conv", listOf(vaultA), HookEvent.SESSION_START))
        assertThat(result).isInstanceOf(HookResult.Error::class.java)
    }

    @Test fun `pull failure emits sync-failed event on event bus`() = runBlocking {
        val eventBus = EventBus()
        val received = mutableListOf<String>()
        val job = launch { eventBus.events.collect { received += it.topic } }
        delay(10) // let collector attach

        val hook = VaultSyncHook(
            cloneIfNeeded = { _, _ -> },
            pull          = { _, _ -> throw IOException("network unreachable") },
            vaultSettings = fakeSettings,
            eventBus      = eventBus,
        )
        hook.execute(HookContext("conv", listOf(vaultA), HookEvent.SESSION_START))
        delay(50)
        job.cancel()

        assertThat(received).contains("vela:sync-failed")
    }

    @Test fun `successful sync emits vault-synced event on event bus`() = runBlocking {
        val eventBus = EventBus()
        val received = mutableListOf<String>()
        val job = launch { eventBus.events.collect { received += it.topic } }
        delay(10) // let collector attach

        val hook = VaultSyncHook(
            cloneIfNeeded = { _, _ -> },
            pull          = { _, _ -> },
            vaultSettings = fakeSettings,
            eventBus      = eventBus,
        )
        hook.execute(HookContext("conv", listOf(vaultA), HookEvent.SESSION_START))
        delay(50)
        job.cancel()

        assertThat(received).contains("vela:vault-synced")
    }
}
