package com.vela.app.ai

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.MiniAppDocumentDao
import com.vela.app.data.db.MiniAppDocumentEntity
import com.vela.app.data.db.MiniAppRegistryDao
import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.ui.miniapp.VelaTheme
import com.vela.app.vault.VaultManager
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
        return RendererGenerator(
            amplifierSession = mockSession,
            capabilitiesRepo = CapabilitiesGraphRepository(fakeRegistryDao),
            documentStore    = MiniAppDocumentStore(fakeDocumentDao),
            vaultManager     = vaultManager,
        )
    }

    private val defaultTheme = VelaTheme(isDark = false, primaryColor = "#7C4DFF")

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
        val vaultManager = VaultManager(
            root = tempDir.newFolder(),
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        val generator = RendererGenerator(
            amplifierSession = mockSession,
            capabilitiesRepo = CapabilitiesGraphRepository(throwingDao),
            documentStore    = MiniAppDocumentStore(fakeDocumentDao),
            vaultManager     = vaultManager,
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
