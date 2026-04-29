# Phase 2: Android Data Layer for Node Bootstrap — Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Add the Room/domain/registry layer that lets `NodeBootstrapper` (Phase 3) and the bootstrap UI (Phase 4) track node bootstrap state and promote SSH nodes to amplifierd nodes.

**Architecture:** A new `BootstrapStatus` enum + `bootstrapStatus` column on `ssh_nodes` carries lifecycle state (UNPROVISIONED → BOOTSTRAPPING → RUNNING → STALE). Schema is bumped with `MIGRATION_14_15`. `SshNodeRegistry` gains three coroutine writers (`promoteToAmplifierd`, `markStale`, `updateBootstrapStatus`) plus a new `BootstrapEvent` sealed class for streaming bootstrap progress to the UI. No existing rows change shape — the column has a safe default.

**Tech Stack:** Kotlin, Room, Hilt, kotlinx.coroutines, JUnit 4, Google Truth.

**Design Reference:** `docs/plans/2026-04-29-amplifierd-node-bootstrap-design.md` Sections 3 & 4.

**Commit convention:** `feat(bootstrap): <description>`

---

## Task 1: Create `BootstrapEvent` sealed class + `BootstrapStep` enum

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ssh/BootstrapEvent.kt`

**Step 1: Create the file**

```kotlin
package com.vela.app.ssh

/**
 * Streaming events emitted by the bootstrap pipeline. The UI subscribes to a
 * Flow<BootstrapEvent> to render progress, terminal output, and final outcome.
 */
sealed class BootstrapEvent {
    /** A line of stdout/stderr from a remote command. */
    data class Output(val line: String) : BootstrapEvent()

    /** Bootstrap pipeline has entered a new step. */
    data class StepStart(val step: BootstrapStep) : BootstrapEvent()

    /** A step finished successfully. */
    data class StepComplete(val step: BootstrapStep) : BootstrapEvent()

    /** A step failed. [logs] holds the most recent terminal output for diagnostics. */
    data class Failed(
        val step: BootstrapStep,
        val error: String,
        val logs: List<String> = emptyList(),
    ) : BootstrapEvent()

    /** Bootstrap completed; amplifierd is reachable at [url] with shared secret [token]. */
    data class Complete(val url: String, val token: String) : BootstrapEvent()
}

/** Ordered phases of the bootstrap pipeline (see design doc Section 1). */
enum class BootstrapStep {
    DETECT,
    INSTALL_UV,
    INSTALL_AMPLIFIERD,
    WRITE_CONFIG,
    INSTALL_SERVICE,
    VERIFY,
}
```

**Step 2: Compile to verify**
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**
`git add app/src/main/kotlin/com/vela/app/ssh/BootstrapEvent.kt && git commit -m "feat(bootstrap): add BootstrapEvent sealed class and BootstrapStep enum"`

---

## Task 2: Add `BootstrapStatus` enum + write the failing test

**Files:**
- Test: `app/src/test/kotlin/com/vela/app/ssh/BootstrapStatusTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies the BootstrapStatus enum exists with the four
 * lifecycle values defined by design doc Section 3.
 */
class BootstrapStatusTest {

    @Test
    fun `enum has exactly four values in lifecycle order`() {
        val values = BootstrapStatus.values().map { it.name }
        assertThat(values).containsExactly(
            "UNPROVISIONED",
            "BOOTSTRAPPING",
            "RUNNING",
            "STALE",
        ).inOrder()
    }

    @Test
    fun `valueOf round-trips each entry by name`() {
        BootstrapStatus.values().forEach { status ->
            assertThat(BootstrapStatus.valueOf(status.name)).isEqualTo(status)
        }
    }
}
```

**Step 2: Run the test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.BootstrapStatusTest"`
Expected: FAIL with "Unresolved reference: BootstrapStatus".

**Step 3: Commit the failing test**
`git add app/src/test/kotlin/com/vela/app/ssh/BootstrapStatusTest.kt && git commit -m "feat(bootstrap): add failing BootstrapStatus enum test"`

---

## Task 3: Implement `BootstrapStatus` enum + `bootstrapStatus` field on `SshNode`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/SshNode.kt`

**Step 1: Add the enum and the new field**

Replace the entire contents of `SshNode.kt` with:

