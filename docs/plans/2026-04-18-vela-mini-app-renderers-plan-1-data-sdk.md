# Plan 1 — Data Layer + SDK Bridge
## Vela Mini App Renderers

**Design:** `docs/plans/2026-04-18-vela-mini-app-renderers-design.md`
**Depends on:** Nothing (start here)
**Followed by:** Plan 2 — Navigation + Integration

---

## Overview

Six tasks, strict dependency order. Each task ends with a compilable build.

| Task | Deliverable | Depends on |
|------|-------------|------------|
| 1 | Room migration v12→v13 + entity stubs | — |
| 2 | `MiniAppRegistryDao` + `CapabilitiesGraphRepository` | 1 |
| 3 | `MiniAppDocumentDao` + `MiniAppDocumentStore` | 1 |
| 4 | `EventBus` + `VelaJSInterface` | 2, 3 |
| 5 | `MiniAppRuntime` (`MiniAppContainer` composable) | 4 |
| 6 | `RendererGenerator` | 2, 5 |

### New files produced

```
app/src/main/kotlin/com/vela/app/
  data/db/
    MiniAppRegistryEntity.kt        — Task 1
    MiniAppDocumentEntity.kt        — Task 1
    MiniAppRegistryDao.kt           — Task 2
    MiniAppDocumentDao.kt           — Task 3
  data/repository/
    CapabilitiesGraphRepository.kt  — Task 2
    MiniAppDocumentStore.kt         — Task 3
  events/
    EventBus.kt                     — Task 4
  ui/miniapp/
    VelaJSInterface.kt              — Task 4
    MiniAppRuntime.kt               — Task 5  (exports MiniAppContainer + VelaTheme)
  ai/
    RendererGenerator.kt            — Task 6
```

### Files modified across all tasks

| File | What changes |
|------|--------------|
| `data/db/VelaDatabase.kt` | v12→v13 bump, `MIGRATION_12_13`, two entities, two abstract DAOs |
| `di/AppModule.kt` | migration wiring (Task 1), two DAO providers (Tasks 2+3), `MiniAppViewModel` not a singleton so no entry |

---

## Task 1: Room DB Migration v12→v13

**Context:**
- `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt` — current version is `12`; study `MIGRATION_11_12` for exact formatting conventions
- `app/src/main/kotlin/com/vela/app/data/db/VaultEmbeddingEntity.kt` — entity column naming convention (camelCase field names = camelCase SQL column names; no `@ColumnInfo` needed)
- `app/src/main/kotlin/com/vela/app/di/AppModule.kt` — `addMigrations(...)` call location (line 57)

**What to build:**

**Step 1 — Write `MIGRATION_12_13` in `VelaDatabase.kt`.**

Add this immediately after the `MIGRATION_11_12` block (after line 50):

```kotlin
/** v12→v13: add mini_app_registry and mini_app_documents tables for the mini app renderer system. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE mini_app_registry (
                contentType TEXT NOT NULL PRIMARY KEY,
                rendererPath TEXT NOT NULL,
                provides TEXT NOT NULL,
                consumes TEXT NOT NULL,
                dbCollections TEXT NOT NULL,
                version INTEGER NOT NULL,
                lastUsed INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE mini_app_documents (
                scopePrefix TEXT NOT NULL,
                collection TEXT NOT NULL,
                id TEXT NOT NULL,
                data TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (scopePrefix, collection, id)
            )
        """.trimIndent())
    }
}
```

**Step 2 — Create `app/src/main/kotlin/com/vela/app/data/db/MiniAppRegistryEntity.kt`.**

Room needs the entity class to exist before `@Database` can reference it. Full definition, no stub:

```kotlin
package com.vela.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per vault content type in the capabilities graph.
 *
 * [provides] and [consumes] are JSON arrays of `{id, description}` objects.
 * [dbCollections] is a JSON array of `{scope, collection, description}` objects
 * documenting which `mini_app_documents` collections this renderer reads/writes.
 * All three are stored as opaque TEXT — parse with `org.json.JSONArray` at the
 * repository layer, never with a Room TypeConverter.
 */
@Entity(tableName = "mini_app_registry")
data class MiniAppRegistryEntity(
    @PrimaryKey val contentType: String,
    /** Absolute path to `.vela/renderers/{contentType}/renderer.html` on disk. */
    val rendererPath: String,
    /** JSON array: `[{"id":"ingredients_list","description":"..."}]` */
    val provides: String,
    /** JSON array: `[{"id":"shopping-list.add_items","description":"..."}]` */
    val consumes: String,
    /** JSON array: `[{"scope":"global","collection":"shopping-list-queue","description":"..."}]` */
    val dbCollections: String,
    /** Monotonically increasing; incremented by RendererGenerator on every regeneration. */
    val version: Int,
    /** `System.currentTimeMillis()` at last use — for LRU eviction in a future pass. */
    val lastUsed: Long,
)
```

**Step 3 — Create `app/src/main/kotlin/com/vela/app/data/db/MiniAppDocumentEntity.kt`.**

Composite primary key — do NOT put `@PrimaryKey` on individual fields:

```kotlin
package com.vela.app.data.db

import androidx.room.Entity

/**
 * Schemaless document store backing `vela.db` in the mini app SDK.
 *
 * Three scope tiers:
 *  - [scopePrefix] = "local"  → [collection] is "{itemPath}::{name}", one namespace per vault file
 *  - [scopePrefix] = "type"   → [collection] is "{contentType}::{name}", shared across same-type renderers
 *  - [scopePrefix] = "global" → [collection] is the bare collection name, shared across all mini apps
 *
 * [data] is a JSON text blob; no schema enforced at the DB layer.
 */
@Entity(
    tableName = "mini_app_documents",
    primaryKeys = ["scopePrefix", "collection", "id"],
)
data class MiniAppDocumentEntity(
    /** "local" | "global" | "type" — stored without the colon. */
    val scopePrefix: String,
    /**
     * For "global": bare collection name, e.g. "shopping-list-queue".
     * For "type":   "{contentType}::{name}", e.g. "recipe::recent-ingredients".
     * For "local":  "{itemPath}::{name}",    e.g. "recipes/carbonara.md::steps".
     */
    val collection: String,
    val id: String,
    /** JSON text — arbitrary shape chosen by the renderer. */
    val data: String,
    /** `System.currentTimeMillis()` at last write. */
    val updatedAt: Long,
)
```

**Step 4 — Update `@Database` in `VelaDatabase.kt`: bump version and add both entities.**

Change the `@Database` annotation from:
```kotlin
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SshNodeEntity::class,
        TurnEntity::class,
        TurnEventEntity::class,
        VaultEntity::class,
        VaultEmbeddingEntity::class,
        GitHubIdentityEntity::class,
    ],
    version = 12,
    exportSchema = true,
)
```
to:
```kotlin
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SshNodeEntity::class,
        TurnEntity::class,
        TurnEventEntity::class,
        VaultEntity::class,
        VaultEmbeddingEntity::class,
        GitHubIdentityEntity::class,
        MiniAppRegistryEntity::class,
        MiniAppDocumentEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
```

