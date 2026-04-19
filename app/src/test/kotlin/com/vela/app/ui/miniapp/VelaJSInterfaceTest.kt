package com.vela.app.ui.miniapp

import com.google.common.truth.Truth.assertThat
import com.vela.app.ai.AmplifierSession
import com.vela.app.data.db.MiniAppDocumentDao
import com.vela.app.data.db.MiniAppDocumentEntity
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.events.VelaEvent
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito

class VelaJSInterfaceTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    // ── Fake DAO for scope-capture -------------------------------------------

    private var capturedScopePrefix: String? = null
    private var capturedCollection: String? = null
    private var capturedId: String? = null

    private val fakeDao = object : MiniAppDocumentDao {
        override suspend fun upsert(entity: MiniAppDocumentEntity) {}
        override suspend fun get(
            scopePrefix: String,
            collection: String,
            id: String,
        ): MiniAppDocumentEntity? {
            capturedScopePrefix = scopePrefix
            capturedCollection = collection
            capturedId = id
            return null
        }
        override suspend fun delete(scopePrefix: String, collection: String, id: String) {}
        override fun watch(
            scopePrefix: String,
            collection: String,
        ): Flow<List<MiniAppDocumentEntity>> = flowOf(emptyList())
        override suspend fun deleteCollection(scopePrefix: String, collection: String) {}
    }

    private val store = MiniAppDocumentStore(fakeDao)

    // Mockito 5 uses inline mock maker by default — final Kotlin classes can be mocked.
    private val mockSession: AmplifierSession = Mockito.mock(AmplifierSession::class.java)

    private fun buildInterface(
        itemScopePath: String = "recipes/carbonara.md",
        contentType: String = "recipe",
    ): VelaJSInterface {
        val vaultManager = VaultManager(
            root = tempDir.newFolder(),
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        return VelaJSInterface(
            documentStore = store,
            eventBus = EventBus(),
            amplifierSession = mockSession,
            vaultManager = vaultManager,
            itemScopePath = itemScopePath,
            contentType = contentType,
        )
    }

    // ── validateCollection via Db.get ----------------------------------------

    @Test
    fun `db get returns null silently for unknown collection prefix`() {
        // @JavascriptInterface methods must not propagate exceptions — invalid prefix returns null
        val result = buildInterface().db.get("unknown:something", "id")
        assertThat(result).isNull()
    }

    @Test
    fun `db get returns null silently for collection with no prefix separator`() {
        // @JavascriptInterface methods must not propagate exceptions — missing prefix returns null
        val result = buildInterface().db.get("shopping-list", "item-1")
        assertThat(result).isNull()
    }

    @Test
    fun `db get does not throw for valid local prefix`() {
        buildInterface().db.get("local:steps", "id") // must not throw
    }

    @Test
    fun `db get does not throw for valid global prefix`() {
        buildInterface().db.get("global:queue", "id")
    }

    @Test
    fun `db get does not throw for valid type prefix`() {
        buildInterface().db.get("type:recent", "id")
    }

    @Test
    fun `db put does not throw for unknown collection prefix`() {
        // fire-and-forget — invalid collection must be silently swallowed
        buildInterface().db.put("unknown:col", "id", "{}")
    }

    @Test
    fun `db delete does not throw for unknown collection prefix`() {
        buildInterface().db.delete("unknown:col", "id")
    }

    // ── scopeCollection via Db.get -------------------------------------------

    @Test
    fun `db get scopes local with item scope path prepended`() {
        buildInterface(itemScopePath = "recipes/carbonara.md").db.get("local:steps", "id")
        assertThat(capturedScopePrefix).isEqualTo("local")
        assertThat(capturedCollection).isEqualTo("recipes/carbonara.md::steps")
        assertThat(capturedId).isEqualTo("id")
    }

    @Test
    fun `db get scopes type with content type prepended`() {
        buildInterface(contentType = "recipe").db.get("type:recent-ingredients", "id-1")
        assertThat(capturedScopePrefix).isEqualTo("type")
        assertThat(capturedCollection).isEqualTo("recipe::recent-ingredients")
    }

    @Test
    fun `db get scopes global with collection name unchanged`() {
        buildInterface().db.get("global:shopping-list", "item-1")
        assertThat(capturedScopePrefix).isEqualTo("global")
        assertThat(capturedCollection).isEqualTo("shopping-list")
    }

    // ── Events.publish routes through EventBus ------------------------------

    @Test
    fun `events publish emits correct topic and payload on event bus`() = runBlocking {
        val eventBus = EventBus()
        val vaultManager = VaultManager(
            root = tempDir.newFolder(),
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        val iface = VelaJSInterface(
            documentStore = store,
            eventBus = eventBus,
            amplifierSession = mockSession,
            vaultManager = vaultManager,
            itemScopePath = "recipes/carbonara.md",
            contentType = "recipe",
        )
        val received = mutableListOf<VelaEvent>()
        val job = launch { eventBus.events.collect { received += it } }

        delay(10) // let collector attach to shared flow
        iface.events.publish("recipe:ingredients-ready", """{"count":5}""")
        delay(50)
        job.cancel()

        assertThat(received).hasSize(1)
        assertThat(received[0].topic).isEqualTo("recipe:ingredients-ready")
        assertThat(received[0].payload).isEqualTo("""{"count":5}""")
    }

    // ── escapeJs (via Events.subscribe callback) -----------------------------

    @Test
    fun `events subscribe escapes backslash in payload before evaluating JS`() = runBlocking {
        val eventBus = EventBus()
        val vaultManager = VaultManager(
            root = tempDir.newFolder(),
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        val iface = VelaJSInterface(
            documentStore = store,
            eventBus = eventBus,
            amplifierSession = mockSession,
            vaultManager = vaultManager,
            itemScopePath = "file.md",
            contentType = "note",
        )
        val evaluated = mutableListOf<String>()
        iface.onEvaluateJs = { evaluated += it }

        iface.events.subscribe("t", "cb")
        delay(10) // let subscription coroutine start collecting
        eventBus.tryPublish("t", """C:\path""")
        delay(50)
        iface.cancelAllSubscriptions()

        assertThat(evaluated).hasSize(1)
        assertThat(evaluated[0]).isEqualTo("""cb("C:\\path")""")
    }

    @Test
    fun `events subscribe escapes double quotes in payload before evaluating JS`() = runBlocking {
        val eventBus = EventBus()
        val vaultManager = VaultManager(
            root = tempDir.newFolder(),
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        val iface = VelaJSInterface(
            documentStore = store,
            eventBus = eventBus,
            amplifierSession = mockSession,
            vaultManager = vaultManager,
            itemScopePath = "file.md",
            contentType = "note",
        )
        val evaluated = mutableListOf<String>()
        iface.onEvaluateJs = { evaluated += it }

        iface.events.subscribe("t", "cb")
        delay(10)
        eventBus.tryPublish("t", """say "hi"""")
        delay(50)
        iface.cancelAllSubscriptions()

        assertThat(evaluated).hasSize(1)
        assertThat(evaluated[0]).isEqualTo("""cb("say \"hi\"")""")
    }

    // ── cancelAllSubscriptions -----------------------------------------------

    @Test
    fun `cancelAllSubscriptions stops delivery to event subscribers`() = runBlocking {
        val eventBus = EventBus()
        val vaultManager = VaultManager(
            root = tempDir.newFolder(),
            enabledVaultPaths = MutableStateFlow(emptySet()),
        )
        val iface = VelaJSInterface(
            documentStore = store,
            eventBus = eventBus,
            amplifierSession = mockSession,
            vaultManager = vaultManager,
            itemScopePath = "file.md",
            contentType = "note",
        )
        val evaluated = mutableListOf<String>()
        iface.onEvaluateJs = { evaluated += it }

        iface.events.subscribe("cancel-test", "onCancelTest")
        delay(10)  // let subscription start
        iface.cancelAllSubscriptions()
        delay(10)  // let cancellation propagate
        eventBus.tryPublish("cancel-test", "{}")
        delay(50)

        assertThat(evaluated).isEmpty()
    }
}
