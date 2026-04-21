package com.vela.app.ai

import com.vela.app.ui.miniapp.VelaTheme
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the full skill-based mini app generation pipeline.
 *
 * For skill-matched content (no feedback): loads the skill template directly
 * and writes it to disk — NO LLM call needed for HTML structure.
 *
 * For unmatched content or feedback refinement: falls back to the
 * open-ended [RendererGenerator] path (LLM generates arbitrary HTML).
 *
 * This is the single entry point for mini app generation from [MiniAppViewModel].
 */
@Singleton
class RendererAssembler @Inject constructor(
    private val archetypeDetector: ArchetypeDetector,
    private val skillLibrary: SkillLibrary,
    private val rendererGenerator: RendererGenerator,
    private val vaultRegistry: VaultRegistry,
    private val contentExtractor: ContentExtractor,
) {
    data class AssemblyResult(
        val result: GenerationResult,
        val skillUsed: SkillLibrary.Skill?,
        val archetype: ArchetypeDetector.DetectionResult?,
    )

    /**
     * Detects archetype, matches skill, assembles renderer.
     *
     * Calls [onPhase] with (index 0-4, detail string) as each build phase activates.
     * Index mapping: 0=detecting, 1=skill-selected, 2=assembling-blocks, 3=building, 4=saving.
     */
    suspend fun assemble(
        itemPath: String,
        itemContent: String,
        contentType: String,
        theme: VelaTheme,
        layout: String,
        rendererType: RendererType,
        feedback: String? = null,
        existingHtml: String? = null,
        onPhase: (index: Int, detail: String) -> Unit = { _, _ -> },
        onToken: ((String) -> Unit)? = null,
    ): AssemblyResult = withContext(Dispatchers.IO) {
        // Phase 0: Detecting content type
        onPhase(0, contentType)
        val detection = archetypeDetector.detect(contentType, itemContent)
        // Phase 1: Skill selected
        onPhase(1, "${detection.displayLabel} · ${(detection.confidence * 100).toInt()}%")

        val matches  = skillLibrary.findMatches(detection.archetype, detection.confidence)
        val topSkill = matches.firstOrNull()?.skill

        // Skill-based path: use template directly (no LLM for HTML) — only when no feedback
        if (topSkill != null && feedback == null) {
            onPhase(2, topSkill.blocks.joinToString(" · "))  // Phase 2: Assembling blocks

            val template = skillLibrary.loadTemplate(topSkill.id, topSkill.isVaultSkill)
            if (template != null) {
                onPhase(3, topSkill.name)  // Phase 3: Building your app

                val vault = vaultRegistry.enabledVaults.value.firstOrNull()
                    ?: return@withContext AssemblyResult(
                        GenerationResult.Failure(IllegalStateException("No vault configured")),
                        topSkill,
                        detection,
                    )
                val dir  = File(vault.localPath, ".vela/renderers/$contentType").also { it.mkdirs() }
                File(dir, "renderer.html").writeText(template)

                // Extract semantic data via LLM — this is the data.json that Lit components will use
                onPhase(3, "Extracting content…")
                try {
                    val json = contentExtractor.extract(itemContent, topSkill)
                    if (json != null) {
                        File(dir, "data.json").writeText(json)
                    }
                } catch (e: Exception) {
                    android.util.Log.w("RendererAssembler", "Extraction skipped: ${e.message}")
                }

                onPhase(4, "")  // Phase 4: Saving

                return@withContext AssemblyResult(
                    GenerationResult.Success(File(dir, "renderer.html").absolutePath),
                    topSkill,
                    detection,
                )
            }
        }

        // Fallback: open-ended LLM generation
        onPhase(2, "Generating…")
        onPhase(3, rendererType.label)

        val result = rendererGenerator.generateRenderer(
            itemPath     = itemPath,
            itemContent  = itemContent,
            contentType  = contentType,
            theme        = theme,
            layout       = layout,
            rendererType = rendererType,
            feedback     = feedback,
            existingHtml = existingHtml,
            onToken      = onToken,
            onActivity   = null,
        )

        onPhase(4, "")
        AssemblyResult(result, null, detection)
    }
}
