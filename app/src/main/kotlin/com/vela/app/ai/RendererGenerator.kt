package com.vela.app.ai

import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.ui.miniapp.VelaTheme
import com.vela.app.vault.VaultManager
import com.vela.app.vault.VaultRegistry
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
    private val vaultRegistry: VaultRegistry,
) {
    /**
     * @param itemPath      Vault-relative path used in the prompt and for local: scoping context.
     * @param itemContent   Raw text content of the vault item.
     * @param contentType   The content type key, e.g. `"recipe"`. Used as the renderer directory name.
     * @param theme         Current app theme — injected into the prompt and `__VELA_CONTEXT__`.
     * @param layout        `"phone"` or `"tablet"` — the form factor to optimise for.
     * @param rendererType  Style/purpose of the renderer to generate.
     * @param onToken       Optional streaming callback invoked for each token as it arrives.
     * @param onActivity    Optional callback invoked when the AI starts a tool, with a human-readable label.
     * @param feedback      Optional user improvement feedback for regeneration.
     * @param existingHtml  Current renderer HTML, supplied when applying feedback updates.
     */
    suspend fun generateRenderer(
        itemPath: String,
        itemContent: String,
        contentType: String,
        theme: VelaTheme,
        layout: String,
        rendererType: RendererType = RendererType.READER,
        onToken: ((String) -> Unit)? = null,
        onActivity: ((String) -> Unit)? = null,
        feedback: String? = null,             // user's improvement feedback
        existingHtml: String? = null,         // current renderer HTML for feedback updates
    ): GenerationResult = withContext(Dispatchers.IO) {
        try {
            // 1. Snapshot dependencies
            val capabilities = capabilitiesRepo.getAll().first()

            // 2. Assemble prompt
            val prompt = buildRendererPrompt(
                itemPath, itemContent, contentType, capabilities, theme, layout, rendererType, feedback, existingHtml
            )

            // 3. Call LLM
            val sb = StringBuilder()
            amplifierSession.runTurn(
                historyJson       = "[]",
                userInput         = prompt,
                userContentJson   = null,
                systemPrompt      = RENDERER_SYSTEM_PROMPT,
                onToolStart       = { name, _ ->
                    onActivity?.invoke(toolActivityLabel(name))
                    ""  // stableId — unused
                },
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

            // 5. Persist HTML — resolve via VaultRegistry (not VaultManager.resolve which
            // requires InferenceEngine session paths to be active, which they are not here).
            val primaryVault = vaultRegistry.enabledVaults.value.firstOrNull()
                ?: return@withContext GenerationResult.Failure(
                    IllegalStateException("No vault configured — add a vault to generate mini apps")
                )
            val rendererDir = File(primaryVault.localPath, ".vela/renderers/$contentType")
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
        feedback: String? = null,
        existingHtml: String? = null,
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
            appendLine("## Step 1 — Read and understand the content")
            appendLine("Study the vault item below. Think about what it IS semantically, what the user")
            appendLine("wants to DO with it, and what UI experience would serve that intent best.")
            appendLine()
            appendLine("## Vault Item")
            appendLine("Path: $itemPath")
            appendLine("Base content type hint: $contentType (use this as a starting point only — ")
            appendLine("the actual semantic type may be much more specific)")
            appendLine()
            appendLine("```")
            appendLine(safeContent)
            appendLine("```")
            appendLine()
            appendLine("## Capabilities Graph (existing mini apps you can connect to)")
            appendLine(capabilitiesSection)
            appendLine()
            appendLine("## Step 2 — Choose the ideal experience")
            appendLine("Based on the content's semantic purpose, pick the right paradigm:")
            appendLine(rendererType.promptStyle)
            appendLine("Override this hint if you identify a better paradigm for this specific content.")
            appendLine()
            appendLine("## Context")
            appendLine("- Theme: ${if (theme.isDark) "dark" else "light"}, primary colour: ${theme.primaryColor}")
            appendLine("- Layout: $layout")
            appendLine("- Vela SDK: window.vela.db.put/get/delete/watch, events.publish/subscribe, ai.ask/stream, vault.read/write/list/sync")
            appendLine()
            if (!feedback.isNullOrBlank() && !existingHtml.isNullOrBlank()) {
                appendLine("## Current Renderer (update this based on the feedback below)")
                appendLine("```html")
                appendLine(existingHtml.take(32_768))
                appendLine("```")
                appendLine()
                appendLine("## User Feedback")
                appendLine(feedback)
                appendLine()
                appendLine("Update the renderer incorporating the user's feedback. Keep what works; improve what they asked for.")
                appendLine()
            }
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
            """```manifest\s*(\{[^`]*?\})\s*```""",
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
    // Activity label helper
    // ─────────────────────────────────────────────────────────────────────────

    private fun toolActivityLabel(toolName: String): String = when (toolName) {
        "read_file"  -> "Reading vault content…"
        "write_file" -> "Writing to vault…"
        "edit_file"  -> "Updating vault content…"
        "glob"       -> "Scanning vault files…"
        "grep"       -> "Searching content…"
        "fetch_url"  -> "Fetching web content…"
        "search_web" -> "Searching the web…"
        "todo"       -> "Tracking build steps…"
        else         -> "${toolName.replace('_', ' ').replaceFirstChar { it.uppercase() }}…"
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Constants
    // ─────────────────────────────────────────────────────────────────────────

    private companion object {
        val RENDERER_SYSTEM_PROMPT = """
You are an intelligent mini app renderer generator for Vela, a personal intelligence platform.

Your job is to study vault content deeply and generate a BESPOKE, beautiful, functional
HTML/CSS/JS experience that is the IDEAL way for a human to interact with that specific content.

## Step 1 — Understand what this content IS
Before writing a single line of HTML, reason about:
- What is the semantic purpose of this content? (recipe, habit tracker, meeting notes,
  journal, project plan, reference doc, dashboard, reading list, contact info, …)
- What does the user actually WANT to do with it? (cook from it, track progress,
  review decisions, look something up, check off tasks, visualise trends, …)
- What UI paradigm serves that intent best? (reading view, interactive checklist,
  card-based dashboard, calendar/timeline, data table, kanban, form, …)

## Step 2 — Design the right experience
DO NOT default to a plain markdown-to-HTML dump. Instead:
- For a recipe → show ingredients list with serving-size multiplier, step-by-step
  cooking cards with timers, and a "shopping list" export.
- For a habit/task list → show interactive checkboxes with streak counters, progress
  rings, and persistent state via window.vela.db.
- For meeting notes → extract agenda, decisions, and action items into distinct blocks;
  show attendees as avatars; highlight open actions.
- For a project status → build a mini dashboard: progress bars, status chips,
  key metrics at the top, detail sections below.
- For a journal/diary → beautiful prose reader with date header, mood indicator,
  linked tags.
- For data/CSV/JSON → pick the right visualisation: table, chart, summary cards.
- For a contacts/people list → card grid with quick-action buttons.
- For a reading list/bookmarks → card list with open-URL actions.
- For anything else → invent the right experience based on the content's intent.

## Rules
- Respond ONLY with the HTML page followed by a manifest block. No explanation.
- The HTML page must be a complete document starting with <!DOCTYPE html>.
- Include substantial CSS — make it look genuinely polished and native-feeling.
- Use window.vela.db for persistence (scope: local:, type:, or global: with JS comments).
- Use window.vela.events to publish/subscribe cross-app events.
- Call window.onVelaReady for post-SDK-init setup.
- Adapt for phone/tablet via CSS vars --vela-layout, --vela-is-dark, --vela-primary-color.
- Connect to other mini apps shown in the capabilities graph via shared db collections.
- Render content dynamically in JavaScript — don't just embed raw text as static HTML.
  Parse the content string, extract structure, and build UI components from it.

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