The abstract DAO methods for the two new entities are added in Tasks 2 and 3 (after their DAO interfaces exist). Room does not require a DAO for every entity; entities without DAOs compile fine — the tables exist but are unreachable from Kotlin until the DAOs are wired in later tasks.

**Step 5 — Register `MIGRATION_12_13` in `AppModule.kt`.**

Extend the existing `.addMigrations(...)` call (line 57) to include the new migration at the end:

```kotlin
Room.databaseBuilder(ctx, VelaDatabase::class.java, "vela_database")
    .addMigrations(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
        MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
        MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
        MIGRATION_12_13,                                      // ← add this
    )
    .build()
```

**Theory of Success:** `./gradlew :app:assembleDebug` succeeds. Room's annotation processor validates both new entity classes against the `@Database` declaration. The v13 schema JSON is generated at `app/schemas/com.vela.app.data.db.VelaDatabase/13.json` including `mini_app_registry` and `mini_app_documents`.

**Proof:**
```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "(BUILD|error|Error)"
# Expected: BUILD SUCCESSFUL — no Room schema errors

grep -c "CREATE TABLE" app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt
# Expected: at least 2 occurrences in MIGRATION_12_13 (more from earlier migrations)

cat app/schemas/com.vela.app.data.db.VelaDatabase/13.json | python3 -c \
  "import json,sys; s=json.load(sys.stdin); print([t['tableName'] for t in s['database']['entities']])"
# Expected: list includes 'mini_app_registry' and 'mini_app_documents'
```

**NFR scan:**
- **Schema correctness:** All columns are `NOT NULL`; SQLite `INTEGER` for Kotlin `Int`/`Long`; `TEXT` for `String`/JSON blobs. The composite PK on `mini_app_documents` uses SQLite `PRIMARY KEY (...)` syntax, which Room recognises correctly when paired with `@Entity(primaryKeys = [...])`.
- **Migration safety:** `MIGRATION_12_13` is purely additive (two `CREATE TABLE` statements, zero `DROP` or `ALTER`). Existing user data in v12 databases survives intact. Rolling back the code on a v13 database is not supported — document that the migration is one-way.
- **Column name alignment:** Kotlin field names are camelCase and are used verbatim as SQL column names (matching the existing codebase convention seen in `vault_embeddings` and `github_identities`). No `@ColumnInfo(name = ...)` annotations needed.

---

## Task 2: MiniAppRegistryDao + CapabilitiesGraphRepository

**Context:**
- `app/src/main/kotlin/com/vela/app/data/db/GitHubIdentityDao.kt` — complete DAO pattern: `@Dao interface`, `@Insert(onConflict = OnConflictStrategy.REPLACE)`, `suspend` for one-shot queries, `Flow<List<...>>` for reactive queries
- `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt` — add the abstract DAO method here (after Task 1)
- `app/src/main/kotlin/com/vela/app/di/AppModule.kt` — DAO providers follow `@Provides fun provideXxxDao(db: VelaDatabase): XxxDao = db.xxxDao()` — no `@Singleton` on DAOs

**What to build:**

**Step 1 — Create `app/src/main/kotlin/com/vela/app/data/db/MiniAppRegistryDao.kt`.**

```kotlin
package com.vela.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MiniAppRegistryDao {

    /** Full capabilities graph — ordered most-recently-used first. */
    @Query("SELECT * FROM mini_app_registry ORDER BY lastUsed DESC")
    fun getAll(): Flow<List<MiniAppRegistryEntity>>

    /** Point lookup for renderer existence check before generation. */
    @Query("SELECT * FROM mini_app_registry WHERE contentType = :contentType LIMIT 1")
    suspend fun getByContentType(contentType: String): MiniAppRegistryEntity?

    /**
     * Insert or replace. REPLACE strategy deletes the old row and inserts a new one,
     * which is correct here — the whole manifest is overwritten on each regeneration.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniAppRegistryEntity)

    @Query("DELETE FROM mini_app_registry WHERE contentType = :contentType")
    suspend fun delete(contentType: String)
}
```

**Step 2 — Add the abstract DAO method to `VelaDatabase.kt`.**

Inside `abstract class VelaDatabase : RoomDatabase()`, add after the existing `gitHubIdentityDao()` line:

```kotlin
abstract fun miniAppRegistryDao(): MiniAppRegistryDao
```

**Step 3 — Create `app/src/main/kotlin/com/vela/app/data/repository/CapabilitiesGraphRepository.kt`.**

```kotlin
package com.vela.app.data.repository

import com.vela.app.data.db.MiniAppRegistryDao
import com.vela.app.data.db.MiniAppRegistryEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the mini app capabilities graph.
 *
 * Every renderer that has been generated for a content type has one row here.
 * [getAll] is the reactive snapshot used by [MiniAppContainer] to keep
 * `__VELA_CONTEXT__.capabilities` current. [upsert] is called by [RendererGenerator]
 * after a successful generation.
 */
@Singleton
class CapabilitiesGraphRepository @Inject constructor(
    private val dao: MiniAppRegistryDao,
) {
    /** Live snapshot of all registered mini apps, ordered most-recently-used first. */
    fun getAll(): Flow<List<MiniAppRegistryEntity>> = dao.getAll()

    /**
     * Persist or update a renderer manifest.
     * Called by [RendererGenerator] after writing the HTML to disk.
     */
    suspend fun upsert(entity: MiniAppRegistryEntity) = dao.upsert(entity)

    /**
     * Returns the registered entity for [contentType], or `null` if no renderer
     * has been generated yet. Used by [RendererGenerator] to check existence and
     * to read the current [MiniAppRegistryEntity.version] before incrementing.
     */
    suspend fun getByContentType(contentType: String): MiniAppRegistryEntity? =
        dao.getByContentType(contentType)
}
```

**Step 4 — Add the DAO `@Provides` method in `AppModule.kt`.**

Add inside `object AppModule`, after the existing `provideGitHubIdentityDao` line:

```kotlin
@Provides
fun provideMiniAppRegistryDao(db: VelaDatabase): MiniAppRegistryDao =
    db.miniAppRegistryDao()
```

`CapabilitiesGraphRepository` has `@Inject constructor` + `@Singleton` — Hilt binds it automatically; no explicit `@Provides` entry needed.

**Theory of Success:** `./gradlew :app:compileDebugKotlin` passes. Room generates accessor code for `MiniAppRegistryDao`. Kotlin compilation resolves all three types (`MiniAppRegistryDao`, `MiniAppRegistryEntity`, `CapabilitiesGraphRepository`) without errors.

**Proof:**
```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "(BUILD|error|Error|unresolved)"
# Expected: BUILD SUCCESSFUL — no unresolved reference errors

# Verify Room generated the DAO implementation
find app/build -name "MiniAppRegistryDao_Impl.java" 2>/dev/null
# Expected: path to generated file printed
```

**NFR scan:**
- **Flow lifecycle:** `getAll()` returns a cold `Flow` — callers are responsible for collecting it in a `viewModelScope` or `lifecycleScope`. The repository does not hold or cancel any coroutine scope; it is a pure data-access facade.
- **JSON opacity:** `provides`, `consumes`, and `dbCollections` fields are stored as opaque `TEXT`. No `TypeConverter` is registered because the data is never queried by JSON field value — it is always returned whole and parsed by the caller (`RendererGenerator` uses `org.json.JSONArray`). Adding a TypeConverter would silently break the `@Insert(REPLACE)` round-trip for these fields if Room attempted to re-serialize them.

