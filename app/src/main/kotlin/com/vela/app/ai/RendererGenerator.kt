package com.vela.app.ai

import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.ui.miniapp.VelaTheme
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

// ────────────────────────────────────────────────────────────────────────────────
// Renderer type
// ────────────────────────────────────────────────────────────────────────────────

enum class RendererType(val label: String, val promptStyle: String) {
    READER(
        label = "Reader",
        promptStyle = "Create a beautiful, scroll-optimised reading experience. Focus on typography, spacing, and readability. Render text, images, and structured content elegantly.",
    ),
    INTERACTIVE(
        label = "Interactive",
        promptStyle = "Create an interactive mini app with buttons, checkboxes, and form inputs. Use window.vela.db to persist user actions (checked items, notes, progress). State must survive page reloads.",
    ),
    DASHBOARD(
        label = "Dashboard",
        promptStyle = "Create a data-focused dashboard. Visualise counts, lists, metrics, and key facts extracted from the content using cards, badges, and simple charts.",
    ),
}

// ────────────────────────────────────────────────────────────────────────────────
// Result type
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Discriminated union returned by [RendererGenerator.generateRenderer].
 *
 * [Success.rendererPath] is the absolute path to the written `.html` file.
 * [Failure.cause] carries the exception for logging/display; it is NOT rethrown.
 */
sealed class GenerationResult {
    data class Success(val rendererPath: String) : GenerationResult()
    data class Failure(val cause: Throwable)     : GenerationResult()
}

// ────────────────────────────────────────────────────────────────────────────────
// Generator
// ────────────────────────────────────────────────────────────────────────────────

/**
 * Generates a new HTML/CSS/JS renderer for a vault content type.
 *
 * Generation happens exactly once per unknown content type (triggered by the user
 * opening a vault item whose content type has no entry in `mini_app_registry`).
 * The user can also explicitly request regeneration — that is trigger #2 from the
 * design doc §3. Additive trigger #1 (new mini app joins the graph) is deferred.
 *
 * Workflow:
 * 1. Snapshot the capabilities graph.
 * 2. Assemble the LLM prompt (item content + graph + scope contract + theme/layout).
 * 3. Call [AmplifierSession.runTurn] with an empty history (single-shot generation).
 * 4. Parse HTML and capability manifest from the response.
 * 5. Persist HTML to `.vela/renderers/{contentType}/renderer.html` inside the vault.
 * 6. Upsert the manifest to `mini_app_registry` via [CapabilitiesGraphRepository].
 * 7. Return [GenerationResult.Success] with the renderer path.
 *
 * On any failure, returns [GenerationResult.Failure] — never throws.
 */
