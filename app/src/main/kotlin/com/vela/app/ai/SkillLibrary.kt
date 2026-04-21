package com.vela.app.ai

import android.content.Context
import com.vela.app.vault.VaultRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the skill library — both the in-app baseline (assets/skills/)
 * and vault-extensible skills (.vela/skills/).
 *
 * Vault skills take precedence over in-app skills when both match on skill id.
 */
@Singleton
class SkillLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRegistry: VaultRegistry,
) {
    data class Skill(
        val id: String,
        val name: String,
        val archetypes: List<String>,
        val blocks: List<String>,
        val confidenceThreshold: Float,
        val description: String,
        val isVaultSkill: Boolean = false,
    )

    data class SkillMatch(
        val skill: Skill,
        val confidence: Float,
    )

    /**
     * Returns skills ranked by archetype match confidence, up to [limit].
     * Vault skills take precedence via deduplication on skill id.
     */
    suspend fun findMatches(
        archetype: String,
        confidence: Float,
        limit: Int = 3,
    ): List<SkillMatch> = withContext(Dispatchers.IO) {
        loadAllSkills()
            .mapNotNull { skill ->
                val matches = skill.archetypes.any { tag ->
                    tag.equals(archetype, ignoreCase = true) ||
                    archetype.contains(tag, ignoreCase = true) ||
                    tag.contains(archetype, ignoreCase = true)
                }
                if (!matches) null else SkillMatch(skill, confidence)
            }
            .sortedByDescending { it.confidence }
            .take(limit)
    }

    /**
     * Returns all available skills — vault skills first, then in-app.
     * Vault skills with the same id as an in-app skill override the in-app one.
     */
    suspend fun loadAllSkills(): List<Skill> = withContext(Dispatchers.IO) {
        val vaultSkills = loadVaultSkills()
        val assetSkills = loadAssetSkills()
        val vaultIds    = vaultSkills.map { it.id }.toSet()
        vaultSkills + assetSkills.filter { it.id !in vaultIds }
    }

    /** Loads the template HTML for a skill. Returns null if not found. */
    suspend fun loadTemplate(skillId: String, isVaultSkill: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (isVaultSkill) {
                val vault = vaultRegistry.enabledVaults.value.firstOrNull() ?: return@withContext null
                File(vault.localPath, ".vela/skills/$skillId/template.html")
                    .takeIf { it.exists() }
                    ?.readText()
            } else {
                runCatching {
                    context.assets.open("skills/$skillId/template.html").bufferedReader().readText()
                }.getOrNull()
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun loadAssetSkills(): List<Skill> =
        runCatching { context.assets.list("skills") }
            .getOrNull()
            ?.mapNotNull { dir -> parseAssetSkill(dir) }
            ?: emptyList()

    private fun loadVaultSkills(): List<Skill> {
        val vault = vaultRegistry.enabledVaults.value.firstOrNull() ?: return emptyList()
        val dir   = File(vault.localPath, ".vela/skills")
        return if (!dir.exists()) emptyList()
        else dir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { parseVaultSkill(it) }
            ?: emptyList()
    }

    private fun parseAssetSkill(id: String): Skill? = runCatching {
        val md = context.assets.open("skills/$id/SKILL.md").bufferedReader().readText()
        parseYaml(id, extractFrontmatter(md), isVaultSkill = false)
    }.getOrNull()

    private fun parseVaultSkill(dir: File): Skill? = runCatching {
        val md = File(dir, "SKILL.md").readText()
        parseYaml(dir.name, extractFrontmatter(md), isVaultSkill = true)
    }.getOrNull()

    /**
     * Extracts YAML content from between the first pair of `---` delimiters in a markdown file.
     * Returns the full content unchanged if no frontmatter delimiters are found (graceful fallback).
     */
    private fun extractFrontmatter(content: String): String {
        val lines = content.lines()
        val firstDelim = lines.indexOfFirst { it.trim() == "---" }
        if (firstDelim < 0) return content  // no frontmatter → treat whole file as YAML
        val secondDelim = lines.drop(firstDelim + 1).indexOfFirst { it.trim() == "---" }
        if (secondDelim < 0) return content  // unclosed frontmatter → treat whole file as YAML
        return lines.drop(firstDelim + 1).take(secondDelim).joinToString("\n")
    }

    private fun parseYaml(id: String, yaml: String, isVaultSkill: Boolean): Skill {
        val lines = yaml.lines().associate { line ->
            val idx = line.indexOf(':')
            if (idx < 0) "" to "" else line.substring(0, idx).trim() to line.substring(idx + 1).trim()
        }
        fun parseList(raw: String?) =
            raw?.removePrefix("[")?.removeSuffix("]")
                ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
                ?: emptyList()
        return Skill(
            id                  = id,
            name                = lines["name"] ?: id,
            archetypes          = parseList(lines["archetypes"]),
            blocks              = parseList(lines["blocks"]),
            confidenceThreshold = lines["confidence_threshold"]?.toFloatOrNull() ?: 0.7f,
            description         = lines["description"] ?: "",
            isVaultSkill        = isVaultSkill,
        )
    }
}
