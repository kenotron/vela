package com.vela.app.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val manager by lazy { VaultManager(tmp.newFolder("vaults")) }

    @Test
    fun relativePathResolvesInsideVaultRoot() {
        val result = manager.resolve("Personal/Notes/today.md")
        assertThat(result).isNotNull()
        assertThat(result!!.canonicalPath).startsWith(manager.root.canonicalPath)
    }

    @Test
    fun absoluteStylePathStripsLeadingSlashAndResolvesInsideRoot() {
        val result = manager.resolve("/Personal/Notes/today.md")
        assertThat(result).isNotNull()
        assertThat(result!!.canonicalPath).startsWith(manager.root.canonicalPath)
    }

    @Test
    fun traversalAttackReturnsNull() {
        assertThat(manager.resolve("../../etc/passwd")).isNull()
    }
}