@Singleton
class RendererGenerator @Inject constructor(
    private val amplifierSession: AmplifierSession,
    private val capabilitiesRepo: CapabilitiesGraphRepository,
    @Suppress("unused") private val documentStore: MiniAppDocumentStore,
    private val vaultManager: VaultManager,
) {
    /**
     * @param itemPath      Vault-relative path used in the prompt and for local: scoping context.
     * @param itemContent   Raw text content of the vault item.
     * @param contentType   The content type key, e.g. `"recipe"`. Used as the renderer directory name.
     * @param theme         Current app theme — injected into the prompt and `__VELA_CONTEXT__`.
     * @param layout        `"phone"` or `"tablet"` — the form factor to optimise for.
     * @param rendererType  Style/purpose of the renderer to generate.
     * @param onToken       Optional streaming callback invoked for each token as it arrives.
     */
    suspend fun generateRenderer(
        itemPath: String,
        itemContent: String,
        contentType: String,
        theme: VelaTheme,
        layout: String,
        rendererType: RendererType = RendererType.READER,
        onToken: ((String) -> Unit)? = null,
    ): GenerationResult = withContext(Dispatchers.IO) {
        try {
            // 1. Snapshot dependencies
            val capabilities = capabilitiesRepo.getAll().first()

            // 2. Assemble prompt
            val prompt = buildRendererPrompt(
                itemPath, itemContent, contentType, capabilities, theme, layout, rendererType
            )

            // 3. Call LLM
            val sb = StringBuilder()
            amplifierSession.runTurn(
                historyJson       = "[]",
                userInput         = prompt,
                userContentJson   = null,
                systemPrompt      = RENDERER_SYSTEM_PROMPT,
                onToolStart       = { _, _ -> "" },
                onToolEnd         = { _, _ -> },
                onToken           = { token ->
                    sb.append(token)
                    onToken?.invoke(token)
                },
                onProviderRequest = { null },
                onServerTool      = { _, _ -> },
            )
            val response = sb.toString()

            // 4. Parse response
            val html = extractHtml(response)
                ?: return@withContext GenerationResult.Failure(
                    IllegalStateException("LLM response contained no <html>…</html> block")
                )
            val manifest = extractManifest(response)
                ?: return@withContext GenerationResult.Failure(
                    IllegalStateException("LLM response contained no ```manifest JSON block")
                )

            // 5. Persist HTML
            val rendererDir = vaultManager.resolve(".vela/renderers/$contentType")
                ?: return@withContext GenerationResult.Failure(
                    IllegalStateException("Vault not accessible — cannot write renderer to .vela/renderers/$contentType")
                )
            rendererDir.mkdirs()
            val rendererFile = File(rendererDir, "renderer.html")
            rendererFile.writeText(html)

            // 6. Upsert manifest to Room
            val existing = capabilitiesRepo.getByContentType(contentType)
            val entity = MiniAppRegistryEntity(
                contentType   = contentType,
                rendererPath  = rendererFile.absolutePath,
                provides      = manifest.provides,
                consumes      = manifest.consumes,
                dbCollections = manifest.dbCollections,
                version       = (existing?.version ?: 0) + 1,
                lastUsed      = System.currentTimeMillis(),
            )
            capabilitiesRepo.upsert(entity)

            // 7. Return success
            GenerationResult.Success(rendererFile.absolutePath)

        } catch (e: Exception) {
            GenerationResult.Failure(e)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Prompt assembly
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildRendererPrompt(
        itemPath: String,
        itemContent: String,
        contentType: String,
        capabilities: List<MiniAppRegistryEntity>,
        theme: VelaTheme,
        layout: String,
        rendererType: RendererType,
    ): String {
        val capabilitiesSection = if (capabilities.isEmpty()) {
            "(No mini apps registered yet — this will be the first.)"
        } else {
            capabilities.joinToString("\n\n") { entity ->
                buildString {
                    appendLine("### ${entity.contentType}")
                    appendLine("Provides: ${entity.provides}")
                    appendLine("Consumes: ${entity.consumes}")
                    appendLine("DB collections: ${entity.dbCollections}")
                }
            }
        }

        // Truncate itemContent to avoid exceeding context limits (128 KB threshold)
        val safeContent = if (itemContent.length > 131_072) {
            itemContent.take(131_072) + "\n\n[…content truncated at 128 KB…]"
        } else {
            itemContent
        }

        return buildString {
            appendLine("## Vault Item")
            appendLine("Path: $itemPath")
            appendLine("Content type: $contentType")
            appendLine()
            appendLine("```")
            appendLine(safeContent)
            appendLine("```")
            appendLine()
            appendLine("## Capabilities Graph (existing mini apps)")
            appendLine(capabilitiesSection)
            appendLine()
            appendLine("## Renderer Style")
            appendLine(rendererType.promptStyle)
            appendLine()
            appendLine("## Context")
            appendLine("- Theme: ${if (theme.isDark) "dark" else "light"}, primary colour: ${theme.primaryColor}")
            appendLine("- Layout: $layout")
            appendLine("- Vela SDK available in window.vela: db.put/get/delete/watch, events.publish/subscribe, ai.ask/stream, vault.read/write/list/sync")
            appendLine()
            appendLine(RESPONSE_FORMAT_INSTRUCTIONS)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Response parsing
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Extracts the first complete `<html>…</html>` block from the LLM response.
     * Returns `null` if no such block is found.
     */
    private fun extractHtml(response: String): String? {
        val regex = Regex(
            """(?s)<!DOCTYPE\s+html>.*?</html>""",
            setOf(RegexOption.IGNORE_CASE),
        )
        return regex.find(response)?.value
            ?: Regex("""(?s)<html[^>]*>.*?</html>""", setOf(RegexOption.IGNORE_CASE))
                .find(response)?.value
    }

    /**
     * Extracts the JSON object from a ` ```manifest … ``` ` fenced code block.
     * Returns a [CapabilityManifest] with the raw JSON strings for `provides`,
     * `consumes`, and `dbCollections`, or `null` if the block is absent or malformed.
     */
    private fun extractManifest(response: String): CapabilityManifest? {
        val regex = Regex(
            """```manifest\s*(\{.*?})\s*```""",
            setOf(RegexOption.DOT_MATCHES_ALL),
        )
        val jsonText = regex.find(response)?.groupValues?.getOrNull(1) ?: return null
        return runCatching {
            val obj = JSONObject(jsonText)
            CapabilityManifest(
                provides      = obj.optJSONArray("provides")?.toString()      ?: "[]",
                consumes      = obj.optJSONArray("consumes")?.toString()      ?: "[]",
                dbCollections = obj.optJSONArray("dbCollections")?.toString() ?: "[]",
            )
        }.getOrNull()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    private companion object {
        val RENDERER_SYSTEM_PROMPT = """
You are a mini app renderer generator for Vela, a personal intelligence platform.

Your job is to generate a beautiful, functional, self-contained HTML/CSS/JS page
that renders the provided vault item in a rich, interactive way.

Rules:
- Respond ONLY with the HTML page followed by a manifest block. No explanation.
- The HTML page must be a complete document starting with <!DOCTYPE html>.
- Use window.vela.db for persistence. Use the scope prefix that best matches the
  data's intended visibility: local: (this item only), global: (shared across all
  mini apps), or type: (shared across all renderers of the same content type).
- Document your scope reasoning in JS comments.
- Use window.vela.events to publish/subscribe to cross-app events.
- Call window.onVelaReady if you need to run setup after the SDK is ready.
- Adapt layout for the given form factor (phone/tablet) using CSS variables
  --vela-layout, --vela-is-dark, --vela-primary-color.
- Where the capabilities graph shows other mini apps that your renderer could
  connect to, wire those connections via vela.db shared collections or vela.events.

The manifest block documents what this mini app provides and consumes.""".trimIndent()

        val RESPONSE_FORMAT_INSTRUCTIONS = """
## Required Response Format

Provide your complete response in EXACTLY this structure (nothing before the DOCTYPE):

<!DOCTYPE html>
<html lang="en">
[complete HTML/CSS/JS page]
</html>

```manifest
{
  "provides": [
    {"id": "capability_id", "description": "English description of what this provides"}
  ],
  "consumes": [
    {"id": "other-type.capability_id", "description": "English description of what this uses from another mini app"}
  ],
  "dbCollections": [
    {"scope": "global|type|local", "collection": "collection-name", "description": "what is stored here"}
  ]
}
```""".trimIndent()
    }
}

// ────────────────────────────────────────────────────────────────────────────────
// Private data
// ────────────────────────────────────────────────────────────────────────────────

/** Parsed capability manifest extracted from the LLM response manifest block. */
private data class CapabilityManifest(
    val provides: String,       // serialised JSON array string
    val consumes: String,       // serialised JSON array string
    val dbCollections: String,  // serialised JSON array string
)
