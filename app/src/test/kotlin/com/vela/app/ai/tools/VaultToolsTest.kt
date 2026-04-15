package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VaultToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vaultRoot by lazy { tmp.newFolder("vault") }
    private val vault by lazy { VaultManager(vaultRoot, MutableStateFlow(setOf(vaultRoot.canonicalPath))) }

    // ─────────────────────────── ReadFileTool ────────────────────────────────

    @Test
    fun readFile_returnsContentWithLineNumbers() = runTest {
        val file = vault.root.resolve("notes.md")
        file.writeText("line one\nline two\nline three")

        val result = ReadFileTool(vault).execute(mapOf("file_path" to "notes.md"))

        assertThat(result).contains("line one")
        assertThat(result).contains("line two")
        assertThat(result).contains("line three")
        // Verify cat -n style: line number then tab then content
        assertThat(result).contains("1\tline one")
        assertThat(result).contains("2\tline two")
        assertThat(result).contains("3\tline three")
    }

    @Test
    fun readFile_directory_returnsListing() = runTest {
        val dir = vault.root.resolve("docs")
        dir.mkdirs()
        dir.resolve("readme.md").writeText("hello")
        dir.resolve("notes.txt").writeText("world")

        val result = ReadFileTool(vault).execute(mapOf("file_path" to "docs"))

        assertThat(result).contains("Directory: docs")
        assertThat(result).contains("readme.md")
        assertThat(result).contains("notes.txt")
    }

    @Test
    fun readFile_traversalAttack_returnsError() = runTest {
        val result = ReadFileTool(vault).execute(mapOf("file_path" to "../../etc/passwd"))

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("outside the vault")
    }

    @Test
    fun readFile_offsetAndLimit_returnsCorrectWindow() = runTest {
        val file = vault.root.resolve("big.txt")
        file.writeText((1..10).joinToString("\n") { "line $it" })

        // offset=3 means start at line 3 (1-indexed), limit=3 → lines 3,4,5
        val result = ReadFileTool(vault).execute(
            mapOf("file_path" to "big.txt", "offset" to 3, "limit" to 3)
        )

        assertThat(result).contains("3\tline 3")
        assertThat(result).contains("4\tline 4")
        assertThat(result).contains("5\tline 5")
        assertThat(result).doesNotContain("line 1")
        assertThat(result).doesNotContain("line 6")
    }

    // ─────────────────────────── WriteFileTool ───────────────────────────────

    @Test
    fun writeFile_createsFileWithCorrectContent() = runTest {
        val result = WriteFileTool(vault).execute(
            mapOf("file_path" to "hello.md", "content" to "# Hello\nWorld")
        )

        assertThat(result).contains("hello.md")
        val written = vault.root.resolve("hello.md").readText()
        assertThat(written).isEqualTo("# Hello\nWorld")
    }

    @Test
    fun writeFile_createsParentDirectoriesAutomatically() = runTest {
        val result = WriteFileTool(vault).execute(
            mapOf("file_path" to "deep/nested/dir/file.md", "content" to "content here")
        )

        assertThat(result).contains("lines to")
        val written = vault.root.resolve("deep/nested/dir/file.md").readText()
        assertThat(written).isEqualTo("content here")
    }

    // ─────────────────────────── EditFileTool ────────────────────────────────

    @Test
    fun editFile_replacesUniqueString() = runTest {
        val file = vault.root.resolve("note.md")
        file.writeText("Hello world, this is a test.")

        val result = EditFileTool(vault).execute(
            mapOf(
                "file_path" to "note.md",
                "old_string" to "world",
                "new_string" to "Vela",
            )
        )

        assertThat(result).contains("1 occurrence(s)")
        assertThat(file.readText()).isEqualTo("Hello Vela, this is a test.")
    }

    @Test
    fun editFile_oldStringNotFound_returnsError() = runTest {
        val file = vault.root.resolve("note.md")
        file.writeText("Hello world.")

        val result = EditFileTool(vault).execute(
            mapOf(
                "file_path" to "note.md",
                "old_string" to "missing text",
                "new_string" to "replacement",
            )
        )

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("not found")
    }

    @Test
    fun editFile_multipleOccurrences_replaceAllFalse_returnsError() = runTest {
        val file = vault.root.resolve("note.md")
        file.writeText("foo bar foo baz foo")

        val result = EditFileTool(vault).execute(
            mapOf(
                "file_path" to "note.md",
                "old_string" to "foo",
                "new_string" to "qux",
                "replace_all" to false,
            )
        )

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("3 times")
        // File should be unchanged
        assertThat(file.readText()).isEqualTo("foo bar foo baz foo")
    }

    @Test
    fun editFile_replaceAll_replacesAllOccurrences() = runTest {
        val file = vault.root.resolve("note.md")
        file.writeText("foo bar foo baz foo")

        val result = EditFileTool(vault).execute(
            mapOf(
                "file_path" to "note.md",
                "old_string" to "foo",
                "new_string" to "qux",
                "replace_all" to true,
            )
        )

        assertThat(result).contains("3 occurrence(s)")
        assertThat(file.readText()).isEqualTo("qux bar qux baz qux")
    }
}