---

## Task 3: MiniAppDocumentDao + MiniAppDocumentStore

**Context:**
- `app/src/main/kotlin/com/vela/app/data/db/VaultEmbeddingDao.kt` — DAO with multi-column queries; note pattern of using parameter names that match column names exactly
- `app/src/main/kotlin/com/vela/app/data/db/MiniAppDocumentEntity.kt` — composite PK structure from Task 1
- `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt` — add abstract DAO method here
- `app/src/main/kotlin/com/vela/app/di/AppModule.kt` — add DAO provider

**What to build:**

**Step 1 — Create `app/src/main/kotlin/com/vela/app/data/db/MiniAppDocumentDao.kt`.**

```kotlin
package com.vela.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MiniAppDocumentDao {

    /**
     * Upsert a document. REPLACE deletes the existing row (matched by composite PK)
     * and inserts the new one — correct for schemaless document semantics.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: MiniAppDocumentEntity)

    @Query("""
        SELECT * FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection AND id = :id
        LIMIT 1
    """)
    suspend fun get(scopePrefix: String, collection: String, id: String): MiniAppDocumentEntity?

    @Query("""
        DELETE FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection AND id = :id
    """)
    suspend fun delete(scopePrefix: String, collection: String, id: String)

    /**
     * Reactive query for `vela.db.watch(collection, cb)`.
     * Room emits a new list every time any row in the result set changes.
     * Ordered by [MiniAppDocumentEntity.updatedAt] descending so new writes
     * surface at the top of the list delivered to the WebView.
     */
    @Query("""
        SELECT * FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection
        ORDER BY updatedAt DESC
    """)
    fun watch(scopePrefix: String, collection: String): Flow<List<MiniAppDocumentEntity>>

    /** Delete all documents in a scoped collection — used for cleanup. */
    @Query("""
        DELETE FROM mini_app_documents
        WHERE scopePrefix = :scopePrefix AND collection = :collection
    """)
    suspend fun deleteCollection(scopePrefix: String, collection: String)
}
```

**Step 2 — Add the abstract DAO method to `VelaDatabase.kt`.**

Inside `abstract class VelaDatabase : RoomDatabase()`, add after `miniAppRegistryDao()`:

```kotlin
abstract fun miniAppDocumentDao(): MiniAppDocumentDao
```

**Step 3 — Create `app/src/main/kotlin/com/vela/app/data/repository/MiniAppDocumentStore.kt`.**

```kotlin
package com.vela.app.data.repository

import com.vela.app.data.db.MiniAppDocumentDao
import com.vela.app.data.db.MiniAppDocumentEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schemaless document store backing `window.vela.db` in the mini app SDK.
 *
 * All methods accept pre-split scope arguments:
 *   - [scopePrefix]: "local" | "global" | "type"   (no colon)
 *   - [collection]:  the scoped collection name (see [MiniAppDocumentEntity] KDoc for format)
 *
 * Scope splitting and prefix validation happen in [VelaJSInterface] before calling into
 * this store; the store itself applies no validation so it can be called safely from
 * pure Kotlin code without the JS validation overhead.
 */
@Singleton
class MiniAppDocumentStore @Inject constructor(
    private val dao: MiniAppDocumentDao,
) {
    suspend fun put(scopePrefix: String, collection: String, id: String, data: String) {
        dao.upsert(
            MiniAppDocumentEntity(
                scopePrefix = scopePrefix,
                collection  = collection,
                id          = id,
                data        = data,
                updatedAt   = System.currentTimeMillis(),
            )
        )
    }

    /** Returns the raw JSON [data] string, or `null` if the document does not exist. */
    suspend fun get(scopePrefix: String, collection: String, id: String): String? =
        dao.get(scopePrefix, collection, id)?.data

    suspend fun delete(scopePrefix: String, collection: String, id: String) =
        dao.delete(scopePrefix, collection, id)

    /**
     * Returns a [Flow] that emits the full collection list every time any document
     * in that collection is written or deleted. Callers collect this in a coroutine
     * and push results to the WebView via `evaluateJavascript`.
     */
    fun watch(scopePrefix: String, collection: String): Flow<List<MiniAppDocumentEntity>> =
        dao.watch(scopePrefix, collection)
}
```

**Step 4 — Add the DAO `@Provides` method in `AppModule.kt`.**

Add after the `provideMiniAppRegistryDao` line (from Task 2):

```kotlin
@Provides
fun provideMiniAppDocumentDao(db: VelaDatabase): MiniAppDocumentDao =
    db.miniAppDocumentDao()
```

`MiniAppDocumentStore` has `@Inject constructor` + `@Singleton` — Hilt binds it automatically.

**Theory of Success:** `./gradlew :app:assembleDebug` succeeds. Room validates `MiniAppDocumentEntity`'s composite PK against `MIGRATION_12_13`'s `PRIMARY KEY (scopePrefix, collection, id)` declaration. The generated `MiniAppDocumentDao_Impl` includes the `watch()` `Flow` implementation.

**Proof:**
```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "(BUILD|error|Error)"
# Expected: BUILD SUCCESSFUL

find app/build -name "MiniAppDocumentDao_Impl.java" 2>/dev/null
# Expected: path to the generated DAO implementation printed

# Verify Room accepted the composite PK syntax
grep -A 5 "primaryKeys" app/schemas/com.vela.app.data.db.VelaDatabase/13.json
# Expected: JSON showing primaryKeys = ["scopePrefix","collection","id"]
```

**NFR scan:**
- **Composite PK semantics:** Room's `@Insert(REPLACE)` on a composite-PK entity first deletes any row matching the full composite key, then inserts the new row. This is correct — a document write is always a full replace. No partial-update `@Update` method is needed.
- **Watch Flow back-pressure:** `dao.watch()` returns a Room `Flow` backed by SQLite's `invalidation tracker`. It does not buffer intermediate states — if two writes arrive before a collector resumes, it sees only one emission with the final state. This is acceptable for UI use; document the behaviour in `MiniAppDocumentStore` KDoc so future callers are not surprised. Do NOT use `ConflatedBroadcastChannel` or `conflate()` — Room's tracker already provides this guarantee.

---

## Task 4: EventBus + VelaJSInterface

**Context:**
- `app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt` — study the `runTurn(historyJson, userInput, userContentJson, systemPrompt, onToolStart, onToolEnd, onToken, onProviderRequest, onServerTool)` signature; this is called by `VelaJSInterface.Ai`
- `app/src/main/kotlin/com/vela/app/vault/VaultManager.kt` — `resolve(path: String): File?` is the vault access gate; returns `null` for out-of-bounds paths
- `app/src/main/kotlin/com/vela/app/data/repository/CapabilitiesGraphRepository.kt` and `MiniAppDocumentStore.kt` — from Tasks 2+3
- `app/src/main/kotlin/com/vela/app/di/AppModule.kt` — no entry needed for `EventBus` (auto-bound by Hilt); review for context only

**What to build:**

