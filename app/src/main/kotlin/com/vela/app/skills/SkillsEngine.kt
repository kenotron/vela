package com.vela.app.skills

    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext
    import java.io.File

    /**
     * Discovers and loads skills from vault-local and bundled skill directories.
     *
     * [getVaultSkillDirs] is a suspend lambda injected by AppModule:
     *   `{ vaultRegistry.getEnabledVaults().map { File(it.localPath, "skills") }.filter { it.exists() } }`
     *
     * [bundledSkillsDir] is the directory where assets/skills/ was extracted to cacheDir.
     * Both are injected as plain values so no Android Context is needed here.
     */
    class SkillsEngine(
        private val getVaultSkillDirs: suspend () -> List<File>,
        private val bundledSkillsDir: File,
    ) {
        suspend fun discoverAll(): List<SkillMeta> = withContext(Dispatchers.IO) {
            skillDirectories().flatMap { base ->
                base.listFiles { f -> f.isDirectory }
                    ?.mapNotNull { parseSkillDir(it) }
                    ?: emptyList()
            }
        }

        suspend fun search(query: String): List<SkillMeta> {
            val q = query.lowercase()
            return discoverAll().filter { q in it.name.lowercase() || q in it.description.lowercase() }
        }

        suspend fun info(name: String): SkillMeta? = discoverAll().firstOrNull { it.name == name }

        suspend fun load(name: String): SkillLoadResult = withContext(Dispatchers.IO) {
            val dir = skillDirectories()
                .flatMap { base ->
                    base.listFiles { f -> f.isDirectory && f.name == name }?.toList() ?: emptyList()
                }
                .firstOrNull() ?: return@withContext SkillLoadResult.NotFound(name)

            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.exists()) return@withContext SkillLoadResult.Error("SKILL.md missing in $name")

            val (_, body) = parseFrontmatter(skillFile.readText())
            SkillLoadResult.Content(body = body, skillDirectory = dir.absolutePath)
        }

        private suspend fun skillDirectories(): List<File> =
            getVaultSkillDirs() + listOf(bundledSkillsDir).filter { it.exists() }

        private fun parseSkillDir(dir: File): SkillMeta? {
            val skillFile = File(dir, "SKILL.md")
            if (!skillFile.exists()) return null
            val (fm, _) = parseFrontmatter(skillFile.readText())
            // Agent Skills spec: name field must equal the directory name
            val name        = fm["name"]?.takeIf { it == dir.name } ?: return null
            val description = fm["description"] ?: return null
            return SkillMeta(
                name            = name,
                description     = description,
                isFork          = fm["context"] == "fork",
                isUserInvocable = fm["user-invocable"] == "true",
                directory       = dir.absolutePath,
            )
        }

        private fun parseFrontmatter(content: String): Pair<Map<String, String>, String> {
            if (!content.startsWith("---")) return emptyMap<String, String>() to content
            val end = content.indexOf("\n---", 3).takeIf { it != -1 }
                ?: return emptyMap<String, String>() to content
            val fm = content.substring(3, end).trim().lines()
                .mapNotNull { line ->
                    line.indexOf(':').takeIf { it != -1 }?.let { i ->
                        line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
                    }
                }.toMap()
            return fm to content.substring(end + 4).trimStart('\n')
        }
    }
    