```kotlin
package com.vela.app.ssh

import java.util.UUID

enum class NodeType { SSH, AMPLIFIERD }

/**
 * Lifecycle of an amplifierd-capable node.
 *
 * UNPROVISIONED → fresh SSH node, never bootstrapped.
 * BOOTSTRAPPING → bootstrap in progress (or failed mid-way).
 * RUNNING       → amplifierd is live and health-checked.
 * STALE         → running but a newer amplifierd version is available.
 */
enum class BootstrapStatus {
    UNPROVISIONED,
    BOOTSTRAPPING,
    RUNNING,
    STALE,
}

data class SshNode(
    val id:       String = UUID.randomUUID().toString(),
    val label:    String,
    /** Ordered list of IPs/hostnames for SSH nodes (primary + fallbacks). */
    val hosts:    List<String> = emptyList(),
    val port:     Int    = 22,
    val username: String = "",
    val addedAt:  Long   = System.currentTimeMillis(),
    /** Node type — SSH or Amplifierd daemon. */
    val type:     NodeType = NodeType.SSH,
    /** amplifierd base URL, e.g. http://10.0.0.106:8410 */
    val url:      String = "",
    /** amplifierd x-amplifier-token shared secret. */
    val token:    String = "",
    /** Bootstrap lifecycle state. New SSH nodes default to UNPROVISIONED. */
    val bootstrapStatus: BootstrapStatus = BootstrapStatus.UNPROVISIONED,
) {
    val primaryHost: String get() = hosts.firstOrNull() ?: ""
}
```

**Step 2: Run the test to verify it passes**
Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.BootstrapStatusTest"`
Expected: PASS (2 tests).

**Step 3: Commit**
`git add app/src/main/kotlin/com/vela/app/ssh/SshNode.kt && git commit -m "feat(bootstrap): add BootstrapStatus enum and bootstrapStatus field on SshNode"`

---

## Task 4: Write the failing `SshNodeEntity` bootstrap column test

**Files:**
- Test: `app/src/test/kotlin/com/vela/app/data/db/SshNodeEntityBootstrapTest.kt`

**Step 1: Write the failing test**

```kotlin
package com.vela.app.data.db

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * RED → GREEN: verifies that [SshNodeEntity] carries a [bootstrapStatus]
 * column that defaults to "UNPROVISIONED" for backward compatibility.
 */
class SshNodeEntityBootstrapTest {

    @Test
    fun `bootstrapStatus defaults to UNPROVISIONED when not set`() {
        val entity = SshNodeEntity(
            id       = "n1",
            label    = "pi-zero",
            hosts    = "10.0.0.10",
            port     = 22,
            username = "ken",
            addedAt  = 0L,
        )
        assertThat(entity.bootstrapStatus).isEqualTo("UNPROVISIONED")
    }

    @Test
    fun `bootstrapStatus can be set explicitly`() {
        val entity = SshNodeEntity(
            id              = "n2",
            label           = "amp-host",
            hosts           = "10.0.0.20",
            port            = 22,
            username        = "ken",
            addedAt         = 0L,
            nodeType        = "amplifierd",
            url             = "http://10.0.0.20:8410",
            token           = "secret",
            bootstrapStatus = "RUNNING",
        )
        assertThat(entity.bootstrapStatus).isEqualTo("RUNNING")
    }
}
```

**Step 2: Run the test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.data.db.SshNodeEntityBootstrapTest"`
Expected: FAIL with "No value passed for parameter 'bootstrapStatus'" or "Unresolved reference: bootstrapStatus".

**Step 3: Commit the failing test**
`git add app/src/test/kotlin/com/vela/app/data/db/SshNodeEntityBootstrapTest.kt && git commit -m "feat(bootstrap): add failing SshNodeEntity bootstrapStatus column test"`

---

## Task 5: Add `bootstrapStatus` column to `SshNodeEntity`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/data/db/SshNodeEntity.kt`

**Step 1: Add the column**

Replace the file contents with:

```kotlin
package com.vela.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ssh_nodes")
data class SshNodeEntity(
    @PrimaryKey val id: String,
    val label:    String,
    /** Comma-separated ordered list of IPs/hostnames (SSH nodes). */
    val hosts:    String,
    val port:     Int,
    val username: String,
    val addedAt:  Long,
    /** "ssh" or "amplifierd". Default "ssh" for backward compat. */
    val nodeType: String = "ssh",
    /** amplifierd base URL. Empty for SSH nodes. */
    val url:      String = "",
    /** amplifierd token. Empty for SSH nodes. */
    val token:    String = "",
    /** BootstrapStatus enum name; default "UNPROVISIONED" for existing rows. */
    val bootstrapStatus: String = "UNPROVISIONED",
)
```

**Step 2: Run the test to verify it passes**
Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.data.db.SshNodeEntityBootstrapTest"`
Expected: PASS (2 tests).