**Step 1 — Create `app/src/main/kotlin/com/vela/app/events/EventBus.kt`.**

```kotlin
package com.vela.app.events

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/** A single event routed through the in-process pub/sub bus. */
data class VelaEvent(val topic: String, val payload: String)

/**
 * In-process pub/sub bus for cross-mini-app communication and Vela system events.
 *
 * Topics follow `{mini-app-type}:{event-name}` convention, e.g. `recipe:ingredients-ready`.
 * System events published by Vela itself: `vela:theme-changed`, `vela:vault-changed`,
 * `vela:vault-synced`, `vela:layout-changed`, `vela:ai-interrupted`, `vela:sync-failed`.
 *
 * [tryPublish] is safe to call from any thread including Binder threads (used by
 * `@JavascriptInterface` methods). [events] is collected by [VelaJSInterface.Events.subscribe]
 * coroutines and by future Kotlin subscribers.
 */
@Singleton
class EventBus @Inject constructor() {
    private val _events = MutableSharedFlow<VelaEvent>(extraBufferCapacity = 64)

    /** Subscribe to all events. Filter by [VelaEvent.topic] in the collector. */
    val events: SharedFlow<VelaEvent> = _events.asSharedFlow()

    /**
     * Non-suspending emit — safe on any thread. Returns `false` only if the
     * 64-event buffer is full (extremely unlikely in practice; log and drop).
     */
    fun tryPublish(topic: String, payload: String): Boolean =
        _events.tryEmit(VelaEvent(topic, payload))
}
```

`EventBus` is `@Singleton` + `@Inject constructor` — Hilt wires it automatically; no `@Provides` entry in `AppModule` is needed.

**Step 2 — Create `app/src/main/kotlin/com/vela/app/ui/miniapp/VelaJSInterface.kt`.**

This class is NOT a Hilt singleton. It is created per-WebView by `MiniAppViewModel.createJsInterface(...)` in Task 5.

```kotlin
package com.vela.app.ui.miniapp

import android.webkit.JavascriptInterface
import com.vela.app.ai.AmplifierSession
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch

/**
 * Exposes the Vela SDK to WebView JavaScript as `window.vela`.
 *
 * Android registers the four inner objects separately (not the outer class):
 * ```
 * webView.addJavascriptInterface(jsInterface.db,     "__vela_db")
 * webView.addJavascriptInterface(jsInterface.events, "__vela_events")
 * webView.addJavascriptInterface(jsInterface.ai,     "__vela_ai")
 * webView.addJavascriptInterface(jsInterface.vault,  "__vela_vault")
 * ```
 * A JS shim injected by [MiniAppContainer] after page load assembles them into
 * `window.vela = { db, events, ai, vault }`.
 *
 * [onEvaluateJs] is set by [MiniAppContainer] to route Kotlin→JS callbacks via
 * `webView.post { webView.evaluateJavascript(js, null) }`.  It is declared `var`
 * so the factory can create the interface before the WebView exists.
 *
 * [cancelAllSubscriptions] tears down all active Room Flow and event collectors —
 * call it in the `DisposableEffect.onDispose` block in [MiniAppContainer].
 */
class VelaJSInterface(
    private val documentStore: MiniAppDocumentStore,
    private val eventBus: EventBus,
    private val amplifierSession: AmplifierSession,
    private val vaultManager: VaultManager,
    /** Absolute path of the currently open vault item — used to scope `local:` collections. */
    private val itemScopePath: String,
    /** Content type of the current renderer — used to scope `type:` collections. */
    private val contentType: String,
) {
    /** Set by [MiniAppContainer] after WebView creation. All inner classes use this for callbacks. */
    var onEvaluateJs: (String) -> Unit = {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Active watch/subscribe jobs keyed by a stable string id. Thread-safe. */
    private val subscriptionJobs = ConcurrentHashMap<String, Job>()

    // ──────────────────────────────────────────────────────────────
    // Inner objects registered with addJavascriptInterface
    // ──────────────────────────────────────────────────────────────

    val db     = Db()
    val events = Events()
    val ai     = Ai()
    val vault  = Vault()

    inner class Db {
        /**
         * Upsert a document. `collection` must start with `local:`, `global:`, or `type:`.
         * Fire-and-forget from JS — no return value.
         */
        @JavascriptInterface
        fun put(collection: String, id: String, data: String) {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            scope.launch { documentStore.put(prefix, name, id, data) }
        }

        /**
         * Synchronous point read — blocks the Binder thread until the DB returns.
         * Acceptable for small document reads; do NOT call on the main thread.
         */
        @JavascriptInterface
        fun get(collection: String, id: String): String? {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            var result: String? = null
            val latch = CountDownLatch(1)
            scope.launch {
                result = documentStore.get(prefix, name, id)
                latch.countDown()
            }
            latch.await()
            return result
        }

        @JavascriptInterface
        fun delete(collection: String, id: String) {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            scope.launch { documentStore.delete(prefix, name, id) }
        }

        /**
         * Registers a live observer. [callbackName] is the JS function to invoke with
         * the updated array when the collection changes, e.g. `"onShoppingListChanged"`.
         *
         * The previous watch on the same collection (if any) is cancelled before
         * the new one starts — prevents duplicates on React-style re-renders.
         */
        @JavascriptInterface
        fun watch(collection: String, callbackName: String) {
            validateCollection(collection)
            val (prefix, name) = scopeCollection(collection)
            val key = "db:watch:$collection"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                documentStore.watch(prefix, name).collect { entities ->
                    val jsonArray = buildString {
                        append("[")
                        entities.forEachIndexed { i, e ->
                            if (i > 0) append(",")
                            append("""{"id":${escapeJs(e.id)},"data":${e.data},"updatedAt":${e.updatedAt}}""")
                        }
                        append("]")
                    }
                    onEvaluateJs("$callbackName($jsonArray)")
                }
            }
        }
    }

    inner class Events {
        /** Publishes to the in-process [EventBus]. Visible to all subscribed mini apps. */
        @JavascriptInterface
        fun publish(topic: String, payload: String) {
            eventBus.tryPublish(topic, payload)
        }

        /**
         * Registers a subscription. [callbackName] is the JS function invoked with
         * `payload` whenever an event matching [topic] arrives.
         * The subscription runs until [cancelAllSubscriptions] is called.
         */
        @JavascriptInterface
        fun subscribe(topic: String, callbackName: String) {
            val key = "event:$topic:$callbackName"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                eventBus.events.collect { event ->
                    if (event.topic == topic) {
                        onEvaluateJs("$callbackName(${escapeJs(event.payload)})")
                    }
                }
            }
        }
    }

    inner class Ai {
        /**
         * Single-shot LLM call. [callbackName] is invoked with the complete response
         * string when the model finishes: `callbackName(responseText)`.
         * On error: `callbackName(null, errorMessage)`.
         */
        @JavascriptInterface
        fun ask(prompt: String, callbackName: String) {
            val key = "ai:ask:$callbackName"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                val sb = StringBuilder()
                try {
                    amplifierSession.runTurn(
                        historyJson       = "[]",
                        userInput         = prompt,
                        userContentJson   = null,
                        systemPrompt      = "",
                        onToolStart       = { _, _ -> "" },
                        onToolEnd         = { _, _ -> },
                        onToken           = { token -> sb.append(token) },
                        onProviderRequest = { null },
                        onServerTool      = { _, _ -> },
                    )
                    onEvaluateJs("$callbackName(${escapeJs(sb.toString())})")
                } catch (e: Exception) {
                    onEvaluateJs("$callbackName(null,${escapeJs(e.message ?: "Unknown error")})")
                }
            }
        }

        /**
         * Streaming LLM call. [chunkCallbackName] is invoked for each token;
         * [doneCallbackName] is invoked with no arguments on completion or with
         * an error string on failure.
         */
        @JavascriptInterface
        fun stream(prompt: String, chunkCallbackName: String, doneCallbackName: String) {
            val key = "ai:stream:$chunkCallbackName"
            subscriptionJobs[key]?.cancel()
            subscriptionJobs[key] = scope.launch {
                try {
                    amplifierSession.runTurn(
                        historyJson       = "[]",
                        userInput         = prompt,
                        userContentJson   = null,
                        systemPrompt      = "",
                        onToolStart       = { _, _ -> "" },
                        onToolEnd         = { _, _ -> },
                        onToken           = { token ->
                            onEvaluateJs("$chunkCallbackName(${escapeJs(token)})")
                        },
                        onProviderRequest = { null },
                        onServerTool      = { _, _ -> },
                    )
                    eventBus.tryPublish("vela:ai-interrupted", "{}")   // signals completion too
                    onEvaluateJs("$doneCallbackName()")
                } catch (e: Exception) {
                    eventBus.tryPublish("vela:ai-interrupted", "{}")
                    onEvaluateJs("$doneCallbackName(${escapeJs(e.message ?: "stream error")})")
                }
            }
        }
    }

    inner class Vault {
        /**
         * Reads a vault file. [callbackName] is invoked with the file content string.
         * Returns an empty string (not an error) if the file doesn't exist or the path
         * is outside the vault root — matches the principle of least surprise for renderers.
         */
        @JavascriptInterface
        fun read(path: String, callbackName: String) {
            scope.launch {
                val content = runCatching {
                    vaultManager.resolve(path)?.readText() ?: ""
                }.getOrElse { "" }
                onEvaluateJs("$callbackName(${escapeJs(content)})")
            }
        }

        /**
         * Writes a vault file and publishes `vela:vault-changed`.
         * Silently no-ops if [path] resolves outside the vault root.
         */
        @JavascriptInterface
        fun write(path: String, content: String) {
            scope.launch {
                runCatching { vaultManager.resolve(path)?.writeText(content) }
                eventBus.tryPublish("vela:vault-changed", """{"path":${escapeJs(path)}}""")
            }
        }

        /** Lists vault directory entries. [callbackName] receives a JSON string array. */
        @JavascriptInterface
        fun list(path: String, callbackName: String) {
            scope.launch {
                val entries = runCatching {
                    vaultManager.resolve(path)?.listFiles()?.map { it.name } ?: emptyList()
                }.getOrElse { emptyList() }
                val json = entries.joinToString(",", "[", "]") { escapeJs(it) }
                onEvaluateJs("$callbackName($json)")
            }
        }

        /** Signals a vault sync. Publishes `vela:vault-synced` to the event bus. */
        @JavascriptInterface
        fun sync() {
            eventBus.tryPublish("vela:vault-synced", "{}")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────

    /**
     * Cancel all active Room Flow collectors and event bus subscriptions.
     * Call this from `DisposableEffect.onDispose` in [MiniAppContainer].
     */
    fun cancelAllSubscriptions() {
        subscriptionJobs.values.forEach { it.cancel() }
        subscriptionJobs.clear()
    }

    // ──────────────────────────────────────────────────────────────
    // Private helpers
    // ──────────────────────────────────────────────────────────────

    /**
     * Validates that [collection] starts with a recognised scope prefix.
     * Throws [IllegalArgumentException] — surfaces as a JS exception to the mini app.
     */
    private fun validateCollection(collection: String) {
        if (!collection.startsWith("local:") &&
            !collection.startsWith("global:") &&
            !collection.startsWith("type:")
        ) {
            throw IllegalArgumentException(
                "vela.db: collection must start with 'local:', 'global:', or 'type:' — got: $collection"
            )
        }
    }

    /**
     * Splits a prefixed collection string into (scopePrefix, scopedCollectionName).
     *
     * Scoping rules:
     *  - "global:shopping-list-queue" → ("global", "shopping-list-queue")
     *  - "type:recent-ingredients"    → ("type",   "recipe::recent-ingredients")   [uses contentType]
     *  - "local:steps"               → ("local",  "recipes/carbonara.md::steps")   [uses itemScopePath]
     *
     * The scoped collection name is what is stored in [MiniAppDocumentEntity.collection].
     */
    private fun scopeCollection(collection: String): Pair<String, String> {
        val colonIdx = collection.indexOf(':')
        val prefix = collection.substring(0, colonIdx)
        val name   = collection.substring(colonIdx + 1)
        return when (prefix) {
            "local"  -> "local"  to "$itemScopePath::$name"
            "type"   -> "type"   to "$contentType::$name"
            else     -> "global" to name
        }
    }

    /** Wraps a string in a JS double-quoted literal with escaping for safe `evaluateJavascript` use. */
    private fun escapeJs(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
```

