package com.vela.app.ai

import android.content.Context
import android.content.res.AssetManager
import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

class SkillLibraryTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var library: SkillLibrary
    private lateinit var vaultSkillsDir: File

    @Before fun setUp() {
        // Asset skills: return empty so vault path is isolated
        val assetManager = Mockito.mock(AssetManager::class.java)
        Mockito.`when`(assetManager.list("skills")).thenReturn(emptyArray())
        val context = Mockito.mock(Context::class.java)
        Mockito.`when`(context.assets).thenReturn(assetManager)

        // Vault pointing at a temp dir with a .vela/skills sub-directory
        val vaultDir = tmp.newFolder("vault")
        vaultSkillsDir = File(vaultDir, ".vela/skills").also { it.mkdirs() }
        val vault = VaultEntity(id = "v1", name = "Test Vault", localPath = vaultDir.absolutePath)
        val vaultRegistry = Mockito.mock(VaultRegistry::class.java)
        Mockito.`when`(vaultRegistry.enabledVaults).thenReturn(MutableStateFlow(listOf(vault)))

        library = SkillLibrary(context, vaultRegistry)
    }

    // ── SKILL.md frontmatter parsing ───────────────────────────────────────

    @Test fun `vault SKILL_md with frontmatter is loaded and parsed`() = runTest {
        val skillDir = File(vaultSkillsDir, "my-skill").also { it.mkdirs() }
        File(skillDir, "SKILL.md").writeText(
            """
            ---
            name: My Skill
            archetypes: [test, sample]
            blocks: [vela-checklist]
            confidence_threshold: 0.75
            description: A test skill
            ---
            """.trimIndent()
        )

        val skills = library.loadAllSkills()

        assertThat(skills).hasSize(1)
        assertThat(skills[0].name).isEqualTo("My Skill")
        assertThat(skills[0].description).isEqualTo("A test skill")
        assertThat(skills[0].confidenceThreshold).isEqualTo(0.75f)
        assertThat(skills[0].isVaultSkill).isTrue()
    }

    @Test fun `extractFrontmatter strips delimiters and returns only YAML content`() = runTest {
        // Frontmatter with trailing body content — only the YAML section should be parsed.
        val skillDir = File(vaultSkillsDir, "fm-skill").also { it.mkdirs() }
        File(skillDir, "SKILL.md").writeText(
            """
            ---
            name: FM Skill
            archetypes: [alpha]
            blocks: [vela-kanban]
            confidence_threshold: 0.8
            description: Frontmatter test
            ---

            This body text should not interfere with YAML parsing.
            """.trimIndent()
        )

        val skills = library.loadAllSkills()

        assertThat(skills).hasSize(1)
        assertThat(skills[0].name).isEqualTo("FM Skill")
        assertThat(skills[0].confidenceThreshold).isEqualTo(0.8f)
    }

    @Test fun `skill directory without SKILL_md is silently skipped`() = runTest {
        // A dir with only skill.yaml (old format) should not be loaded
        val skillDir = File(vaultSkillsDir, "old-skill").also { it.mkdirs() }
        File(skillDir, "skill.yaml").writeText(
            """
            name: Old Skill
            archetypes: [legacy]
            blocks: [vela-checklist]
            confidence_threshold: 0.7
            description: Old format skill
            """.trimIndent()
        )

        val skills = library.loadAllSkills()

        assertThat(skills).isEmpty()
    }
}
