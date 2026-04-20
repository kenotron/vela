package com.vela.app.ui.miniapp

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.AmplifierSession
import com.vela.app.ai.RendererGenerator
import com.vela.app.ai.RendererType
import com.vela.app.data.db.MiniAppDocumentDao
import com.vela.app.data.db.MiniAppDocumentEntity
import com.vela.app.data.db.MiniAppRegistryDao
import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.db.VaultEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.server.VelaMiniAppServer
import com.vela.app.vault.VaultManager
import com.vela.app.vault.VaultRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class MiniAppRuntimeTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ── Fake stubs ────────────────────────────────────────────────────────────────────────────────

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
    private val mockRendererGenerator: RendererGenerator = Mockito.mock(RendererGenerator::class.java)

    private fun buildViewModel(root: File): MiniAppViewModel {
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
        val mockServer = Mockito.mock(VelaMiniAppServer::class.java).also { mock ->
            Mockito.`when`(mock.port).thenReturn(MutableStateFlow(VelaMiniAppServer.DEFAULT_PORT))
            Mockito.`when`(mock.isReady).thenReturn(MutableStateFlow(false))
        }
        return MiniAppViewModel(
            documentStore     = MiniAppDocumentStore(fakeDocumentDao),
            eventBus          = EventBus(),
            amplifierSession  = mockSession,
            vaultManager      = vaultManager,
            vaultRegistry     = mockVaultRegistry,
            capabilitiesRepo  = CapabilitiesGraphRepository(fakeRegistryDao),
            rendererGenerator = mockRendererGenerator,
            server            = mockServer,
        )
    }

    // ── RendererState ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `RendererState Fallback holds contentType and content`() {
        val fallback = RendererState.Fallback("markdown", "# Hello")
        assertThat(fallback.contentType).isEqualTo("markdown")
        assertThat(fallback.content).isEqualTo("# Hello")
    }

    @Test
    fun `RendererState Ready holds renderer file reference`() {
        val file = File("/tmp/renderer.html")
        val ready = RendererState.Ready(file)
        assertThat(ready.rendererFile).isEqualTo(file)
    }

    @Test
    fun `RendererState Loading is a singleton object`() {
        assertThat(RendererState.Loading).isNotNull()
    }

    // ── VelaTheme ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `VelaTheme dark variant stores isDark true and primaryColor`() {
        val theme = VelaTheme(isDark = true, primaryColor = "#7C4DFF")
        assertThat(theme.isDark).isTrue()
        assertThat(theme.primaryColor).isEqualTo("#7C4DFF")
    }

    @Test
    fun `VelaTheme light variant stores isDark false`() {
        val theme = VelaTheme(isDark = false, primaryColor = "#6200EE")
        assertThat(theme.isDark).isFalse()
        assertThat(theme.primaryColor).isEqualTo("#6200EE")
    }

    // ── MiniAppViewModel.getRendererFile ──────────────────────────────────────────────────────────

    @Test
    fun `getRendererFile returns null when renderer does not exist`() {
        val vm = buildViewModel(tempDir.newFolder())
        assertThat(vm.getRendererFile("recipe")).isNull()
    }

    @Test
    fun `getRendererFile returns File when renderer html exists on disk`() {
        val root = tempDir.newFolder()
        val rendererDir = File(root, ".vela/renderers/recipe")
        rendererDir.mkdirs()
        File(rendererDir, "renderer.html").writeText("<!DOCTYPE html><html></html>")

        val vm = buildViewModel(root)
        val result = vm.getRendererFile("recipe")
        assertThat(result).isNotNull()
        assertThat(result!!.name).isEqualTo("renderer.html")
    }

    @Test
    fun `getRendererFile returns null when directory exists but html file is absent`() {
        val root = tempDir.newFolder()
        File(root, ".vela/renderers/recipe").mkdirs()  // dir exists, no file

        val vm = buildViewModel(root)
        assertThat(vm.getRendererFile("recipe")).isNull()
    }

    // ── RendererSuggestion ────────────────────────────────────────────────────────────────────────

    // ── MiniAppViewModel server state flows (Task 5) ─────────────────────────────────────────────

    @Test
    fun `MiniAppViewModel exposes serverPort defaulting to 7701`() {
        val vm = buildViewModel(tempDir.newFolder())
        assertThat(vm.serverPort.value).isEqualTo(VelaMiniAppServer.DEFAULT_PORT)
    }

    @Test
    fun `MiniAppViewModel exposes serverReady defaulting to false`() {
        val vm = buildViewModel(tempDir.newFolder())
        assertThat(vm.serverReady.value).isFalse()
    }

    @Test
    fun `RendererSuggestion holds type label and description`() {
        val suggestion = RendererSuggestion(
            type        = RendererType.READER,
            label       = "Step-by-step guide",
            description = "Check off ingredients as you cook.",
        )
        assertThat(suggestion.type).isEqualTo(RendererType.READER)
        assertThat(suggestion.label).isEqualTo("Step-by-step guide")
        assertThat(suggestion.description).isEqualTo("Check off ingredients as you cook.")
    }

    // ── MiniAppViewModel.suggestRendererTypes ─────────────────────────────────────────────────────

    @Test
    fun `suggestRendererTypes falls back to all RendererType entries when session yields no output`() =
        runTest {
            val vm = buildViewModel(tempDir.newFolder())
            // mockSession.runTurn does nothing by default (Unit return), so sb stays empty
            // => JSONArray("") throws => fallback path is taken
            val suggestions = vm.suggestRendererTypes("# Recipe: Carbonara\n...", "recipe")

            assertThat(suggestions).hasSize(RendererType.entries.size)
            assertThat(suggestions.map { it.type })
                .containsExactlyElementsIn(RendererType.entries)
                .inOrder()
        }

    @Test
    fun `suggestRendererTypes fallback entries use RendererType label`() = runTest {
        val vm = buildViewModel(tempDir.newFolder())
        val suggestions = vm.suggestRendererTypes("some content", "note")

        suggestions.zip(RendererType.entries).forEach { (suggestion, type) ->
            assertThat(suggestion.label).isEqualTo(type.label)
        }
    }
}
