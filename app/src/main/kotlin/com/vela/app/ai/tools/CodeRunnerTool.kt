package com.vela.app.ai.tools

import android.content.Context
import android.util.Log
import com.chaquo.python.PyException
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "CodeRunnerTool"

/**
 * "Code mode" — Claude writes Python instead of making N individual tool calls.
 *
 * How it works:
 *  1. A preamble is prepended that binds read_file(), write_file(), glob(), grep(),
 *     etc. as Python functions backed by Kotlin Tool implementations via
 *     [PythonToolBridge].
 *  2. The combined script executes via Chaquopy (embedded CPython, same process UID
 *     as the app — full vault filesystem access, no sandbox crossing).
 *  3. stdout is captured; only whatever the script print()s returns to Claude.
 *
 * Intermediate results (file contents, grep hits, loop counters) stay in Python
 * variables and NEVER consume Claude's context tokens.
 */
class CodeRunnerTool(private val context: Context) : Tool {

    override val name        = "run_code"
    override val displayName = "Run Code"
    override val icon        = "🐍"
    override val description = """
        Execute Python that calls vault tools as functions — more efficient than
        multiple individual tool calls because intermediate values never consume
        your context tokens. Only what you print() comes back.

        AVAILABLE FUNCTIONS
          read_file(path)                           → str
          read_file(path, offset=N, limit=N)        → str  (paginated)
          write_file(path, content)                 → str
          edit_file(path, old_str, new_str)         → str
          glob(pattern)                             → list[str]
          glob(pattern, path=subdir)                → list[str]
          grep(pattern, path='', output_mode='content') → str
          bash(command)                             → str
          fetch_url(url)                            → str
          search_web(query)                         → str

        Paths are relative to vault root (same as read_file / write_file tools).
        Full Python stdlib available: json, re, collections, pathlib, csv, etc.
        Use print() for output — everything printed is returned to you.

        WHEN TO USE
          ✓ Read several files, aggregate, summarise
          ✓ glob for files, read each, transform, write result
          ✓ Complex text processing across many files
          ✗ Single file read → use read_file directly
          ✗ git operations → use git tool
    """.trimIndent()

    override val parameters = listOf(
        ToolParameter(
            name        = "code",
            type        = "string",
            description = "Python code to execute. Use print() to produce output.",
        ),
    )

    companion object {
        // Injected before every script. Defines the vault tool API as plain Python
        // functions. stdout is redirected by Kotlin AFTER the preamble runs so
        // import noise is excluded from the result.
        private val PREAMBLE = """
from java import jclass as _jc
import json as _json, re as _re, collections as _col

_B = _jc('com.vela.app.ai.tools.PythonToolBridge')

def read_file(path, offset=None, limit=None):
    if offset is not None or limit is not None:
        return str(_B.readFileRange(path, int(offset or 1), int(limit or 2000)))
    return str(_B.readFile(path))

def write_file(path, content):
    return str(_B.writeFile(path, content))

def edit_file(path, old_str, new_str):
    return str(_B.editFile(path, old_str, new_str))

def glob(pattern, path=None):
    raw = str(_B.globIn(pattern, path)) if path else str(_B.glob(pattern))
    if raw.startswith('(no matches') or raw.startswith('Error:'):
        return []
    return [l for l in raw.splitlines() if l.strip()]

def grep(pattern, path='', output_mode='content'):
    return str(_B.grep(pattern, path, output_mode))

def bash(command):
    return str(_B.bash(command))

def fetch_url(url):
    return str(_B.fetchUrl(url))

def search_web(query):
    return str(_B.searchWeb(query))
""".trimIndent()
    }

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val code = args["code"] as? String
            ?: return@withContext "Error: 'code' is required"

        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val py       = Python.getInstance()
        val builtins = py.getBuiltins()
        val sys      = py.getModule("sys")
        val io       = py.getModule("io")

        // Fresh isolated globals per invocation — prevents state leaking between calls
        val globals = builtins.callAttr("dict")
        builtins.callAttr("exec", PREAMBLE, globals)

        // Redirect stdout AFTER preamble so import noise is excluded
        val origStdout = sys["stdout"]
        val buf        = io.callAttr("StringIO")
        sys["stdout"]  = buf

        return@withContext try {
            builtins.callAttr("exec", code, globals)
            buf.callAttr("getvalue").toString().trimEnd()
                .ifBlank { "(script completed — use print() to return output)" }
        } catch (e: PyException) {
            val msg = e.message ?: "Unknown Python error"
            Log.w(TAG, "run_code error: $msg")
            "Error:\n$msg"
        } finally {
            sys["stdout"] = origStdout
        }
    }
}