**Theory of Success:** `./gradlew :app:compileDebugKotlin` passes. All four inner classes resolve `MiniAppDocumentStore`, `EventBus`, `AmplifierSession`, and `VaultManager` without type errors.

**Proof:**
```bash
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "(BUILD|error|unresolved)"
# Expected: BUILD SUCCESSFUL

# Confirm EventBus is in the Hilt component graph
./gradlew :app:hiltJavaCompileDebug 2>&1 | grep -E "(BUILD|error|EventBus)"
# Expected: BUILD SUCCESSFUL — no missing binding errors
```

**NFR scan:**
- **Thread safety — `@JavascriptInterface` thread:** Android calls `@JavascriptInterface` methods on a Binder thread (not the main thread). All blocking work is dispatched to `Dispatchers.IO` via `scope.launch`. The only synchronous call in `Db.get()` uses `CountDownLatch` with a bounded DB read — acceptable for document sizes mini apps will realistically read (< 1 MB). If the DB call takes > 5 s Android's ANR watchdog on the Binder thread may trigger; add a 4 s `latch.await(4, TimeUnit.SECONDS)` timeout guard if this becomes a concern.
- **Memory leaks — coroutine scope:** `VelaJSInterface` creates its own `CoroutineScope(SupervisorJob())`. `cancelAllSubscriptions()` cancels all child jobs AND clears the map. The `DisposableEffect` in `MiniAppContainer` (Task 5) calls this on composable disposal. No `Activity` or `Fragment` context is stored in any inner class, so the scope is the only live reference from the WebView.
- **Scope isolation — `local:` and `type:` prefixes:** `scopeCollection()` bakes `itemScopePath` and `contentType` into the collection name stored in the DB, making cross-item and cross-type data leaks structurally impossible without deliberately using `global:`. The LLM renderer is informed of this contract via `__VELA_CONTEXT__` comments in the generated JS.

---

