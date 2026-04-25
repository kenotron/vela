package com.vela.app.ui.conversation

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RED → GREEN tests for [listAgentFiles].
 *
 * The helper is extracted from AgentInstallerDialog so it can be validated
 * without Compose/Android dependencies.
 */
class AgentInstallerDialogTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `returns empty list when vault path does not contain agents dir`() {
        val vault = tmp.newFolder("vault")
        val result = listAgentFiles(vault.absolutePath)
        assertThat(result).isEmpty()
    }

    @Test
    fun `returns empty list when agents dir is missing`() {
        val vault = tmp.newFolder("vault2")
        // no .agents sub-directory created
        val result = listAgentFiles(vault.absolutePath)
        assertThat(result).isEmpty()
    }

    @Test
    fun `returns only md files from agents dir`() {
        val vault = tmp.newFolder("vault3")
        val agentsDir = File(vault, ".agents").also { it.mkdirs() }
        File(agentsDir, "explorer.md").writeText("# explorer")
        File(agentsDir, "notes.txt").writeText("ignored")
        File(agentsDir, "builder.md").writeText("# builder")

        val result = listAgentFiles(vault.absolutePath)

        assertThat(result.map { it.name }).containsExactly("builder.md", "explorer.md")
    }

    @Test
    fun `results are sorted by file name`() {
        val vault = tmp.newFolder("vault4")
        val agentsDir = File(vault, ".agents").also { it.mkdirs() }
        File(agentsDir, "zzz.md").writeText("")
        File(agentsDir, "aaa.md").writeText("")
        File(agentsDir, "mmm.md").writeText("")

        val result = listAgentFiles(vault.absolutePath)

        assertThat(result.map { it.name }).containsExactly("aaa.md", "mmm.md", "zzz.md").inOrder()
    }

    @Test
    fun `does not recurse into sub-directories`() {
        val vault = tmp.newFolder("vault5")
        val agentsDir = File(vault, ".agents").also { it.mkdirs() }
        File(agentsDir, "top.md").writeText("")
        val sub = File(agentsDir, "sub").also { it.mkdirs() }
        File(sub, "nested.md").writeText("")

        val result = listAgentFiles(vault.absolutePath)

        assertThat(result.map { it.name }).containsExactly("top.md")
    }

    @Test
    fun `empty agents dir returns empty list`() {
        val vault = tmp.newFolder("vault6")
        File(vault, ".agents").mkdirs()

        val result = listAgentFiles(vault.absolutePath)

        assertThat(result).isEmpty()
    }
}