**Step 3: Commit**
`git add app/src/main/kotlin/com/vela/app/data/db/SshNodeEntity.kt && git commit -m "feat(bootstrap): add bootstrapStatus column to SshNodeEntity"`

---

## Task 6: Add `MIGRATION_14_15` and bump `@Database(version = 15)`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt`

**Step 1: Bump the version**

In `VelaDatabase.kt`, change the `@Database` annotation:

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
    version = 15,
    exportSchema = true,
)
```

**Step 2: Add the migration object**

Append to the bottom of `VelaDatabase.kt` (after `MIGRATION_8_9`):

```kotlin
/** v14→v15: add bootstrapStatus column to ssh_nodes for amplifierd bootstrap lifecycle. */
val MIGRATION_14_15 = object : Migration(14, 15) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE ssh_nodes ADD COLUMN bootstrapStatus TEXT NOT NULL DEFAULT 'UNPROVISIONED'")
    }
}
```

**Step 3: Compile to verify**
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (Room may warn about needing a schema export — that's expected; the schema JSON for v15 will be generated at build time.)

**Step 4: Commit**
`git add app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt && git commit -m "feat(bootstrap): bump VelaDatabase to v15 with MIGRATION_14_15"`

---

## Task 7: Register `MIGRATION_14_15` in `AppModule`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

**Step 1: Add the migration to the builder**

In `AppModule.kt`, find this line:

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
```

Replace with:

```kotlin
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15)
```

**Step 2: Compile to verify**
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**
`git add app/src/main/kotlin/com/vela/app/di/AppModule.kt && git commit -m "feat(bootstrap): register MIGRATION_14_15 in AppModule"`

---

## Task 8: Add new DAO queries to `SshNodeDao`

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/data/db/SshNodeDao.kt`

**Step 1: Add the two queries**

Replace the file contents with:

```kotlin
package com.vela.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SshNodeDao {
    @Query("SELECT * FROM ssh_nodes ORDER BY addedAt ASC")
    fun getAllNodes(): Flow<List<SshNodeEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(node: SshNodeEntity)

    @Query("DELETE FROM ssh_nodes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM ssh_nodes WHERE id = :id")
    suspend fun getById(id: String): SshNodeEntity?

    /** Update only the bootstrap lifecycle column. */
    @Query("UPDATE ssh_nodes SET bootstrapStatus = :status WHERE id = :id")
    suspend fun updateBootstrapStatus(id: String, status: String)

    /**
     * Promote an SSH node to an amplifierd node in a single statement: flips
     * nodeType, sets url + token, and marks bootstrapStatus.
     */
    @Query("UPDATE ssh_nodes SET nodeType = :type, url = :url, token = :token, bootstrapStatus = :status WHERE id = :id")
    suspend fun promoteToAmplifierd(id: String, type: String, url: String, token: String, status: String)
}
```

**Step 2: Compile to verify (Room generates DAO impl at compile time)**
Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

**Step 3: Commit**
`git add app/src/main/kotlin/com/vela/app/data/db/SshNodeDao.kt && git commit -m "feat(bootstrap): add updateBootstrapStatus and promoteToAmplifierd DAO queries"`

---

## Task 9: Write the failing `SshNodeRegistry` bootstrap test

**Files:**
- Test: `app/src/test/kotlin/com/vela/app/ssh/SshNodeRegistryBootstrapTest.kt`

**Step 1: Write the failing test (uses a hand-rolled fake DAO)**

```kotlin
package com.vela.app.ssh

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * RED → GREEN: verifies the registry's bootstrap-related writers and the
 * entity↔domain mapping for the new bootstrapStatus column.
 */
class SshNodeRegistryBootstrapTest {

    // ── Fake DAO ────────────────────────────────────────────────────────────

    private class FakeSshNodeDao : SshNodeDao {
        val statusUpdates = mutableListOf<Pair<String, String>>()           // id, status
        val promotions    = mutableListOf<PromoteCall>()
        val inserts       = mutableListOf<SshNodeEntity>()
        var nextGetById: SshNodeEntity? = null

        data class PromoteCall(
            val id: String,
            val type: String,
            val url: String,
            val token: String,
            val status: String,
        )

        override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(emptyList())
        override suspend fun insert(node: SshNodeEntity) { inserts += node }
        override suspend fun delete(id: String) { /* no-op */ }
        override suspend fun getById(id: String): SshNodeEntity? = nextGetById

        override suspend fun updateBootstrapStatus(id: String, status: String) {
            statusUpdates += id to status
        }

