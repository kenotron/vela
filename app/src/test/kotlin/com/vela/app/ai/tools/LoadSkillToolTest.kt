package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.skills.SkillsEngine
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LoadSkillToolTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var engine: SkillsEngine
    private lateinit var tool: LoadSkillTool

    @Before fun setUp() {
        skillsDir = tmp.newFolder("skills")
        engine = SkillsEngine(
            getVaultSkillDirs = { listOf(skillsDir) },
            bundledSkillsDir  = tmp.newFolder("bundled"),
        )
        tool = LoadSkillTool(engine)
    }

    private fun createSkill(name: String, description: String = "Test skill $name"): File {
        val dir = File(skillsDir, name).also { it.mkdirs() }
        File(dir, "SKILL.md").writeText(
            """
            ---
            name: $name
            description: $description
            ---

            Body content for $name.
            """.trimIndent()
        )
        return dir
    }

    // ── list=true ──────────────────────────────────────────────────────────────

    @Test
    fun list_withSkillsPresent_returnsFormattedList() = runTest {
        createSkill("python-dev", "Python development patterns")

        val result = tool.execute(mapOf("list" to true))

        assertThat(result).contains("- **python-dev**:")
        assertThat(result).contains("Python development patterns")
    }

    @Test
    fun list_withNoSkills_returnsNoSkillsAvailable() = runTest {
        val result = tool.execute(mapOf("list" to true))

        assertThat(result).isEqualTo("(no skills available)")
    }

    @Test
    fun list_withMultipleSkills_returnsAllSkills() = runTest {
        createSkill("python-dev", "Python development")
        createSkill("go-patterns", "Go patterns")

        val result = tool.execute(mapOf("list" to true))

        assertThat(result).contains("- **python-dev**:")
        assertThat(result).contains("- **go-patterns**:")
    }

    // ── skill_name ─────────────────────────────────────────────────────────────

    @Test
    fun skillName_withExistingSkill_returnsBodyAndSkillDirectory() = runTest {
        createSkill("python-dev")

        val result = tool.execute(mapOf("skill_name" to "python-dev"))

        assertThat(result).contains("Body content for python-dev")
        assertThat(result).contains("skill_directory:")
        assertThat(result).contains("python-dev")
    }

    @Test
    fun skillName_withUnknownSkill_returnsNotFound() = runTest {
        val result = tool.execute(mapOf("skill_name" to "nonexistent"))

        assertThat(result).isEqualTo("Skill 'nonexistent' not found")
    }

    // ── search ─────────────────────────────────────────────────────────────────

    @Test
    fun search_withMatchingSkills_returnsFilteredList() = runTest {
        createSkill("python-dev", "Python development")
        createSkill("go-patterns", "Go patterns")

        val result = tool.execute(mapOf("search" to "python"))

        assertThat(result).contains("- **python-dev**:")
        assertThat(result).doesNotContain("go-patterns")
    }

    @Test
    fun search_withNoMatches_returnsNoMatchingMessage() = runTest {
        createSkill("python-dev", "Python development")

        val result = tool.execute(mapOf("search" to "rust"))

        assertThat(result).isEqualTo("No skills matching: rust")
    }

    // ── info ───────────────────────────────────────────────────────────────────

    @Test
    fun info_withExistingSkill_returnsMetadata() = runTest {
        createSkill("python-dev", "Python development patterns")

        val result = tool.execute(mapOf("info" to "python-dev"))

        assertThat(result).contains("python-dev")
        assertThat(result).contains("Python development patterns")
        assertThat(result).contains("directory:")
    }

    @Test
    fun info_withUnknownSkill_returnsNotFound() = runTest {
        val result = tool.execute(mapOf("info" to "nonexistent"))

        assertThat(result).isEqualTo("Skill 'nonexistent' not found")
    }

    // ── no args error ──────────────────────────────────────────────────────────

    @Test
    fun execute_withNoArgs_returnsErrorMessage() = runTest {
        val result = tool.execute(emptyMap())

        assertThat(result).contains("Error")
        assertThat(result).contains("list")
        assertThat(result).contains("skill_name")
    }
}
