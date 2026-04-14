package com.vela.app.ai.tools

import com.vela.app.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class GlobTool(private val vault: VaultManager) : Tool {
    override val name = "glob"
    override val displayName = "Find Files"
    override val icon = "🔍"
    override val description = "Find files by glob pattern (e.g. '**/*.md'). Excludes .git. Limit: 500 results."
    override val parameters = listOf(
        ToolParameter("pattern", "string", "Glob pattern (e.g. '**/*.md', '*.txt')"),
        ToolParameter("path", "string", "Base path to search from (default: vault root)", required = false),
        ToolParameter("type", "string", "Filter: 'file', 'dir', or 'any' (default: 'file')", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val pattern = args["pattern"] as? String
            ?: return@withContext "Error: pattern is required"
        val basePath = args["path"] as? String
        val typeFilter = args["type"] as? String ?: "file"
        val limit = 500

        val base = if (basePath != null) {
            vault.resolve(basePath) ?: return@withContext "Error: path '$basePath' is outside the vault"
        } else {
            vault.root
        }
        if (!base.exists()) return@withContext "Error: base path not found"

        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            return@withContext "Error: invalid glob pattern: ${e.message}"
        }

        val results = mutableListOf<String>()
        val basePath2 = base.toPath()
        Files.walk(basePath2)
            .filter { path ->
                val relative = basePath2.relativize(path)
                val relStr = relative.toString()
                if (relStr.isEmpty()) return@filter false  // skip base dir itself
                // Java's glob: "**/*.md" requires a path separator, so "notes.md" won't match.
                // Fix: also try with a dummy prefix so patterns like "**/*.md" match root-level files.
                val normPath = basePath2.fileSystem.getPath("_/$relStr")
                relative.none { it.toString() == ".git" } &&
                (matcher.matches(relative) || matcher.matches(normPath)) &&
                when (typeFilter) {
                    "dir" -> Files.isDirectory(path)
                    "file" -> Files.isRegularFile(path)
                    else -> true
                }
            }
            .limit(limit.toLong())
            .forEach { results.add(basePath2.relativize(it).toString()) }

        if (results.isEmpty()) "(no matches for pattern: $pattern)"
        else results.joinToString("\n")
    }
}

class GrepTool(private val vault: VaultManager) : Tool {
    override val name = "grep"
    override val displayName = "Search Content"
    override val icon = "🔍"
    override val description = "Search file contents with regex. output_mode: 'files_with_matches' (default), 'content', 'count'. Excludes .git."
    override val parameters = listOf(
        ToolParameter("pattern", "string", "Regex pattern to search for"),
        ToolParameter("path", "string", "File or directory to search in (default: vault root)", required = false),
        ToolParameter("output_mode", "string", "Output mode: 'files_with_matches', 'content', or 'count'", required = false),
        ToolParameter("-i", "boolean", "Case insensitive search", required = false),
        ToolParameter("-n", "boolean", "Show line numbers in content mode (default: true)", required = false),
        ToolParameter("-A", "integer", "Lines to show after each match", required = false),
        ToolParameter("-B", "integer", "Lines to show before each match", required = false),
        ToolParameter("-C", "integer", "Context lines before and after each match", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val patternStr = args["pattern"] as? String
            ?: return@withContext "Error: pattern is required"
        val searchPath = args["path"] as? String
        val outputMode = args["output_mode"] as? String ?: "files_with_matches"
        val caseInsensitive = args["-i"] as? Boolean ?: false
        val showLineNumbers = args["-n"] as? Boolean ?: true
        val contextAfter = maxOf(
            (args["-A"] as? Number)?.toInt() ?: 0,
            (args["-C"] as? Number)?.toInt() ?: 0
        )
        val contextBefore = maxOf(
            (args["-B"] as? Number)?.toInt() ?: 0,
            (args["-C"] as? Number)?.toInt() ?: 0
        )

        val base = if (searchPath != null) {
            vault.resolve(searchPath) ?: return@withContext "Error: path '$searchPath' is outside the vault"
        } else {
            vault.root
        }

        val flags = if (caseInsensitive) Pattern.CASE_INSENSITIVE else 0
        val regex = try {
            Pattern.compile(patternStr, flags)
        } catch (e: PatternSyntaxException) {
            return@withContext "Error: invalid regex: ${e.message}"
        }

        val matchingFiles = mutableListOf<String>()
        val contentResults = mutableListOf<String>()
        var totalCount = 0
        var fileMatchCount = 0

        fun searchFile(file: File) {
            val lines = try { file.readLines() } catch (_: Exception) { return }
            val relativePath = vault.root.toPath().relativize(file.toPath()).toString()
            val matchingLineIndices = lines.indices.filter { regex.matcher(lines[it]).find() }
            if (matchingLineIndices.isEmpty()) return

            fileMatchCount++
            matchingFiles.add(relativePath)
            totalCount += matchingLineIndices.size

            if (outputMode == "content" && contentResults.size < 500) {
                val shownIndices = mutableSetOf<Int>()
                matchingLineIndices.forEach { idx ->
                    ((idx - contextBefore).coerceAtLeast(0)..(idx + contextAfter).coerceAtMost(lines.lastIndex))
                        .forEach { shownIndices.add(it) }
                }
                contentResults.add("--")
                contentResults.add(relativePath)
                shownIndices.sorted().forEach { idx ->
                    val prefix = if (showLineNumbers) "${idx + 1}:" else ""
                    contentResults.add("$prefix${lines[idx]}")
                }
            }
        }

        if (base.isFile) {
            searchFile(base)
        } else {
            Files.walk(base.toPath())
                .filter { path ->
                    Files.isRegularFile(path) && path.none { it.toString() == ".git" }
                }
                .limit(200)
                .forEach { searchFile(it.toFile()) }
        }

        when (outputMode) {
            "content" -> if (contentResults.isEmpty()) "(no matches)" else contentResults.drop(1).joinToString("\n")
            "count" -> "$totalCount matches in $fileMatchCount files"
            else -> if (matchingFiles.isEmpty()) "(no matches)" else matchingFiles.joinToString("\n")
        }
    }
}
