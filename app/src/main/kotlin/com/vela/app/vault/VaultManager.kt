package com.vela.app.vault

import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides safe path resolution within a vault root directory.
 *
 * Any path that would resolve outside the vault root returns null, preventing
 * directory traversal attacks when the AI passes file paths.
 *
 * Additionally gates access by enabled vaults: if [enabledVaultPaths] is non-empty,
 * the resolved file must fall within one of those vault directories. If the set is
 * empty (first launch — no vaults configured yet), all paths within root are allowed.
 *
 * Designed for constructor injection — trivially testable with TemporaryFolder.
 *
 * AppModule wires: VaultManager(File(ctx.filesDir, "vaults"), enabledVaultPaths).also { it.init() }
 */
class VaultManager(
    val root: File,
    private val enabledVaultPaths: StateFlow<Set<String>>,
) {
    /**
     * Session-level vault restriction, set by InferenceEngine before each turn.
     * When non-empty, ONLY paths inside these canonical directories are accessible,
     * regardless of the global [enabledVaultPaths]. Cleared after every turn.
     *
     * This makes the session vault chip actually gate tool access — not just
     * the system prompt description of available vaults.
     */
    private val sessionActivePaths = AtomicReference<Set<String>>(emptySet())

    fun setSessionVaultPaths(canonicalPaths: Set<String>) {
        sessionActivePaths.set(canonicalPaths)
    }
    fun clearSessionVaultPaths() {
        sessionActivePaths.set(emptySet())
    }

    /** Creates the vault root directory (and any parents) if not already present. */
    fun init() {
        root.mkdirs()
    }

    /** Returns true if the vault root exists. */
    fun isInitialized(): Boolean = root.exists()

    /**
     * Resolves [path] to a [File] within [root], gated by [enabledVaultPaths].
     *
     * - Strips a leading "/" so AI-provided absolute-style paths like
     *   "/Personal/Notes/today.md" are treated as vault-relative.
     * - Returns null if the resolved canonical path would escape [root]
     *   (traversal protection against "../../etc/passwd" style inputs).
     * - Returns null if the resolved file is not within an enabled vault
     *   (access control — disabled vault = no file access).
     */
    fun resolve(path: String): File? {
        // Path starts with "/" — could be a genuine Android filesystem path already
        // inside the vault tree (e.g. /data/data/com.vela.app/files/vaults/abc123/…),
        // OR a vault-relative path with a leading "/" as supplied by the AI
        // (e.g. /Personal/Notes/today.md).
        //
        // Strategy: try absolute first; if NOT inside vault root, fall back to
        // treating it as vault-relative by stripping the leading "/".
        if (path.startsWith("/")) {
            val absFile = File(path)
            try {
                val canonical = absFile.canonicalPath
                val rootCanonical = root.canonicalPath
                if (canonical.startsWith(rootCanonical + File.separator) || canonical == rootCanonical) {
                    return if (isWithinEnabledVault(absFile)) absFile else null
                }
            } catch (_: Exception) { /* fall through to vault-relative resolution */ }
            // Not inside vault when treated as absolute — treat as vault-relative
            return resolveRelative(path.removePrefix("/"))
        }
        return resolveRelative(path)
    }

    private fun resolveRelative(relativePath: String): File? {
        val candidate = File(root, relativePath)
        return try {
            val canonical = candidate.canonicalPath
            val rootCanonical = root.canonicalPath
            if (canonical.startsWith(rootCanonical + File.separator) || canonical == rootCanonical) {
                if (isWithinEnabledVault(candidate)) candidate else null
            } else null
        } catch (_: Exception) { null }
    }

    /**
     * Returns true if [file] falls within at least one enabled vault.
     *
     * When [enabledVaultPaths] is empty (no vaults configured — first launch),
     * all access is permitted so the user can set up their first vault.
     */
    private fun isWithinEnabledVault(file: File): Boolean {
        // Session-active paths take priority when set (vault chip toggled off → deny access).
        // Fall back to the global enabled set when no session restriction is in effect.
        val session = sessionActivePaths.get()
        val effective = if (session.isNotEmpty()) session else enabledVaultPaths.value
        if (effective.isEmpty()) return true  // no vaults configured — don't block
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return effective.any { vaultCanonical ->
            canonical.startsWith(vaultCanonical + File.separator) || canonical == vaultCanonical
        }
    }
}
