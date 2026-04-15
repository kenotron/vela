package com.vela.app.ai.tools

import kotlinx.coroutines.runBlocking

/**
 * Static bridge between Chaquopy Python code and Vela's ToolRegistry.
 *
 * Python accesses this via Chaquopy's jclass mechanism:
 *
 *   from java import jclass as _jc
 *   _B = _jc('com.vela.app.ai.tools.PythonToolBridge')
 *   content = _B.readFile('Notes/foo.md')
 *
 * @JvmStatic generates a true Java static method on the class, so Python can call
 * _B.readFile(...) directly without going through INSTANCE.
 *
 * Initialised once by AppModule immediately after ToolRegistry is built.
 */
object PythonToolBridge {

    @Volatile private var _registry: ToolRegistry? = null

    internal fun init(registry: ToolRegistry) {
        _registry = registry
    }

    private fun exec(name: String, args: Map<String, Any>): String =
        runBlocking {
            _registry?.execute(name, args) ?: "Error: PythonToolBridge not initialised"
        }

    // ── File I/O ──────────────────────────────────────────────────────────────

    @JvmStatic fun readFile(path: String): String =
        exec("read_file", mapOf("file_path" to path))

    @JvmStatic fun readFileRange(path: String, offset: Int, limit: Int): String =
        exec("read_file", mapOf("file_path" to path, "offset" to offset, "limit" to limit))

    @JvmStatic fun writeFile(path: String, content: String): String =
        exec("write_file", mapOf("file_path" to path, "content" to content))

    @JvmStatic fun editFile(path: String, oldStr: String, newStr: String): String =
        exec("edit_file", mapOf("file_path" to path, "old_string" to oldStr, "new_string" to newStr))

    // ── Search ────────────────────────────────────────────────────────────────

    /** Returns newline-separated relative paths, or "(no matches…)" */
    @JvmStatic fun glob(pattern: String): String =
        exec("glob", mapOf("pattern" to pattern))

    @JvmStatic fun globIn(pattern: String, basePath: String): String =
        exec("glob", mapOf("pattern" to pattern, "path" to basePath))

    /** outputMode: "files_with_matches" | "content" | "count" */
    @JvmStatic fun grep(pattern: String, path: String, outputMode: String): String =
        exec("grep", buildMap {
            put("pattern", pattern)
            if (path.isNotBlank()) put("path", path)
            put("output_mode", outputMode)
        })

    // ── Shell ─────────────────────────────────────────────────────────────────

    @JvmStatic fun bash(command: String): String =
        exec("bash", mapOf("command" to command))

    // ── Web ───────────────────────────────────────────────────────────────────

    @JvmStatic fun fetchUrl(url: String): String =
        exec("fetch_url", mapOf("url" to url))

    @JvmStatic fun searchWeb(query: String): String =
        exec("search_web", mapOf("query" to query))
}
