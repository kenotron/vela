package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.ui.miniapp.VelaTheme
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * Unit tests for [RendererAssembler].
 * Run: ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.RendererAssemblerTest"
 */
class RendererAssemblerTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val defaultTheme = VelaTheme(isDark = false, primaryColor = "#7C4DFF")

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun fakeVaultRegistry(root: java.io.File): VaultRegistry =
        Mockito.mock(VaultRegistry::class.java).also { mock ->
            Mockito.`when`(mock.enabledVaults).thenReturn(
                MutableStateFlow(
                    listOf(VaultEntity(id = "test", name = "Test", localPath = root.absolutePath))
                )
            )
        }

    private fun emptyVaultRegistry(): VaultRegistry =
        Mockito.mock(VaultRegistry::class.java).also { mock ->
            Mockito.`when`(mock.enabledVaults).thenReturn(MutableStateFlow(emptyList()))
        }

    // ── Data class shape ─────────────────────────────────────────────────────

    @Test
    fun `AssemblyResult carries result skillUsed and archetype`() {
        val success = GenerationResult.Success("/path/to/renderer.html")
        val archetype = ArchetypeDetector.DetectionResult("recipe", 0.9f, "Recipe")

        val result = RendererAssembler.AssemblyResult(
            result    = success,
            skillUsed = null,
            archetype = archetype,
        )

        assertThat(result.result).isEqualTo(success)
        assertThat(result.skillUsed).isNull()
        assertThat(result.archetype).isEqualTo(archetype)
    }

    // ── Skill-based path ─────────────────────────────────────────────────────

    /**
     * When a skill match exists and the template loads successfully,
     * [RendererAssembler.assemble] must write the template HTML to disk and
     * return [GenerationResult.Success] — no LLM call needed.
     */
    @Test
    fun `assemble writes skill template to disk and returns Success`() = runTest {
        val root = tempDir.newFolder()
        val templateHtml = "<html><body>recipe template</body></html>"

        // ArchetypeDetector stub — returns high-confidence "recipe"
        val mockDetector = Mockito.mock(ArchetypeDetector::class.java)
        Mockito.`when`(
            mockDetector.detect(Mockito.anyString(), Mockito.anyString())
        ).thenReturn(
            ArchetypeDetector.DetectionResult("recipe", 0.95f, "Recipe")
        )

        // SkillLibrary stub — one matching skill with a loadable template
        val mockSkillLib = Mockito.mock(SkillLibrary::class.java)
        val fakeSkill = SkillLibrary.Skill(
            id                  = "recipe-cookalong",
            name                = "Recipe Cookalong",
            archetypes          = listOf("recipe"),
            blocks              = listOf("step-through", "checklist"),
            confidenceThreshold = 0.7f,
            description         = "Cook along with a recipe",
            isVaultSkill        = false,
        )
        Mockito.`when`(
            mockSkillLib.findMatches(Mockito.anyString(), Mockito.anyFloat(), Mockito.anyInt())
        ).thenReturn(listOf(SkillLibrary.SkillMatch(fakeSkill, 0.95f)))
        Mockito.`when`(
            mockSkillLib.loadTemplate(Mockito.anyString(), Mockito.anyBoolean())
        ).thenReturn(templateHtml)

        // RendererGenerator — should NOT be called in skill path (no feedback)
        val mockGenerator = Mockito.mock(RendererGenerator::class.java)
        val mockExtractor = Mockito.mock(ContentExtractor::class.java)

        val assembler = RendererAssembler(
            archetypeDetector = mockDetector,
            skillLibrary      = mockSkillLib,
            rendererGenerator = mockGenerator,
            vaultRegistry     = fakeVaultRegistry(root),
            contentExtractor  = mockExtractor,
        )

        val phasesRecorded = mutableListOf<Pair<Int, String>>()
        val assemblyResult = assembler.assemble(
            itemPath      = "recipes/pasta.md",
            itemContent   = "# Pasta\nBoil water, cook pasta.",
            contentType   = "recipe",
            theme         = defaultTheme,
            layout        = "phone",
            rendererType  = RendererType.READER,
            onPhase       = { idx, detail -> phasesRecorded += idx to detail },
        )

        // Result must be Success
        assertThat(assemblyResult.result).isInstanceOf(GenerationResult.Success::class.java)
        val path = (assemblyResult.result as GenerationResult.Success).rendererPath
        assertThat(path).endsWith("renderer.html")

        // Skill and archetype must be populated
        assertThat(assemblyResult.skillUsed).isEqualTo(fakeSkill)
        assertThat(assemblyResult.archetype?.archetype).isEqualTo("recipe")

        // HTML must be written to disk
        val writtenHtml = java.io.File(path).readText()
        assertThat(writtenHtml).isEqualTo(templateHtml)

        // All 5 phases must fire (indices 0-4)
        assertThat(phasesRecorded.map { it.first }).containsExactly(0, 1, 2, 3, 4).inOrder()

        // RendererGenerator must NOT have been called
        Mockito.verifyNoInteractions(mockGenerator)
    }

    // ── Fallback / no-vault failure ───────────────────────────────────────────

    /**
     * If no vault is configured and the skill path is triggered,
     * assemble must return [GenerationResult.Failure] — not throw.
     */
    @Test
    fun `assemble returns Failure when skill matched but no vault configured`() = runTest {
        val mockDetector = Mockito.mock(ArchetypeDetector::class.java)
        Mockito.`when`(
            mockDetector.detect(Mockito.anyString(), Mockito.anyString())
        ).thenReturn(ArchetypeDetector.DetectionResult("recipe", 0.95f, "Recipe"))

        val mockSkillLib = Mockito.mock(SkillLibrary::class.java)
        val fakeSkill = SkillLibrary.Skill(
            id = "recipe-cookalong", name = "Recipe Cookalong",
            archetypes = listOf("recipe"), blocks = listOf("step-through"),
            confidenceThreshold = 0.7f, description = "", isVaultSkill = false,
        )
        Mockito.`when`(
            mockSkillLib.findMatches(Mockito.anyString(), Mockito.anyFloat(), Mockito.anyInt())
        ).thenReturn(listOf(SkillLibrary.SkillMatch(fakeSkill, 0.95f)))
        Mockito.`when`(
            mockSkillLib.loadTemplate(Mockito.anyString(), Mockito.anyBoolean())
        ).thenReturn("<html/>")

        val mockGenerator = Mockito.mock(RendererGenerator::class.java)
        val mockExtractor = Mockito.mock(ContentExtractor::class.java)

        val assembler = RendererAssembler(
            archetypeDetector = mockDetector,
            skillLibrary      = mockSkillLib,
            rendererGenerator = mockGenerator,
            vaultRegistry     = emptyVaultRegistry(),
            contentExtractor  = mockExtractor,
        )

        val result = assembler.assemble(
            itemPath     = "recipes/pasta.md",
            itemContent  = "# Pasta",
            contentType  = "recipe",
            theme        = defaultTheme,
            layout       = "phone",
            rendererType = RendererType.READER,
        )

        assertThat(result.result).isInstanceOf(GenerationResult.Failure::class.java)
        Mockito.verifyNoInteractions(mockGenerator)
    }
}
