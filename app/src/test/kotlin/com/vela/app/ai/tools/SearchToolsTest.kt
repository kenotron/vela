package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SearchToolsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val vault by lazy { VaultManager(tmp.newFolder("vault")) }

    // ─────────────────────────────── GlobTool ────────────────────────────────

    @Test
    fun glob_findsOnlyMarkdownFiles() = runTest {
        vault.root.resolve("notes.md").writeText("hello")
        vault.root.resolve("readme.md").writeText("world")
        vault.root.resolve("image.png").writeText("binary")

        val result = GlobTool(vault).execute(mapOf("pattern" to "**/*.md"))

        assertThat(result).contains("notes.md")
        assertThat(result).contains("readme.md")
        assertThat(result).doesNotContain("image.png")
    }

    @Test
    fun glob_excludesDotGitContents() = runTest {
        val gitDir = vault.root.resolve(".git")
        gitDir.mkdirs()
        gitDir.resolve("config").writeText("git config")
        vault.root.resolve("real.md").writeText("real")

        val result = GlobTool(vault).execute(mapOf("pattern" to "**/*"))

        assertThat(result).doesNotContain(".git")
        assertThat(result).doesNotContain("config")
    }

    @Test
    fun glob_typeDirReturnsOnlyDirectories() = runTest {
        vault.root.resolve("subdir").mkdirs()
        vault.root.resolve("notes.md").writeText("content")

        val result = GlobTool(vault).execute(
            mapOf("pattern" to "**/*", "type" to "dir")
        )

        assertThat(result).contains("subdir")
        assertThat(result).doesNotContain("notes.md")
    }

    @Test
    fun glob_noMatches_returnsNoMatchesMessage() = runTest {
        vault.root.resolve("notes.md").writeText("content")

        val result = GlobTool(vault).execute(mapOf("pattern" to "**/*.txt"))

        assertThat(result).startsWith("(no matches")
        assertThat(result).contains("**/*.txt")
    }

    @Test
    fun glob_missingPattern_returnsError() = runTest {
        val result = GlobTool(vault).execute(emptyMap())

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("pattern")
    }

    @Test
    fun glob_pathOutsideVault_returnsError() = runTest {
        val result = GlobTool(vault).execute(
            mapOf("pattern" to "*.md", "path" to "../../etc")
        )

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("outside the vault")
    }

    @Test
    fun glob_withSubdirectoryPattern_findsNestedFiles() = runTest {
        val sub = vault.root.resolve("docs")
        sub.mkdirs()
        sub.resolve("guide.md").writeText("guide")
        vault.root.resolve("top.md").writeText("top")

        val result = GlobTool(vault).execute(mapOf("pattern" to "**/*.md"))

        assertThat(result).contains("docs${java.io.File.separator}guide.md")
        assertThat(result).contains("top.md")
    }

    // ─────────────────────────────── GrepTool ────────────────────────────────

    @Test
    fun grep_filesWithMatches_returnsFilePath() = runTest {
        vault.root.resolve("match.md").writeText("hello world")
        vault.root.resolve("nomatch.md").writeText("nothing here")

        val result = GrepTool(vault).execute(mapOf("pattern" to "hello"))

        assertThat(result).contains("match.md")
        assertThat(result).doesNotContain("nomatch.md")
    }

    @Test
    fun grep_contentMode_returnsMatchingLineWithLineNumber() = runTest {
        vault.root.resolve("note.md").writeText("first line\nhello world\nthird line")

        val result = GrepTool(vault).execute(
            mapOf("pattern" to "hello", "output_mode" to "content")
        )

        assertThat(result).contains("2:")
        assertThat(result).contains("hello world")
        assertThat(result).doesNotContain("first line")
        assertThat(result).doesNotContain("third line")
    }

    @Test
    fun grep_caseInsensitive_matchesRegardlessOfCase() = runTest {
        vault.root.resolve("doc.md").writeText("Hello World")

        val result = GrepTool(vault).execute(
            mapOf("pattern" to "hello", "-i" to true)
        )

        assertThat(result).contains("doc.md")
    }

    @Test
    fun grep_caseSensitiveByDefault_noMatchOnWrongCase() = runTest {
        vault.root.resolve("doc.md").writeText("Hello World")

        val result = GrepTool(vault).execute(mapOf("pattern" to "hello"))

        assertThat(result).isEqualTo("(no matches)")
    }

    @Test
    fun grep_countMode_returnsCountString() = runTest {
        vault.root.resolve("a.md").writeText("foo\nfoo\nbar")
        vault.root.resolve("b.md").writeText("foo\nbaz")

        val result = GrepTool(vault).execute(
            mapOf("pattern" to "foo", "output_mode" to "count")
        )

        assertThat(result).contains("matches in")
        assertThat(result).contains("3 matches")
        assertThat(result).contains("2 files")
    }

    @Test
    fun grep_noMatches_returnsNoMatchesMessage() = runTest {
        vault.root.resolve("note.md").writeText("nothing relevant")

        val result = GrepTool(vault).execute(mapOf("pattern" to "xyzzy"))

        assertThat(result).isEqualTo("(no matches)")
    }

    @Test
    fun grep_invalidRegex_returnsError() = runTest {
        val result = GrepTool(vault).execute(mapOf("pattern" to "[invalid"))

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("regex")
    }

    @Test
    fun grep_missingPattern_returnsError() = runTest {
        val result = GrepTool(vault).execute(emptyMap())

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("pattern")
    }

    @Test
    fun grep_excludesDotGitContents() = runTest {
        val gitDir = vault.root.resolve(".git")
        gitDir.mkdirs()
        gitDir.resolve("config").writeText("hello from git")
        vault.root.resolve("real.md").writeText("hello from vault")

        val result = GrepTool(vault).execute(mapOf("pattern" to "hello"))

        assertThat(result).contains("real.md")
        assertThat(result).doesNotContain(".git")
        assertThat(result).doesNotContain("config")
    }

    @Test
    fun grep_contentMode_withContextAfter_includesContextLines() = runTest {
        vault.root.resolve("note.md").writeText("line1\nmatch\nafter1\nafter2\nother")

        val result = GrepTool(vault).execute(
            mapOf("pattern" to "match", "output_mode" to "content", "-A" to 2)
        )

        assertThat(result).contains("match")
        assertThat(result).contains("after1")
        assertThat(result).contains("after2")
        assertThat(result).doesNotContain("line1")
    }

    @Test
    fun grep_searchSingleFile_returnsResultsForThatFile() = runTest {
        vault.root.resolve("a.md").writeText("needle in a haystack")
        vault.root.resolve("b.md").writeText("needle here too")

        val result = GrepTool(vault).execute(
            mapOf("pattern" to "needle", "path" to "a.md")
        )

        assertThat(result).contains("a.md")
        assertThat(result).doesNotContain("b.md")
    }

    @Test
    fun grep_pathOutsideVault_returnsError() = runTest {
        val result = GrepTool(vault).execute(
            mapOf("pattern" to "test", "path" to "../../etc")
        )

        assertThat(result).startsWith("Error:")
        assertThat(result).contains("outside the vault")
    }
}
