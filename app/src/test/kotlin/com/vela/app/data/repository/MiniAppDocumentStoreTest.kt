package com.vela.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.MiniAppDocumentDao
import com.vela.app.data.db.MiniAppDocumentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class MiniAppDocumentStoreTest {

    // --- Stub DAO -------------------------------------------------------

    private val upserted  = mutableListOf<MiniAppDocumentEntity>()
    private val deleted   = mutableListOf<Triple<String, String, String>>()
    private val deletedCollections = mutableListOf<Pair<String, String>>()
    private var stubbedEntity: MiniAppDocumentEntity? = null
    private var stubbedFlow: Flow<List<MiniAppDocumentEntity>> = flowOf(emptyList())

    private val fakeDao = object : MiniAppDocumentDao {
        override suspend fun upsert(entity: MiniAppDocumentEntity) { upserted += entity }
        override suspend fun get(scopePrefix: String, collection: String, id: String) = stubbedEntity
        override suspend fun delete(scopePrefix: String, collection: String, id: String) {
            deleted += Triple(scopePrefix, collection, id)
        }
        override fun watch(scopePrefix: String, collection: String) = stubbedFlow
        override suspend fun deleteCollection(scopePrefix: String, collection: String) {
            deletedCollections += Pair(scopePrefix, collection)
        }
    }

    private lateinit var store: MiniAppDocumentStore

    @Before
    fun setUp() {
        store = MiniAppDocumentStore(fakeDao)
    }

    // --- Tests -----------------------------------------------------------

    @Test
    fun `put upserts entity with correct fields`() = runBlocking {
        store.put("local", "recipes/carbonara.md::steps", "step-1", """{"text":"boil water"}""")

        assertThat(upserted).hasSize(1)
        val entity = upserted[0]
        assertThat(entity.scopePrefix).isEqualTo("local")
        assertThat(entity.collection).isEqualTo("recipes/carbonara.md::steps")
        assertThat(entity.id).isEqualTo("step-1")
        assertThat(entity.data).isEqualTo("""{"text":"boil water"}""")
        assertThat(entity.updatedAt).isGreaterThan(0L)
    }

    @Test
    fun `get returns data string when document exists`() = runBlocking {
        stubbedEntity = MiniAppDocumentEntity(
            scopePrefix = "global",
            collection  = "shopping-list",
            id          = "item-1",
            data        = """{"name":"milk"}""",
            updatedAt   = 1_000L,
        )

        val result = store.get("global", "shopping-list", "item-1")

        assertThat(result).isEqualTo("""{"name":"milk"}""")
    }

    @Test
    fun `get returns null when document does not exist`() = runBlocking {
        stubbedEntity = null

        val result = store.get("global", "missing-collection", "nonexistent-id")

        assertThat(result).isNull()
    }

    @Test
    fun `delete delegates to dao with correct args`() = runBlocking {
        store.delete("type", "recipe::recent", "doc-42")

        assertThat(deleted).hasSize(1)
        assertThat(deleted[0]).isEqualTo(Triple("type", "recipe::recent", "doc-42"))
    }

    @Test
    fun `watch returns the dao flow for the given scope and collection`() {
        val entity = MiniAppDocumentEntity("local", "notes::todo", "n1", "{}", 1L)
        stubbedFlow = flowOf(listOf(entity))

        val result = store.watch("local", "notes::todo")

        assertThat(result).isSameInstanceAs(stubbedFlow)
    }
}