        override suspend fun promoteToAmplifierd(
            id: String,
            type: String,
            url: String,
            token: String,
            status: String,
        ) {
            promotions += PromoteCall(id, type, url, token, status)
        }
    }

    // ── Tests ───────────────────────────────────────────────────────────────

    @Test
    fun `promoteToAmplifierd writes type, url, token, RUNNING status`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.promoteToAmplifierd(
            nodeId = "node-1",
            url    = "http://10.0.0.5:8410",
            token  = "abc123",
        )

        assertThat(dao.promotions).hasSize(1)
        val call = dao.promotions[0]
        assertThat(call.id).isEqualTo("node-1")
        assertThat(call.type).isEqualTo("amplifierd")
        assertThat(call.url).isEqualTo("http://10.0.0.5:8410")
        assertThat(call.token).isEqualTo("abc123")
        assertThat(call.status).isEqualTo("RUNNING")
    }

    @Test
    fun `markStale writes STALE status for the node`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.markStale("node-2")

        assertThat(dao.statusUpdates).containsExactly("node-2" to "STALE")
    }

    @Test
    fun `updateBootstrapStatus writes the enum name`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.updateBootstrapStatus("node-3", BootstrapStatus.BOOTSTRAPPING)

        assertThat(dao.statusUpdates).containsExactly("node-3" to "BOOTSTRAPPING")
    }

    @Test
    fun `addNode round-trips bootstrapStatus through entity mapping`() = runTest {
        val dao = FakeSshNodeDao()
        val registry = SshNodeRegistry(dao)

        registry.addNode(
            SshNode(
                id              = "n4",
                label           = "amp",
                hosts           = listOf("10.0.0.6"),
                bootstrapStatus = BootstrapStatus.RUNNING,
            )
        )

        assertThat(dao.inserts).hasSize(1)
        assertThat(dao.inserts[0].bootstrapStatus).isEqualTo("RUNNING")
    }

    @Test
    fun `allFlow maps unknown bootstrapStatus strings to UNPROVISIONED`() = runTest {
        val dao = object : FakeSshNodeDao() {
            override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(
                listOf(
                    SshNodeEntity(
                        id              = "x",
                        label           = "x",
                        hosts           = "1.1.1.1",
                        port            = 22,
                        username        = "u",
                        addedAt         = 0L,
                        bootstrapStatus = "BOGUS_VALUE",
                    )
                )
            )
        }
        val registry = SshNodeRegistry(dao)

        val first = registry.allFlow().let { flow ->
            var captured: List<SshNode> = emptyList()
            flow.collect { captured = it }
            captured
        }

        assertThat(first).hasSize(1)
        assertThat(first[0].bootstrapStatus).isEqualTo(BootstrapStatus.UNPROVISIONED)
    }

    @Test
    fun `allFlow maps known bootstrapStatus string to enum`() = runTest {
        val dao = object : FakeSshNodeDao() {
            override fun getAllNodes(): Flow<List<SshNodeEntity>> = flowOf(
                listOf(
                    SshNodeEntity(
                        id              = "y",
                        label           = "y",
                        hosts           = "2.2.2.2",
                        port            = 22,
                        username        = "u",
                        addedAt         = 0L,
                        bootstrapStatus = "STALE",
                    )
                )
            )
        }
        val registry = SshNodeRegistry(dao)

        var captured: List<SshNode> = emptyList()
        registry.allFlow().collect { captured = it }

        assertThat(captured[0].bootstrapStatus).isEqualTo(BootstrapStatus.STALE)
    }
}
```

**Step 2: Run the test to verify it fails**
Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.SshNodeRegistryBootstrapTest"`
Expected: FAIL — `promoteToAmplifierd`, `markStale`, `updateBootstrapStatus` are unresolved on `SshNodeRegistry`, and `bootstrapStatus` is unresolved on `SshNode`'s mapping (this last one already exists from Task 3, but the registry mapping doesn't propagate it yet).

**Step 3: Commit the failing test**
`git add app/src/test/kotlin/com/vela/app/ssh/SshNodeRegistryBootstrapTest.kt && git commit -m "feat(bootstrap): add failing SshNodeRegistry bootstrap writer tests"`

---

## Task 10: Implement registry methods + entity/domain mapping

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ssh/SshNodeRegistry.kt`

**Step 1: Replace the file**

```kotlin
package com.vela.app.ssh

