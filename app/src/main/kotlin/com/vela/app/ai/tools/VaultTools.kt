package com.vela.app.ai.tools

import com.vela.app.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ReadFileTool(private val vault: VaultManager) : Tool {
    override val name = "read_file"
    override val displayName = "Read File"
    override val icon = "📄"
    override val description = "Reads a file from the vault. Returns content with line numbers (cat -n format). If path is a directory, returns a listing."
    override val parameters = listOf(
        ToolParameter("file_path", "string", "Path to the file or directory (relative to vault root)"),
        ToolParameter("offset", "integer", "Line number to start reading from (1-indexed)", required = false),
        ToolParameter("limit", "integer", "Maximum number of lines to return (default: 2000)", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val path = args["file_path"] as? String
            ?: return@withContext "Error: file_path is required"
        val offset = (args["offset"] as? Number)?.toInt()?.let { it - 1 }?.coerceAtLeast(0) ?: 0
        val limit = (args["limit"] as? Number)?.toInt() ?: 2000

        val file = vault.resolve(path)
            ?: return@withContext "Error: path '$path' is outside the vault"
        if (!file.exists()) return@withContext "Error: '$path' not found"

        if (file.isDirectory) {
            buildString {
                appendLine("Directory: $path")
                appendLine()
                file.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    ?.forEach { entry ->
                        val type = if (entry.isDirectory) "  DIR " else "  FILE"
                        appendLine("$type  ${entry.name}")
                    }
            }
        } else {
            val lines = file.readLines()
            val selected = lines.drop(offset).take(limit)
            if (selected.isEmpty()) return@withContext "(empty file)"
            selected.mapIndexed { i, line ->
                val lineNum = (offset + i + 1).toString().padStart(6)
                "$lineNum\t$line"
            }.joinToString("\n")
        }
    }
}

class WriteFileTool(private val vault: VaultManager) : Tool {
    override val name = "write_file"
    override val displayName = "Write File"
    override val icon = "✏️"
    override val description = "Creates or overwrites a file in the vault. Creates parent directories automatically."
    override val parameters = listOf(
        ToolParameter("file_path", "string", "Path to the file (relative to vault root)"),
        ToolParameter("content", "string", "Content to write"),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val path = args["file_path"] as? String
            ?: return@withContext "Error: file_path is required"
        val content = args["content"] as? String
            ?: return@withContext "Error: content is required"

        val file = vault.resolve(path)
            ?: return@withContext "Error: path '$path' is outside the vault"
        file.parentFile?.mkdirs()
        file.writeText(content)
        val lineCount = content.lines().size
        "Wrote $lineCount lines to $path"
    }
}

class EditFileTool(private val vault: VaultManager) : Tool {
    override val name = "edit_file"
    override val displayName = "Edit File"
    override val icon = "✏️"
    override val description = "Performs exact string replacement in a file. Fails if old_string is not found or occurs multiple times (unless replace_all=true)."
    override val parameters = listOf(
        ToolParameter("file_path", "string", "Path to the file (relative to vault root)"),
        ToolParameter("old_string", "string", "The exact text to find and replace"),
        ToolParameter("new_string", "string", "The replacement text"),
        ToolParameter("replace_all", "boolean", "Replace all occurrences (default: false)", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val path = args["file_path"] as? String
            ?: return@withContext "Error: file_path is required"
        val oldStr = args["old_string"] as? String
            ?: return@withContext "Error: old_string is required"
        val newStr = args["new_string"] as? String
            ?: return@withContext "Error: new_string is required"
        val replaceAll = args["replace_all"] as? Boolean ?: false

        val file = vault.resolve(path)
            ?: return@withContext "Error: path '$path' is outside the vault"
        if (!file.exists()) return@withContext "Error: '$path' not found"

        val content = file.readText()
        val count = content.split(oldStr).size - 1

        when {
            count == 0 ->
                "Error: old_string not found in $path"
            count > 1 && !replaceAll ->
                "Error: old_string found $count times in $path — use replace_all=true or provide more context to make it unique"
            else -> {
                val newContent = if (replaceAll) content.replace(oldStr, newStr)
                                 else content.replaceFirst(oldStr, newStr)
                file.writeText(newContent)
                "Replaced ${if (replaceAll) count else 1} occurrence(s) in $path"
            }
        }
    }
}
