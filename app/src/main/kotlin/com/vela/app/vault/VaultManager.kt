package com.vela.app.vault

import java.io.File

/**
 * Provides safe path resolution within a vault root directory.
 *
 * Any path that would resolve outside the vault root returns null, preventing
 * directory traversal attacks when the AI passes file paths.
 *
 * Designed for constructor injection — no Android Context dependency,
 * trivially testable with TemporaryFolder.
 *
 * AppModule wires: VaultManager(File(ctx.filesDir, "vaults")).also { it.init() }
 */
class VaultManager(val root: File) {

    /** Creates the vault root directory (and any parents) if not already present. */
    fun init() {
        root.mkdirs()
    }

    /** Returns true if the vault root exists. */
    fun isInitialized(): Boolean = root.exists()

    /**
     * Resolves [path] to a [File] within [root].
     *
     * - Strips a leading "/" so AI-provided absolute-style paths like
     *   "/Personal/Notes/today.md" are treated as vault-relative.
     * - Returns null if the resolved canonical path would escape [root]
     *   (traversal protection against "../../etc/passwd" style inputs).
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
                    return absFile  // genuine absolute path already inside vault
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
            if (canonical.startsWith(rootCanonical + File.separator) || canonical == rootCanonical) candidate
            else null
        } catch (_: Exception) { null }
    }
}
