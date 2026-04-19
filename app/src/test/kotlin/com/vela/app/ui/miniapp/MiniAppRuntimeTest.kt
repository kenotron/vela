package com.vela.app.ui.miniapp

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.AmplifierSession
import com.vela.app.ai.RendererGenerator
import com.vela.app.data.db.MiniAppDocumentDao
import com.vela.app.data.db.MiniAppDocumentEntity
import com.vela.app.data.db.MiniAppRegistryDao
import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
        return MiniAppViewModel(
            documentStore     = MiniAppDocumentStore(fakeDocumentDao),
            eventBus          = EventBus(),
            amplifierSession  = mockSession,
            vaultManager      = vaultManager,
            capabilitiesRepo  = CapabilitiesGraphRepository(fakeRegistryDao),
            rendererGenerator = mockRendererGenerator,
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

    // ── MiniAppViewModel.createJsInterface ────────────────────────────────────────────────────────

    @Test
    fun `createJsInterface returns non-null VelaJSInterface`() {
        val vm = buildViewModel(tempDir.newFolder())
        val jsInterface = vm.createJsInterface("recipes/carbonara.md", "recipe")
        assertThat(jsInterface).isNotNull()
    }

    @Test
    fun `createJsInterface for different paths returns independent instances`() {
        val vm = buildViewModel(tempDir.newFolder())
        val a = vm.createJsInterface("notes/todo.md", "note")
        val b = vm.createJsInterface("recipes/pasta.md", "recipe")
        assertThat(a).isNotSameInstanceAs(b)
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
}