## Task 5: MiniAppRuntime (`MiniAppContainer` composable)

**Context:**
- `app/src/main/kotlin/com/vela/app/ui/vault/VaultBrowserScreen.kt` — study `HtmlViewer` (line 440) for the `AndroidView { WebView(ctx).apply { ... } }` pattern
- `app/src/main/kotlin/com/vela/app/ui/miniapp/VelaJSInterface.kt` — from Task 4; study constructor parameters and `cancelAllSubscriptions()`
- `app/src/main/kotlin/com/vela/app/data/db/MiniAppRegistryEntity.kt` — fields used in `buildContextJson`

**What to build:**

**Create `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt`.**

This single file exports three things: `VelaTheme` (data class used by `RendererGenerator` in Task 6), `MiniAppViewModel` (the Hilt-injected dependency carrier), and `MiniAppContainer` (the exported composable).

```kotlin
package com.vela.app.ui.miniapp

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.ai.AmplifierSession
import com.vela.app.data.db.MiniAppRegistryEntity
import com.vela.app.data.repository.CapabilitiesGraphRepository
import com.vela.app.data.repository.MiniAppDocumentStore
import com.vela.app.events.EventBus
import com.vela.app.vault.VaultManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

// ─────────────────────────────────────────────────────────────────────────────
// Shared data class — also used by RendererGenerator (Task 6)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Represents the current app theme for injection into `__VELA_CONTEXT__` and for
 * LLM prompt assembly in [RendererGenerator]. Passed as a value — not a Compose state.
 */
data class VelaTheme(
    val isDark: Boolean,
    /** RGB hex colour, e.g. `"#7C4DFF"`. */
    val primaryColor: String,
)

// ─────────────────────────────────────────────────────────────────────────────
// ViewModel — dependency carrier, one instance per screen destination
// ─────────────────────────────────────────────────────────────────────────────

@HiltViewModel
class MiniAppViewModel @Inject constructor(
    internal val documentStore: MiniAppDocumentStore,
    internal val eventBus: EventBus,
    internal val amplifierSession: AmplifierSession,
    internal val vaultManager: VaultManager,
    private val capabilitiesRepo: CapabilitiesGraphRepository,
) : ViewModel() {

    /** Live capabilities graph — drives `__VELA_CONTEXT__.capabilities` updates. */
    val capabilities: StateFlow<List<MiniAppRegistryEntity>> =
        capabilitiesRepo.getAll()
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Factory — creates one [VelaJSInterface] per (itemPath, contentType) pair. */
    fun createJsInterface(itemPath: String, contentType: String): VelaJSInterface =
        VelaJSInterface(
            documentStore  = documentStore,
            eventBus       = eventBus,
            amplifierSession = amplifierSession,
            vaultManager   = vaultManager,
            itemScopePath  = itemPath,
            contentType    = contentType,
        )

    /**
     * Returns the cached renderer HTML [File], or `null` if generation has not
     * happened yet (triggers fallback placeholder UI in [MiniAppContainer]).
     */
    fun getRendererFile(contentType: String): File? =
        vaultManager.resolve(".vela/renderers/$contentType/renderer.html")
            ?.takeIf { it.exists() }
}

// ─────────────────────────────────────────────────────────────────────────────
// Composable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Full-screen mini app host. Owns the [WebView] lifecycle, injects the Vela SDK,
 * and keeps `window.__VELA_CONTEXT__` current as theme or capabilities change.
 *
 * The file is named `MiniAppRuntime.kt`; the exported composable is `MiniAppContainer`
 * (per the design doc §5 naming convention).
 *
 * @param itemPath      Vault-relative path to the item being rendered, e.g. `"recipes/carbonara.md"`.
 * @param itemContent   Raw text content of the vault item.
 * @param contentType   Renderer content type key, e.g. `"recipe"`.
 * @param layout        `"phone"` or `"tablet"` — injected into `__VELA_CONTEXT__`.
 */
@Composable
fun MiniAppContainer(
    itemPath: String,
    itemContent: String,
    contentType: String,
    layout: String,
    modifier: Modifier = Modifier,
    viewModel: MiniAppViewModel = hiltViewModel(),
) {
    // ── Reactive state ──────────────────────────────────────────────────────
    val capabilities by viewModel.capabilities.collectAsState()
    val isDark       = isSystemInDarkTheme()
    val primaryArgb  = MaterialTheme.colorScheme.primary.toArgb()
    val primaryHex   = "#%06X".format(primaryArgb and 0xFFFFFF)
    val theme        = VelaTheme(isDark = isDark, primaryColor = primaryHex)

    val contextJson = remember(itemPath, itemContent, contentType, capabilities, isDark, primaryHex, layout) {
        buildContextJson(itemPath, itemContent, contentType, capabilities, theme, layout)
    }

    // ── JS interface — stable for the composable's lifetime ─────────────────
    val jsInterface = remember(itemPath, contentType) {
        viewModel.createJsInterface(itemPath, contentType)
    }

    DisposableEffect(jsInterface) {
        onDispose { jsInterface.cancelAllSubscriptions() }
    }

    // ── WebView ─────────────────────────────────────────────────────────────
    AndroidView(
        factory = { ctx ->
            WebView(ctx).apply {
                // Wire evaluateJavascript callback back to this WebView
                jsInterface.onEvaluateJs = { js ->
                    post { evaluateJavascript(js, null) }
                }

                // Register the four SDK namespaces
                addJavascriptInterface(jsInterface.db,     "__vela_db")
                addJavascriptInterface(jsInterface.events, "__vela_events")
                addJavascriptInterface(jsInterface.ai,     "__vela_ai")
                addJavascriptInterface(jsInterface.vault,  "__vela_vault")

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        // Assemble window.vela namespace + inject __VELA_CONTEXT__
                        val script = buildString {
                            append("(function(){")
                            append("window.vela={")
                            append("db:window.__vela_db,")
                            append("events:window.__vela_events,")
                            append("ai:window.__vela_ai,")
                            append("vault:window.__vela_vault")
                            append("};")
                            append("window.__VELA_CONTEXT__=")
                            append(contextJson)
                            append(";")
                            append("if(typeof window.onVelaReady==='function')window.onVelaReady();")
                            append("})();")
                        }
                        view.evaluateJavascript(script, null)
                    }
                }

                // Load renderer if it exists, otherwise show the loading placeholder
                val rendererFile = viewModel.getRendererFile(contentType)
                if (rendererFile != null) {
                    loadUrl("file://${rendererFile.absolutePath}")
                } else {
                    loadData(LOADING_PLACEHOLDER_HTML, "text/html", "UTF-8")
                }
            }
        },
        update = { webView ->
            // Recomposition — push the latest __VELA_CONTEXT__ (e.g. after theme change
            // or after a new mini app joins the capabilities graph)
            webView.post {
                webView.evaluateJavascript("window.__VELA_CONTEXT__=$contextJson;", null)
            }
        },
        modifier = modifier.fillMaxSize(),
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Private helpers
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Serialises all inputs into the `__VELA_CONTEXT__` JSON object that is injected
 * into every renderer after page load (design doc §2).
 *
 * `globalCollections` is an empty object for now — RendererGenerator populates it
 * with actual global/type snapshots at generation time (Task 6). Keeping it as `{}`
 * here keeps the runtime injection fast and avoids an additional DB round-trip on
 * every page load.
 */
private fun buildContextJson(
    itemPath: String,
    itemContent: String,
    contentType: String,
    capabilities: List<MiniAppRegistryEntity>,
    theme: VelaTheme,
    layout: String,
): String {
    val capsArray = JSONArray()
    capabilities.forEach { entity ->
        capsArray.put(JSONObject().apply {
            put("type", entity.contentType)
            put("provides", runCatching { JSONArray(entity.provides) }.getOrElse { JSONArray() })
            put("consumes", runCatching { JSONArray(entity.consumes) }.getOrElse { JSONArray() })
        })
    }
    return JSONObject().apply {
        put("itemPath",         itemPath)
        put("itemContent",      itemContent)
        put("contentType",      contentType)
        put("capabilities",     capsArray)
        put("globalCollections", JSONObject())
        put("theme", JSONObject().apply {
            put("isDark",       theme.isDark)
            put("primaryColor", theme.primaryColor)
        })
        put("layout", layout)
    }.toString()
}

private const val LOADING_PLACEHOLDER_HTML = """<!DOCTYPE html>
<html lang="en">
<head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
  body{margin:0;display:flex;align-items:center;justify-content:center;
       min-height:100vh;font-family:sans-serif;background:#121212;color:#888;}
  p{font-size:14px;letter-spacing:.5px;}
</style></head>
<body><p>Generating renderer…</p></body>
</html>"""
```

