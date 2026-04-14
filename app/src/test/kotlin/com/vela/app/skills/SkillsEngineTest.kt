package com.vela.app.skills

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillsEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var engine: SkillsEngine

    @Before fun setUp() {
        skillsDir = tmp.newFolder("skills")
        engine = SkillsEngine(
            getVaultSkillDirs = { listOf(skillsDir) },
            bundledSkillsDir  = tmp.newFolder("bundled"),
        )
    }

    private fun createSkill(name: String, extraFrontmatter: String = ""): File {
        val dir = File(skillsDir, name).also { it.mkdirs() }
        File(dir, "SKILL.md").writeText(
            """
            ---
            name: $name
            description: Test skill $name
            $extraFrontmatter
            ---

            This is the body of $name.
            """.trimIndent()
        )
        return dir
    }

    @Test fun `skill with valid SKILL_md is discovered`() = runBlocking {
        createSkill("my-skill")
        val skills = engine.discoverAll()
        assertThat(skills).hasSize(1)
        assertThat(skills[0].name).isEqualTo("my-skill")
        assertThat(skills[0].description).isEqualTo("Test skill my-skill")
    }

    @Test fun `load returns Content with body and skillDirectory`() = runBlocking {
        createSkill("python-standards")
        val result = engine.load("python-standards")
        assertThat(result).isInstanceOf(SkillLoadResult.Content::class.java)
        val content = result as SkillLoadResult.Content
        assertThat(content.body).contains("This is the body of python-standards")
        assertThat(content.skillDirectory).endsWith("python-standards")
    }

    @Test fun `skill with mismatched name field not discovered (spec enforcement)`() = runBlocking {
        val dir = File(skillsDir, "my-skill").also { it.mkdirs() }
        File(dir, "SKILL.md").writeText(
            """
            ---
            name: wrong-name
            description: Bad
            ---
            Body.
            """.trimIndent()
        )
        assertThat(engine.discoverAll()).isEmpty()
    }

    @Test fun `load returns NotFound for unknown skill`() = runBlocking {
        val result = engine.load("does-not-exist")
        assertThat(result).isInstanceOf(SkillLoadResult.NotFound::class.java)
    }

    @Test fun `search filters by name and description`() = runBlocking {
        createSkill("python-standards")
        createSkill("go-patterns")
        val results = engine.search("python")
        assertThat(results).hasSize(1)
        assertThat(results[0].name).isEqualTo("python-standards")
    }

    @Test fun `fork context flag parsed correctly`() = runBlocking {
        createSkill("fork-skill", "context: fork")
        val meta = engine.info("fork-skill")
        assertThat(meta).isNotNull()
        assertThat(meta!!.isFork).isTrue()
    }
}