import com.vela.app.data.db.SshNodeDao
import com.vela.app.data.db.SshNodeEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class SshNodeRegistry @Inject constructor(private val dao: SshNodeDao) {

    fun allFlow(): Flow<List<SshNode>> =
        dao.getAllNodes().map { it.map { e -> e.toDomain() } }

    @Volatile var cache: List<SshNode> = emptyList()
    fun updateCache(nodes: List<SshNode>) { cache = nodes }

    fun findByLabel(label: String): SshNode? =
        cache.firstOrNull { it.label.equals(label, ignoreCase = true) }
            ?: cache.firstOrNull { it.primaryHost.equals(label, ignoreCase = true) }

    fun allSync(): List<SshNode> = cache

    suspend fun addNode(node: SshNode)    = dao.insert(node.toEntity())
    suspend fun updateNode(node: SshNode) = dao.insert(node.toEntity())
    suspend fun removeNode(id: String)    = dao.delete(id)

    // ── Bootstrap-lifecycle writers ─────────────────────────────────────────

    /** Promote an SSH node to an amplifierd node in a single transaction. */
    suspend fun promoteToAmplifierd(nodeId: String, url: String, token: String) {
        dao.promoteToAmplifierd(nodeId, "amplifierd", url, token, BootstrapStatus.RUNNING.name)
    }

    /** Flag a running amplifierd node as stale (newer version available). */
    suspend fun markStale(nodeId: String) {
        dao.updateBootstrapStatus(nodeId, BootstrapStatus.STALE.name)
    }

    /** Update only the bootstrap lifecycle column. */
    suspend fun updateBootstrapStatus(nodeId: String, status: BootstrapStatus) {
        dao.updateBootstrapStatus(nodeId, status.name)
    }

    // ── Entity ↔ Domain mapping ─────────────────────────────────────────────

    private fun SshNodeEntity.toDomain() = SshNode(
        id       = id,
        label    = label,
        hosts    = hosts.split(",").map { it.trim() }.filter { it.isNotBlank() },
        port     = port,
        username = username,
        addedAt  = addedAt,
        type     = if (nodeType == "amplifierd") NodeType.AMPLIFIERD else NodeType.SSH,
        url      = url,
        token    = token,
        bootstrapStatus = parseBootstrapStatus(bootstrapStatus),
    )

    private fun SshNode.toEntity() = SshNodeEntity(
        id       = id,
        label    = label,
        hosts    = hosts.joinToString(","),
        port     = port,
        username = username,
        addedAt  = addedAt,
        nodeType = if (type == NodeType.AMPLIFIERD) "amplifierd" else "ssh",
        url      = url,
        token    = token,
        bootstrapStatus = bootstrapStatus.name,
    )

    /** Tolerant parse — unknown / corrupt strings fall back to UNPROVISIONED. */
    private fun parseBootstrapStatus(raw: String): BootstrapStatus =
        runCatching { BootstrapStatus.valueOf(raw) }.getOrDefault(BootstrapStatus.UNPROVISIONED)
}
```

**Step 2: Run the test to verify it passes**
Run: `./gradlew :app:testDebugUnitTest --tests "com.vela.app.ssh.SshNodeRegistryBootstrapTest"`
Expected: PASS (6 tests).

**Step 3: Run all unit tests to confirm no regressions**
Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL with all tests passing.

**Step 4: Commit**
`git add app/src/main/kotlin/com/vela/app/ssh/SshNodeRegistry.kt && git commit -m "feat(bootstrap): add promoteToAmplifierd, markStale, updateBootstrapStatus to registry"`

---

## Task 11: Verify full build + Room schema export

**Files:** none.

**Step 1: Full debug build**
Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Room generates schema JSON at `app/schemas/com.vela.app.data.db.VelaDatabase/15.json`.

**Step 2: Confirm schema v15 was exported**
Run: `ls app/schemas/com.vela.app.data.db.VelaDatabase/ | grep 15.json`
Expected: `15.json` listed.

**Step 3: Commit the exported schema**
`git add app/schemas/com.vela.app.data.db.VelaDatabase/15.json && git commit -m "feat(bootstrap): export Room schema v15"`

---

## Done

The data layer is now ready for Phase 3 (`NodeBootstrapper` will call `registry.updateBootstrapStatus(...)` during the pipeline and `registry.promoteToAmplifierd(...)` on success) and Phase 4 (UI subscribes to `registry.allFlow()` and reads `bootstrapStatus` to drive the bootstrap dialog state).

**Coverage summary:**
- 2 new test files, 1 modified test pattern reference (`TurnEventEntityAgentNameTest`).
- 10 unit tests added (2 enum + 2 entity + 6 registry).
- 1 schema migration (v14 → v15), 1 column added.
- 0 changes to existing tests or call sites — purely additive.