`MiniAppViewModel` has `@Inject constructor` + `@HiltViewModel` — Hilt wires it automatically. No `AppModule` changes needed.

**Theory of Success:** `./gradlew :app:assembleDebug` succeeds. `MiniAppContainer` compiles and can be added to any Compose UI tree. The WebView loads the placeholder HTML when no renderer file exists.

**Proof:**
```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "(BUILD|error|unresolved)"
# Expected: BUILD SUCCESSFUL

# Verify Hilt generates MiniAppViewModel_HiltModules
find app/build -name "MiniAppViewModel_HiltModules*" 2>/dev/null | head -3
# Expected: at least one generated file path printed

# Manual smoke test: add MiniAppContainer(itemPath="test.md", itemContent="hello",
# contentType="note", layout="phone") to any existing screen, run on emulator,
# open chrome://inspect — expect the placeholder "Generating renderer…" HTML.
```

**NFR scan:**
- **Memory — WebView lifecycle:** `AndroidView` creates the `WebView` once in `factory`; the `update` block re-injects `__VELA_CONTEXT__` on recomposition without recreating the view. The `DisposableEffect` cancels all JS interface subscriptions when `MiniAppContainer` leaves the composition. `WebView` itself is not explicitly `destroy()`-ed here — the activity's `onDestroy` handles that via the standard Android lifecycle.
- **Security — JavaScript bridge exposure:** `javaScriptEnabled = true` is set only because the HTML is LLM-generated content served from within the app's own vault (`file://...`). `setAllowContentAccess(false)` (default) and the fact that `vaultManager.resolve()` gates all file writes prevent a compromised renderer from reading arbitrary device files. The four `addJavascriptInterface` names are prefixed `__vela_` to reduce collision risk with page-authored globals.
- **`update` block idempotency:** The `update` block fires on every recomposition. `evaluateJavascript("window.__VELA_CONTEXT__=...")` is idempotent and fast (JSON string assignment). It does not reload the page — the renderer's JavaScript state (loaded data, UI interactions) is preserved.

---

## Task 6: RendererGenerator

**Context:**
- `app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt` — full `runTurn` signature (lines 40–85); pay attention to `onToken` callback used for token accumulation
- `app/src/main/kotlin/com/vela/app/data/repository/CapabilitiesGraphRepository.kt` — `getAll(): Flow<List<MiniAppRegistryEntity>>` and `upsert(entity)`; call `.first()` to get a one-shot snapshot
- `app/src/main/kotlin/com/vela/app/data/repository/MiniAppDocumentStore.kt` — `watch()` not needed here; only `get()` for the scope snapshots
- `app/src/main/kotlin/com/vela/app/ui/miniapp/MiniAppRuntime.kt` — `VelaTheme` is defined here; import it from `com.vela.app.ui.miniapp`
- `app/src/main/kotlin/com/vela/app/vault/VaultManager.kt` — `resolve(path: String): File?` and `root: File`

**What to build:**

**Create `app/src/main/kotlin/com/vela/app/ai/RendererGenerator.kt`.**

