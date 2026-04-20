package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.MiniAppDocumentDao
import com.vela.app.data.db.MiniAppDocumentEntity
import com.vela.app.data.db.MiniAppRegistryDao
import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.db.VaultEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.ui.miniapp.VelaTheme
import com.vela.app.vault.VaultManager
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

/**
 * Unit tests for [RendererGenerator].
 * Run: ./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.RendererGeneratorTest"
 */
class RendererGeneratorTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    // ── Fakes ──────────────────────────────────────────────────────────────

    private val fakeRegistryDao = object : MiniAppRegistryDao {
        override fun getAll(): Flow<List<MiniAppRegistryEntity>> = flowOf(emptyList())
        override suspend fun getByContentType(contentType: String): MiniAppRegistryEntity? = null
        override suspend fun upsert(entity: MiniAppRegistryEntity) {}
        override suspend fun delete(contentType: String) {}
    }

    private val fakeDocumentDao = object : MiniAppDocumentDao {
        override suspend fun upsert(entity: MiniAppDocumentEntity) {}
        override suspend fun get(
            scopePrefix: String,
            collection: String,
            id: String,
        ): MiniAppDocumentEntity? = null
        override suspend fun delete(scopePrefix: String, collection: String, id: String) {}
        override fun watch(
            scopePrefix: String,
            collection: String,
        ): Flow<List<MiniAppDocumentEntity>> = flowOf(emptyList())
        override suspend fun deleteCollection(scopePrefix: String, collection: String) {}
    }

    private val mockSession: AmplifierSession = Mockito.mock(AmplifierSession::class.java)

    private fun buildGenerator(root: java.io.File): RendererGenerator {
        val vaultManager = VaultManager(
            root = root,
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        val mockVaultRegistry = Mockito.mock(VaultRegistry::class.java).also { mock ->
            Mockito.`when`(mock.enabledVaults).thenReturn(
                MutableStateFlow(
                    listOf(VaultEntity(id = "test", name = "Test", localPath = root.absolutePath))
                )
            )
        }
        return RendererGenerator(
            amplifierSession = mockSession,
            capabilitiesRepo = CapabilitiesGraphRepository(fakeRegistryDao),
            documentStore    = MiniAppDocumentStore(fakeDocumentDao),
            vaultManager     = vaultManager,
            vaultRegistry    = mockVaultRegistry,
        )
    }

    private val defaultTheme = VelaTheme(isDark = false, primaryColor = "#7C4DFF")

    // ── Prompt / system-prompt content (no legacy API refs) ───────────────────

    /**
     * RENDERER_SYSTEM_PROMPT must not reference the old @JavascriptInterface-era API.
     * Access via reflection since the field lives in a private companion object.
     */
    @Test
    fun `RENDERER_SYSTEM_PROMPT describes only fetch-based window-vela API`() {
        // In Kotlin, companion object vals compile to static fields on the outer class
        val promptField = RendererGenerator::class.java
            .getDeclaredField("RENDERER_SYSTEM_PROMPT")
            .also { it.isAccessible = true }
        val systemPrompt = promptField.get(null) as String

        // Old conceptual API descriptions must be gone
        assertThat(systemPrompt).doesNotContain("window.vela.db for persistence")
        assertThat(systemPrompt).doesNotContain("events to publish/subscribe")

        // New fetch-based API descriptions must be present
        assertThat(systemPrompt).contains("window.vela.db.query")
        assertThat(systemPrompt).contains("window.vela.events.emit")
    }

    /**
     * The dynamic portion of the user prompt built by buildRendererPrompt() must
     * also reference only the fetch-based window.vela API — never the old bridge.
     */
    @Test
    fun `buildRendererPrompt output contains no legacy API references`() {
        val generator = buildGenerator(tempDir.newFolder())

        val method = RendererGenerator::class.java.getDeclaredMethod(
            "buildRendererPrompt",
            String::class.java,
            String::class.java,
            String::class.java,
            List::class.java,
            VelaTheme::class.java,
            String::class.java,
            RendererType::class.java,
            String::class.java,
            String::class.java,
        ).also { it.isAccessible = true }

        val prompt = method.invoke(
            generator,
            "notes/test.md", "# Test\nContent", "note",
            emptyList<MiniAppRegistryEntity>(), defaultTheme, "phone",
            RendererType.READER, null, null,
        ) as String

        val legacyPatterns = listOf(
            "vela.db.put", "vela.db.get", "events.publish", "events.subscribe",
            "ai.ask", "ai.stream", "vault.write", "vault.sync", "window.__vela_",
        )
        for (pattern in legacyPatterns) {
            assertThat(prompt).doesNotContain(pattern)
        }
    }


    // ── GenerationResult data classes ─────────────────────────────────────

    @Test
    fun `GenerationResult Success stores rendererPath`() {
        val path   = "/vault/.vela/renderers/recipe/renderer.html"
        val result = GenerationResult.Success(path)
        assertThat((result as GenerationResult.Success).rendererPath).isEqualTo(path)
    }

    @Test
    fun `GenerationResult Failure stores cause`() {
        val ex     = IllegalStateException("no html block")
        val result = GenerationResult.Failure(ex)
        assertThat((result as GenerationResult.Failure).cause).isSameInstanceAs(ex)
    }

    // ── generateRenderer — failure paths ──────────────────────────────────

    /**
     * When the LLM session is a no-op mock (emits no tokens), the accumulated
     * response is empty → [extractHtml] returns null → [GenerationResult.Failure].
     */
    @Test
    fun `generateRenderer returns Failure when LLM emits no HTML block`() = runTest {
        val generator = buildGenerator(tempDir.newFolder())
        val result = generator.generateRenderer(
            itemPath    = "notes/note.md",
            itemContent = "# A note\nsome content",
            contentType = "note",
            theme       = defaultTheme,
            layout      = "phone",
        )
        assertThat(result).isInstanceOf(GenerationResult.Failure::class.java)
        assertThat((result as GenerationResult.Failure).cause.message)
            .contains("no <html>")
    }

    /**
     * Any unexpected exception thrown by a dependency (e.g. DB crash before LLM is called)
     * must be wrapped in [GenerationResult.Failure] — never rethrown.
     */
    @Test
    fun `generateRenderer wraps unexpected exceptions in Failure — never throws`() = runTest {
        val throwingDao = object : MiniAppRegistryDao {
            override fun getAll(): Flow<List<MiniAppRegistryEntity>> =
                throw RuntimeException("DB connection lost")
            override suspend fun getByContentType(contentType: String): MiniAppRegistryEntity? = null
            override suspend fun upsert(entity: MiniAppRegistryEntity) {}
            override suspend fun delete(contentType: String) {}
        }
        val throwingRoot = tempDir.newFolder()
        val vaultManager = VaultManager(
            root = throwingRoot,
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        val mockVaultRegistry = Mockito.mock(VaultRegistry::class.java).also { mock ->
            Mockito.`when`(mock.enabledVaults).thenReturn(
                MutableStateFlow(
                    listOf(VaultEntity(id = "test", name = "Test", localPath = throwingRoot.absolutePath))
                )
            )
        }
        val generator = RendererGenerator(
            amplifierSession = mockSession,
            capabilitiesRepo = CapabilitiesGraphRepository(throwingDao),
            documentStore    = MiniAppDocumentStore(fakeDocumentDao),
            vaultManager     = vaultManager,
            vaultRegistry    = mockVaultRegistry,
        )
        val result = generator.generateRenderer(
            itemPath    = "notes/note.md",
            itemContent = "content",
            contentType = "note",
            theme       = defaultTheme,
            layout      = "phone",
        )
        assertThat(result).isInstanceOf(GenerationResult.Failure::class.java)
        assertThat((result as GenerationResult.Failure).cause.message)
            .contains("DB connection lost")
    }
}
