package com.vela.app.vault

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultRoot by lazy { tmp.newFolder("vaults") }
    private val enabledPaths by lazy { MutableStateFlow(setOf(vaultRoot.canonicalPath)) }
    private val manager by lazy { VaultManager(vaultRoot, enabledPaths) }

    @Test
    fun relativePathResolvesInsideVaultRoot() {
        val result = manager.resolve("Personal/Notes/today.md")
        assertThat(result).isNotNull()
        assertThat(result!!.canonicalPath).startsWith(manager.root.canonicalPath)
    }

    @Test
    fun `absolute-style path produces same result as relative path`() {
        val relative = manager.resolve("Personal/Notes/today.md")
        val absolute = manager.resolve("/Personal/Notes/today.md")
        assertThat(relative).isNotNull()
        assertThat(absolute).isNotNull()
        // The key assertion: both should point to the same file
        assertThat(absolute!!.canonicalPath).isEqualTo(relative!!.canonicalPath)
    }

    @Test
    fun traversalAttackReturnsNull() {
        assertThat(manager.resolve("../../etc/passwd")).isNull()
    }

    // --- Enabled-vault gating tests ---

    @Test
    fun `file within enabled vault resolves successfully`() {
        val root = tmp.newFolder("vault_enabled")
        val paths = MutableStateFlow(setOf(root.canonicalPath))
        val mgr = VaultManager(root, paths)
        assertThat(mgr.resolve("notes.md")).isNotNull()
    }

    @Test
    fun `file not within any enabled vault returns null`() {
        val root = tmp.newFolder("vault_disabled")
        val otherDir = tmp.newFolder("other_vault")
        // otherDir is "enabled" but root is not — so resolving from root should fail
        val paths = MutableStateFlow(setOf(otherDir.canonicalPath))
        val mgr = VaultManager(root, paths)
        assertThat(mgr.resolve("notes.md")).isNull()
    }

    @Test
    fun `empty enabled vault set allows all access (first launch)`() {
        val root = tmp.newFolder("vault_fresh")
        val paths = MutableStateFlow<Set<String>>(emptySet())
        val mgr = VaultManager(root, paths)
        assertThat(mgr.resolve("notes.md")).isNotNull()
    }

    @Test
    fun `disabling vault at runtime blocks subsequent resolve calls`() {
        val root = tmp.newFolder("vault_toggle")
        val paths = MutableStateFlow(setOf(root.canonicalPath))
        val mgr = VaultManager(root, paths)
        // Enabled — should resolve
        assertThat(mgr.resolve("doc.md")).isNotNull()
        // Toggle off by clearing enabled paths
        paths.value = emptySet<String>().also {
            // Re-assign a non-empty set pointing elsewhere to simulate "vault disabled"
        }
        paths.value = setOf("/nonexistent/other_vault")
        // Now the root vault is "disabled" — resolve must return null
        assertThat(mgr.resolve("doc.md")).isNull()
    }
}