```kotlin
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

// ─────────────────────────────────────────────────────────────────────────────
// Result type
// ─────────────────────────────────────────────────────────────────────────────

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

// ─────────────────────────────────────────────────────────────────────────────
// Generator
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Generates a new HTML/CSS/JS renderer for a vault content type.
 *
 * Generation happens exactly once per unknown content type (triggered by the user
 * opening a vault item whose content type has no entry in `mini_app_registry`).
 * The user can also explicitly request regeneration — that is trigger #2 from the
 * design doc §3. Additive trigger #1 (new mini app joins the graph) is deferred
 * to Plan 2.
 *
 * Workflow:
 * 1. Snapshot the capabilities graph and current global/type collection data.
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
    private val documentStore: MiniAppDocumentStore,
    private val vaultManager: VaultManager,
) {
    /**
     * @param itemPath    Vault-relative path used in the prompt and for local: scoping context.
     * @param itemContent Raw text content of the vault item.
     * @param contentType The content type key, e.g. `"recipe"`. Used as the renderer directory name.
     * @param theme       Current app theme — injected into the prompt and `__VELA_CONTEXT__`.
     * @param layout      `"phone"` or `"tablet"` — the form factor to optimise for.
     */
    suspend fun generateRenderer(
        itemPath: String,
        itemContent: String,
        contentType: String,
        theme: VelaTheme,
        layout: String,
    ): GenerationResult = withContext(Dispatchers.IO) {
        try {
            // 1. Snapshot dependencies
            val capabilities = capabilitiesRepo.getAll().first()

            // 2. Assemble prompt
            val prompt = buildRendererPrompt(
                itemPath, itemContent, contentType, capabilities, theme, layout
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
                onToken           = { token -> sb.append(token) },
                onProviderRequest = { null },
                onServerTool      = { _, _ -> },
            )
            val response = sb.toString()

            // 4. Parse response
            val html     = extractHtml(response)
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

    // ──────────────────────────────────────────────────────────────
    // Prompt assembly
    // ──────────────────────────────────────────────────────────────

    private fun buildRendererPrompt(
        itemPath: String,
        itemContent: String,
        contentType: String,
        capabilities: List<MiniAppRegistryEntity>,
        theme: VelaTheme,
        layout: String,
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
            appendLine("## Context")
            appendLine("- Theme: ${if (theme.isDark) "dark" else "light"}, primary colour: ${theme.primaryColor}")
            appendLine("- Layout: $layout")
            appendLine("- Vela SDK available in window.vela: db.put/get/delete/watch, events.publish/subscribe, ai.ask/stream, vault.read/write/list/sync")
            appendLine()
            appendLine(RESPONSE_FORMAT_INSTRUCTIONS)
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Response parsing
    // ──────────────────────────────────────────────────────────────

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
                provides      = obj.optJSONArray("provides")?.toString()  ?: "[]",
                consumes      = obj.optJSONArray("consumes")?.toString()  ?: "[]",
                dbCollections = obj.optJSONArray("dbCollections")?.toString() ?: "[]",
            )
        }.getOrNull()
    }

    // ──────────────────────────────────────────────────────────────
    // Constants
    // ──────────────────────────────────────────────────────────────

    private companion object {
        const val RENDERER_SYSTEM_PROMPT = """
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

The manifest block documents what this mini app provides and consumes.
""".trimIndent()

        const val RESPONSE_FORMAT_INSTRUCTIONS = """
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
```
""".trimIndent()
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Private data
// ─────────────────────────────────────────────────────────────────────────────

/** Parsed capability manifest extracted from the LLM response manifest block. */
private data class CapabilityManifest(
    val provides: String,       // serialised JSON array string
    val consumes: String,       // serialised JSON array string
    val dbCollections: String,  // serialised JSON array string
)
```

`RendererGenerator` has `@Inject constructor` + `@Singleton` — Hilt wires it automatically. No `AppModule` changes needed.

**Theory of Success:** `./gradlew :app:assembleDebug` succeeds. Given a `MiniAppRegistryEntity` and a vault directory, `RendererGenerator.generateRenderer(...)` writes `renderer.html` to `.vela/renderers/{contentType}/renderer.html` inside the vault root and returns `GenerationResult.Success`.

**Proof:**
```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "(BUILD|error|unresolved)"
# Expected: BUILD SUCCESSFUL

# Manual integration test — run in a connected emulator or device:
# 1. Open a vault item with an unknown content type.
# 2. In logcat: adb logcat | grep -i "RendererGenerator\|GenerationResult"
# 3. After LLM call completes, verify file on device:
#    adb shell ls /data/data/com.vela.app/files/vaults/<vault-id>/.vela/renderers/<contentType>/
# Expected: renderer.html present
```

**NFR scan:**
- **LLM failure handling:** Every failure path returns `GenerationResult.Failure(cause)` — never throws. Callers (Plan 2's `VaultBrowserScreen` integration) should fall back to the existing extension-based viewer (`MarkdownViewer`, `PlainTextViewer`, etc.) on `Failure`, with a snackbar "Could not generate renderer — showing plain view." This matches design §5 error handling.
- **Vault path safety:** `vaultManager.resolve(".vela/renderers/$contentType")` delegates all path traversal prevention to `VaultManager.resolve()`, which returns `null` if the path escapes the vault root. `contentType` originates from Room DB or the caller — a content type value containing `../` would be caught and return `Failure`. Do NOT call `File(vaultManager.root, ".vela/renderers/$contentType")` directly; always go through `resolve()`.
- **Prompt size:** `itemContent` is truncated at 128 KB before inclusion in the prompt to avoid hitting model context limits. The truncation message `[…content truncated…]` is appended so the LLM knows the input was cut and can note it in generated comments.
- **Version monotonicity:** The version field is `(existing?.version ?: 0) + 1`. If `existing` is non-null, the previous version is read from `capabilitiesRepo.getByContentType(contentType)` (a suspend call within the same `withContext(Dispatchers.IO)` block) before the upsert. Two concurrent calls with the same `contentType` would both increment from the same existing version; this is acceptable — only one will win the Room `REPLACE`, and the next generation call will read the correct post-write version.

---

## Cross-task type consistency checklist

| Symbol | Defined in | Used in |
|--------|-----------|---------|
| `MiniAppRegistryEntity` | Task 1 | Tasks 2, 5, 6 |
| `MiniAppDocumentEntity` | Task 1 | Tasks 3, 4 |
| `MiniAppRegistryDao` | Task 2 | Tasks 2 (VelaDatabase), AppModule |
| `MiniAppDocumentDao` | Task 3 | Tasks 3 (VelaDatabase), AppModule |
| `CapabilitiesGraphRepository.getAll(): Flow<List<MiniAppRegistryEntity>>` | Task 2 | Tasks 5, 6 |
| `CapabilitiesGraphRepository.upsert(entity: MiniAppRegistryEntity)` | Task 2 | Task 6 |
| `CapabilitiesGraphRepository.getByContentType(contentType: String): MiniAppRegistryEntity?` | Task 2 | Task 6 |
| `MiniAppDocumentStore.put(scopePrefix, collection, id, data)` | Task 3 | Task 4 |
| `MiniAppDocumentStore.get(scopePrefix, collection, id): String?` | Task 3 | Task 4 |
| `MiniAppDocumentStore.delete(scopePrefix, collection, id)` | Task 3 | Task 4 |
| `MiniAppDocumentStore.watch(scopePrefix, collection): Flow<List<MiniAppDocumentEntity>>` | Task 3 | Task 4 |
| `EventBus.tryPublish(topic, payload): Boolean` | Task 4 | Tasks 4, 5 |
| `EventBus.events: SharedFlow<VelaEvent>` | Task 4 | Task 4 |
| `VelaEvent(topic, payload)` | Task 4 | Task 4 |
| `VelaJSInterface(documentStore, eventBus, amplifierSession, vaultManager, itemScopePath, contentType)` | Task 4 | Task 5 |
| `VelaJSInterface.cancelAllSubscriptions()` | Task 4 | Task 5 |
| `VelaJSInterface.onEvaluateJs: (String) -> Unit` (var) | Task 4 | Task 5 |
| `VelaTheme(isDark: Boolean, primaryColor: String)` | Task 5 | Tasks 5, 6 |
| `MiniAppViewModel.createJsInterface(itemPath, contentType): VelaJSInterface` | Task 5 | Task 5 |
| `MiniAppViewModel.getRendererFile(contentType): File?` | Task 5 | Task 5 |
| `MiniAppContainer(itemPath, itemContent, contentType, layout, modifier, viewModel)` | Task 5 | Plan 2 |
| `GenerationResult.Success(rendererPath: String)` | Task 6 | Plan 2 |
| `GenerationResult.Failure(cause: Throwable)` | Task 6 | Plan 2 |
| `RendererGenerator.generateRenderer(itemPath, itemContent, contentType, theme, layout)` | Task 6 | Plan 2 |

---

## Amendment — Multi-Turn Generation Loop

**Added:** 2026-04-19

### Follow-on: Update RendererGenerator to use InferenceEngine loop

**Problem:** `RendererGenerator.generateRenderer()` calls `AmplifierSession.runTurn()` once with a large assembled prompt. Single-turn, no tool access, no self-correction.

**Required change:** Replace the single `runTurn()` call with the `InferenceEngine` multi-turn loop so RendererGenerator can:
- Use tools to read vault files and capabilities graph incrementally  
- Reason across multiple steps: analyse content → design structure → write HTML → verify
- Match the quality bar of the main chat sessions

**What to build:**
- Inject `InferenceEngine` into `RendererGenerator`
- Run an ephemeral session with the renderer system prompt
- Extract the HTML + manifest from the final session output
- Remove the manual `StringBuilder` accumulation pattern

**Deferred** — the renderer system is functional with single-turn generation; this is a quality improvement.
