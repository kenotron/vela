# Phase 1: Tool & Vault Layer — Implementation Plan

> **For execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Add the complete tool surface and vault infrastructure that lets the AI read/write/search files in a local vault and perform git operations — no UI, no Rust changes, no hooks or skills yet.

**Architecture:** Kotlin `Tool` implementations wired through the existing `ToolRegistry` + Hilt DI. `VaultManager` owns path safety (traversal protection). `VaultGitSync` wraps JGit. `BashTool` routes git command strings to JGit. All vault tools are added to the existing `provideTools` list in `AppModule`.

**Tech Stack:** Kotlin, JGit 6.8.0, Android Room, Hilt, `java.nio.file` (glob), `java.util.regex` (grep), Google Truth + JUnit4 (tests)

---

## Codebase Orientation

- **Tool interface:** `app/src/main/kotlin/com/vela/app/ai/tools/Tool.kt`
- **Existing tool examples:** `WebTools.kt`, `BuiltInTools.kt` in same package
- **DI wiring:** `app/src/main/kotlin/com/vela/app/di/AppModule.kt`
- **Database:** `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt` — currently at version 6
- **Session layer:** `app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt`
- **Test location:** `app/src/test/kotlin/com/vela/app/` — JVM tests, JUnit4 + Google Truth
- **Build commands:** `./gradlew :app:testDebugUnitTest` (tests), `./gradlew :app:assembleDebug` (build)

---

## Task 1: Add JGit dependency

**WHAT:** `org.eclipse.jgit.*` imports resolve at compile time.

**HOW:** Add JGit to `app/build.gradle.kts` dependencies block. JGit ships OSGI manifests that conflict with the existing packaging exclusions — add the relevant exclude.

**PROOF:** `./gradlew :app:assembleDebug` succeeds with no unresolved reference errors.

**File to modify:** `app/build.gradle.kts`

In the `dependencies { }` block, add after the existing SSH dependency:
```kotlin
// JGit — vault git sync
implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")
```

In the `packaging { }` block inside `android { }`, add to the existing excludes:
```kotlin
resources.excludes += "META-INF/ECLIPSE_.SF"
resources.excludes += "META-INF/ECLIPSE_.RSA"
```

**Commit:** `build: add JGit 6.8.0 dependency`

---

## Task 2: Add `required` field to `ToolParameter`

**WHAT:** Tool parameters can be declared optional so they don't appear in Anthropic's `required` array, enabling tools like `read_file` to have optional `offset` and `limit` params. Existing tools are unaffected (all their params default to `required = true`).

**HOW:** Add `val required: Boolean = true` to `ToolParameter` in `Tool.kt`. Update `buildToolsJson()` in `AmplifierSession.kt` to only include required params in the `required` JSON array.

**PROOF:** `./gradlew :app:assembleDebug` succeeds. No existing tool behaviour changes because the default is `true`.

**Files to modify:**

`app/src/main/kotlin/com/vela/app/ai/tools/Tool.kt` — update `ToolParameter`:
```kotlin
data class ToolParameter(
    val name: String,
    val type: String,          // "string" | "number" | "boolean" | "integer"
    val description: String,
    val required: Boolean = true,
)
```

`app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt` — in `buildToolsJson()`, replace the `required` array block. Find this existing code:
```kotlin
val params = JSONObject().put("type", "object").put("properties", props).also { obj ->
    if (tool.parameters.isNotEmpty()) obj.put("required", JSONArray().also { req ->
        tool.parameters.forEach { req.put(it.name) }
    })
}
```

Replace with:
```kotlin
val requiredParams = tool.parameters.filter { it.required }
val params = JSONObject().put("type", "object").put("properties", props).also { obj ->
    if (requiredParams.isNotEmpty()) obj.put("required", JSONArray().also { req ->
        requiredParams.forEach { req.put(it.name) }
    })
}
```

**Commit:** `feat(tools): add required field to ToolParameter, filter in buildToolsJson`

---

## Task 3: VaultManager

**WHAT:** `VaultManager` provides a vault root directory and safe path resolution. Traversal attacks (`../../etc/passwd`) return `null`. All subsequent file tools call `resolve()` before touching disk.

**HOW:** Constructor takes a `File` root directly (no Android Context dependency — makes unit testing trivial). `resolve(path)` strips leading `/`, resolves canonically, and checks the result starts with the canonical root path.

**PROOF:**
```
./gradlew :app:testDebugUnitTest --tests "com.vela.app.vault.VaultManagerTest"
```
Expected: 4 tests pass — relative path resolves, absolute-style path (with leading `/`) resolves, traversal attack returns null, root itself resolves.

**New files:**
- `app/src/main/kotlin/com/vela/app/vault/VaultManager.kt`
- `app/src/test/kotlin/com/vela/app/vault/VaultManagerTest.kt`

**`VaultManager.kt`:**
```kotlin
package com.vela.app.vault

import java.io.File

class VaultManager(val root: File) {

    fun init() { root.mkdirs() }

    /**
     * Resolve a path to a File within the vault root.
     * Leading "/" is stripped so AI-provided absolute-style paths work.
     * Returns null if the resolved path escapes the vault root (traversal attack).
     */
    fun resolve(path: String): File? {
        val sanitized = path.trimStart('/')
        val candidate = File(root, sanitized)
        return try {
            val canonical     = candidate.canonicalPath
            val rootCanonical = root.canonicalPath
            if (canonical.startsWith(rootCanonical + File.separator) || canonical == rootCanonical) {
                candidate
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
```

**`VaultManagerTest.kt`:**
```kotlin
package com.vela.app.vault

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultManagerTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var manager: VaultManager

    @Before fun setUp() {
        manager = VaultManager(tmp.newFolder("vaults"))
    }

    @Test fun `relative path resolves inside vault root`() {
        val result = manager.resolve("Personal/Notes/today.md")
        assertThat(result).isNotNull()
        assertThat(result!!.canonicalPath).startsWith(manager.root.canonicalPath)
    }

    @Test fun `absolute-style path resolves inside vault root`() {
        val result = manager.resolve("/Personal/Notes/today.md")
        assertThat(result).isNotNull()
        assertThat(result!!.canonicalPath).startsWith(manager.root.canonicalPath)
    }

    @Test fun `traversal attack returns null`() {
        val result = manager.resolve("../../etc/passwd")
        assertThat(result).isNull()
    }

    @Test fun `root itself resolves`() {
        val result = manager.resolve("")
        assertThat(result).isNotNull()
    }
}
```

**Commit:** `feat(vault): add VaultManager with path resolution and traversal protection`

---

## Task 4: VaultSettings

**WHAT:** Per-vault config (remote URL, PAT, branch) is persisted in `SharedPreferences` and retrievable by vault ID. Follows the same pattern as existing API key storage in `AmplifierSession`.

**HOW:** Define a `VaultSettings` interface and an `SharedPrefsVaultSettings` Android implementation. Interface enables easy test stubs for `VaultGitSync` tests.

**PROOF:** `./gradlew :app:assembleDebug` succeeds.

**New file:** `app/src/main/kotlin/com/vela/app/vault/VaultSettings.kt`

```kotlin
package com.vela.app.vault

import android.content.Context

interface VaultSettings {
    fun getRemoteUrl(vaultId: String): String
    fun setRemoteUrl(vaultId: String, url: String)
    fun getPat(vaultId: String): String
    fun setPat(vaultId: String, pat: String)
    fun getBranch(vaultId: String): String
    fun setBranch(vaultId: String, branch: String)
    fun isConfiguredForSync(vaultId: String): Boolean
}

class SharedPrefsVaultSettings(private val context: Context) : VaultSettings {
    private val prefs get() = context.getSharedPreferences("vault_prefs", Context.MODE_PRIVATE)

    override fun getRemoteUrl(vaultId: String): String = prefs.getString("${vaultId}_remote_url", "") ?: ""
    override fun setRemoteUrl(vaultId: String, url: String) { prefs.edit().putString("${vaultId}_remote_url", url).apply() }

    override fun getPat(vaultId: String): String = prefs.getString("${vaultId}_pat", "") ?: ""
    override fun setPat(vaultId: String, pat: String) { prefs.edit().putString("${vaultId}_pat", pat).apply() }

    override fun getBranch(vaultId: String): String = prefs.getString("${vaultId}_branch", "main") ?: "main"
    override fun setBranch(vaultId: String, branch: String) { prefs.edit().putString("${vaultId}_branch", branch).apply() }

    override fun isConfiguredForSync(vaultId: String): Boolean =
        getRemoteUrl(vaultId).isNotBlank() && getPat(vaultId).isNotBlank()
}
```

**Commit:** `feat(vault): add VaultSettings interface and SharedPrefs implementation`

---

## Task 5: Room — VaultEntity, VaultDao, VaultRegistry, and MIGRATION\_6\_7

**WHAT:** Vaults are persisted in Room. `ConversationEntity` gains a `mode` column. The DB migrates cleanly from version 6 to 7.

**HOW:** New entity, DAO, registry. MIGRATION\_6\_7 does two things: (1) adds `mode TEXT NOT NULL DEFAULT 'default'` to `conversations`, (2) creates the `vaults` table. `VaultRegistry` wraps the DAO with domain logic.

**PROOF:** `./gradlew :app:assembleDebug` — Room's annotation processor will fail at compile time if the entity/DAO/DB are inconsistent. Build success means the schema is coherent.

**New files:**
- `app/src/main/kotlin/com/vela/app/data/db/VaultEntity.kt`
- `app/src/main/kotlin/com/vela/app/data/db/VaultDao.kt`
- `app/src/main/kotlin/com/vela/app/vault/VaultRegistry.kt`

**Modified files:**
- `app/src/main/kotlin/com/vela/app/data/db/ConversationEntity.kt`
- `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt`

---

**`VaultEntity.kt`:**
```kotlin
package com.vela.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "vaults")
data class VaultEntity(
    @PrimaryKey val id: String,
    val name: String,
    val localPath: String,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
```

**`VaultDao.kt`:**
```kotlin
package com.vela.app.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {
    @Query("SELECT * FROM vaults ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<VaultEntity>>

    @Query("SELECT * FROM vaults WHERE isEnabled = 1")
    suspend fun getEnabled(): List<VaultEntity>

    @Query("SELECT * FROM vaults WHERE id = :id")
    suspend fun getById(id: String): VaultEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(vault: VaultEntity)

    @Update
    suspend fun update(vault: VaultEntity)

    @Delete
    suspend fun delete(vault: VaultEntity)
}
```

**`VaultRegistry.kt`:**
```kotlin
package com.vela.app.vault

import com.vela.app.data.db.VaultDao
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.util.UUID

class VaultRegistry(
    private val dao: VaultDao,
    private val vaultManager: VaultManager,
) {
    fun observeAll(): Flow<List<VaultEntity>> = dao.observeAll()

    suspend fun getEnabledVaults(): List<VaultEntity> = dao.getEnabled()

    suspend fun addVault(name: String): VaultEntity {
        val id        = UUID.randomUUID().toString()
        val localPath = File(vaultManager.root, id).absolutePath
        File(localPath).mkdirs()
        val entity = VaultEntity(id = id, name = name, localPath = localPath)
        dao.insert(entity)
        return entity
    }

    suspend fun setEnabled(vaultId: String, enabled: Boolean) {
        val entity = dao.getById(vaultId) ?: return
        dao.update(entity.copy(isEnabled = enabled))
    }

    suspend fun delete(vaultId: String) {
        val entity = dao.getById(vaultId) ?: return
        dao.delete(entity)
        File(entity.localPath).deleteRecursively()
    }

    /**
     * Find which enabled vault root contains the given file.
     * Used by file tools to confirm a path belongs to a known active vault.
     */
    suspend fun resolveVaultForPath(file: File): VaultEntity? =
        getEnabledVaults().firstOrNull { vault ->
            file.canonicalPath.startsWith(File(vault.localPath).canonicalPath)
        }
}
```

**`ConversationEntity.kt`** — add `mode` field:
```kotlin
package com.vela.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    @ColumnInfo(defaultValue = "default") val mode: String = "default",
)
```

**`VelaDatabase.kt`** — bump version, add entity + DAO + migration:
```kotlin
@Database(
    entities = [
        MessageEntity::class,
        ConversationEntity::class,
        SshNodeEntity::class,
        TurnEntity::class,
        TurnEventEntity::class,
        VaultEntity::class,           // ← added
    ],
    version = 7,                       // ← bumped from 6
    exportSchema = true,
)
abstract class VelaDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun conversationDao(): ConversationDao
    abstract fun sshNodeDao(): SshNodeDao
    abstract fun turnDao(): TurnDao
    abstract fun turnEventDao(): TurnEventDao
    abstract fun vaultDao(): VaultDao  // ← added
}
```

Add the new migration constant at the bottom of `VelaDatabase.kt`:
```kotlin
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add mode column to conversations (used by Phase 2 harness)
        db.execSQL("ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'default'")
        // Create vaults table
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS vaults (
                id TEXT NOT NULL PRIMARY KEY,
                name TEXT NOT NULL,
                localPath TEXT NOT NULL,
                isEnabled INTEGER NOT NULL DEFAULT 1,
                createdAt INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}
```

**`AppModule.kt`** — add `MIGRATION_6_7` to the migration list and a DAO provider. Find the existing database builder:
```kotlin
Room.databaseBuilder(ctx, VelaDatabase::class.java, "vela_database")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
    .build()
```
Change to:
```kotlin
Room.databaseBuilder(ctx, VelaDatabase::class.java, "vela_database")
    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
    .build()
```

Add a DAO provider alongside the other DAO providers:
```kotlin
@Provides fun provideVaultDao(db: VelaDatabase): VaultDao = db.vaultDao()
```

**Commit:** `feat(vault): add VaultEntity/Dao/Registry + ConversationEntity.mode + MIGRATION_6_7`

---

## Task 6: VaultGitSync

**WHAT:** JGit operations (init, clone, add, commit, push, pull, status, log) work against a vault directory and return human-readable result strings. Never throws — all errors become strings returned to the AI.

**HOW:** Wrap JGit API. Commits use an explicit `PersonIdent` (no dependency on system git config, which may not exist in test or CI environments). HTTPS auth via `UsernamePasswordCredentialsProvider("token", pat)`. All ops on `Dispatchers.IO`.

**PROOF:**
```
./gradlew :app:testDebugUnitTest --tests "com.vela.app.vault.VaultGitSyncTest"
```
Expected: 3 tests pass — commit returns hash+message, status reflects untracked files, log contains recent commits.

**New files:**
- `app/src/main/kotlin/com/vela/app/vault/VaultGitSync.kt`
- `app/src/test/kotlin/com/vela/app/vault/VaultGitSyncTest.kt`

**`VaultGitSync.kt`:**
```kotlin
package com.vela.app.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

private val VELA_IDENT = PersonIdent("Vela", "vela@vela.app")

class VaultGitSync(private val vaultSettings: VaultSettings) {

    private fun credProvider(vaultId: String) =
        UsernamePasswordCredentialsProvider("token", vaultSettings.getPat(vaultId))

    suspend fun initRepo(vaultPath: File): String = withContext(Dispatchers.IO) {
        runCatching {
            Git.init().setDirectory(vaultPath).call().close()
            "Initialized empty git repository in ${vaultPath.path}"
        }.getOrElse { "Error initializing repo: ${it.message}" }
    }

    suspend fun cloneIfNeeded(vaultId: String, vaultPath: File): String = withContext(Dispatchers.IO) {
        if (File(vaultPath, ".git").exists()) return@withContext "Already cloned."
        val remote = vaultSettings.getRemoteUrl(vaultId)
        if (remote.isBlank()) return@withContext "No remote configured."
        runCatching {
            Git.cloneRepository()
                .setURI(remote)
                .setDirectory(vaultPath)
                .setBranch(vaultSettings.getBranch(vaultId))
                .setCredentialsProvider(credProvider(vaultId))
                .call().close()
            "Cloned $remote into ${vaultPath.path}"
        }.getOrElse { "Error cloning: ${it.message}" }
    }

    suspend fun addAll(vaultPath: File): String = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(vaultPath).use { git ->
                git.add().addFilepattern(".").call()
                "Staged all changes."
            }
        }.getOrElse { "Error staging: ${it.message}" }
    }

    suspend fun commit(
        vaultId: String,
        vaultPath: File,
        message: String,
        addAll: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(vaultPath).use { git ->
                val result = git.commit()
                    .setMessage(message)
                    .setAuthor(VELA_IDENT)
                    .setCommitter(VELA_IDENT)
                    .setAll(addAll)
                    .call()
                "[${result.id.abbreviate(7).name()}] $message"
            }
        }.getOrElse { "Error committing: ${it.message}" }
    }

    suspend fun push(vaultId: String, vaultPath: File): String = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(vaultPath).use { git ->
                git.push().setCredentialsProvider(credProvider(vaultId)).call()
                "Pushed to ${vaultSettings.getRemoteUrl(vaultId)}"
            }
        }.getOrElse { "Error pushing: ${it.message}" }
    }

    suspend fun pull(vaultId: String, vaultPath: File): String = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(vaultPath).use { git ->
                val result = git.pull()
                    .setCredentialsProvider(credProvider(vaultId))
                    .setRebase(true)
                    .call()
                if (result.isSuccessful) "Pulled successfully."
                else "Pull failed: ${result.rebaseResult?.status}"
            }
        }.getOrElse { "Error pulling: ${it.message}" }
    }

    suspend fun status(vaultPath: File): String = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(vaultPath).use { git ->
                val s = git.status().call()
                if (s.isClean) "nothing to commit, working tree clean"
                else buildString {
                    if (s.added.isNotEmpty())      appendLine("Added: ${s.added.joinToString(", ")}")
                    if (s.modified.isNotEmpty())   appendLine("Modified: ${s.modified.joinToString(", ")}")
                    if (s.removed.isNotEmpty())    appendLine("Removed: ${s.removed.joinToString(", ")}")
                    if (s.untracked.isNotEmpty())  appendLine("Untracked: ${s.untracked.joinToString(", ")}")
                }.trim()
            }
        }.getOrElse { "Error getting status: ${it.message}" }
    }

    suspend fun log(vaultPath: File, count: Int = 10): String = withContext(Dispatchers.IO) {
        runCatching {
            Git.open(vaultPath).use { git ->
                git.log().setMaxCount(count).call()
                    .joinToString("\n") { "${it.id.abbreviate(7).name()} ${it.shortMessage}" }
                    .ifEmpty { "(no commits yet)" }
            }
        }.getOrElse { "Error reading log: ${it.message}" }
    }
}
```

**`VaultGitSyncTest.kt`:**
```kotlin
package com.vela.app.vault

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultGitSyncTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var workDir: File
    private lateinit var sync: VaultGitSync

    @Before fun setUp() {
        workDir = tmp.newFolder("vault")
        val settings = object : VaultSettings {
            override fun getPat(vaultId: String) = ""
            override fun setPat(vaultId: String, pat: String) {}
            override fun getRemoteUrl(vaultId: String) = ""
            override fun setRemoteUrl(vaultId: String, url: String) {}
            override fun getBranch(vaultId: String) = "main"
            override fun setBranch(vaultId: String, branch: String) {}
            override fun isConfiguredForSync(vaultId: String) = false
        }
        sync = VaultGitSync(settings)
        runBlocking {
            sync.initRepo(workDir)
            File(workDir, "README.md").writeText("# Vault")
            sync.addAll(workDir)
            sync.commit("test-vault", workDir, "initial commit")
        }
    }

    @Test fun `addAll and commit returns abbreviated hash and message`() = runBlocking {
        File(workDir, "notes.md").writeText("hello vault")
        sync.addAll(workDir)
        val result = sync.commit("test-vault", workDir, "add notes")
        assertThat(result).contains("add notes")
        // abbreviated hash is 7 hex chars in square brackets
        assertThat(result).matches("\\[[0-9a-f]{7}\\] add notes")
    }

    @Test fun `status reports untracked files`() = runBlocking {
        File(workDir, "new.md").writeText("new file")
        val result = sync.status(workDir)
        assertThat(result).contains("new.md")
    }

    @Test fun `log returns recent commits`() = runBlocking {
        val result = sync.log(workDir, count = 5)
        assertThat(result).contains("initial commit")
    }
}
```

**Commit:** `feat(vault): add VaultGitSync with JGit operations`

---

## Task 7: ReadFileTool, WriteFileTool, EditFileTool

**WHAT:** The AI can read files (with line numbers), list directories, write/overwrite files, and do exact-string replacement edits — all scoped to the vault root.

**HOW:** All three tools live in `VaultTools.kt`. Each calls `vault.resolve(path)` first; `null` returns an error string. `ReadFileTool` formats with cat -n style line numbers and supports `offset`/`limit`. `EditFileTool` errors if `old_string` not found or is ambiguous unless `replace_all=true`.

**PROOF:**
```
./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.tools.VaultToolsTest"
```
Expected: 7 tests pass — read returns line numbers, read directory returns listing, read with offset/limit returns correct window, traversal returns error string, write creates a file, edit replaces once, edit errors on ambiguous match.

**New files:**
- `app/src/main/kotlin/com/vela/app/ai/tools/VaultTools.kt`
- `app/src/test/kotlin/com/vela/app/ai/tools/VaultToolsTest.kt`

**`VaultTools.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.vela.app.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ReadFileTool(private val vault: VaultManager) : Tool {
    override val name        = "read_file"
    override val displayName = "Read File"
    override val icon        = "📄"
    override val description = "Read a file from the vault. Returns content with line numbers (cat -n format). " +
                               "If path is a directory, returns a listing."
    override val parameters  = listOf(
        ToolParameter("file_path", "string",  "Path to the file or directory (relative to vault root)"),
        ToolParameter("offset",    "integer", "Line number to start reading from (1-indexed)",   required = false),
        ToolParameter("limit",     "integer", "Maximum number of lines to return (default 2000)", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val path   = args["file_path"] as? String ?: return@withContext "Error: file_path is required"
        val offset = (args["offset"] as? Number)?.toInt()?.let { it - 1 }?.coerceAtLeast(0) ?: 0
        val limit  = (args["limit"]  as? Number)?.toInt() ?: 2000

        val file = vault.resolve(path) ?: return@withContext "Error: path '$path' is outside the vault"
        if (!file.exists()) return@withContext "Error: '$path' not found"

        if (file.isDirectory) {
            buildString {
                appendLine("Directory: $path")
                appendLine()
                file.listFiles()
                    ?.sortedWith(compareBy({ !it.isDirectory }, { it.name }))
                    ?.forEach { entry ->
                        val type = if (entry.isDirectory) "  DIR " else "  FILE"
                        appendLine("$type  ${entry.name}")
                    }
            }
        } else {
            val lines    = file.readLines()
            val selected = lines.drop(offset).take(limit)
            if (selected.isEmpty()) return@withContext "(empty file)"
            selected.mapIndexed { i, line ->
                val lineNum = (offset + i + 1).toString().padStart(6)
                "$lineNum\t$line"
            }.joinToString("\n")
        }
    }
}

class WriteFileTool(private val vault: VaultManager) : Tool {
    override val name        = "write_file"
    override val displayName = "Write File"
    override val icon        = "✏️"
    override val description = "Create or overwrite a file in the vault. Parent directories are created automatically."
    override val parameters  = listOf(
        ToolParameter("file_path", "string", "Path to the file (relative to vault root)"),
        ToolParameter("content",   "string", "Content to write"),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val path    = args["file_path"] as? String ?: return@withContext "Error: file_path is required"
        val content = args["content"]   as? String ?: return@withContext "Error: content is required"

        val file = vault.resolve(path) ?: return@withContext "Error: path '$path' is outside the vault"
        file.parentFile?.mkdirs()
        file.writeText(content)
        "Wrote ${content.lines().size} lines to $path"
    }
}

class EditFileTool(private val vault: VaultManager) : Tool {
    override val name        = "edit_file"
    override val displayName = "Edit File"
    override val icon        = "✏️"
    override val description = "Exact string replacement in a file. Fails if old_string is not found or appears " +
                               "more than once (use replace_all=true to replace every occurrence)."
    override val parameters  = listOf(
        ToolParameter("file_path",   "string",  "Path to the file"),
        ToolParameter("old_string",  "string",  "The exact text to find and replace"),
        ToolParameter("new_string",  "string",  "The replacement text"),
        ToolParameter("replace_all", "boolean", "Replace all occurrences (default: false)", required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val path       = args["file_path"]   as? String  ?: return@withContext "Error: file_path is required"
        val oldStr     = args["old_string"]  as? String  ?: return@withContext "Error: old_string is required"
        val newStr     = args["new_string"]  as? String  ?: return@withContext "Error: new_string is required"
        val replaceAll = args["replace_all"] as? Boolean ?: false

        val file = vault.resolve(path) ?: return@withContext "Error: path '$path' is outside the vault"
        if (!file.exists()) return@withContext "Error: '$path' not found"

        val content = file.readText()
        val count   = content.split(oldStr).size - 1

        when {
            count == 0 -> "Error: old_string not found in $path"
            count > 1 && !replaceAll ->
                "Error: old_string found $count times in $path — use replace_all=true or provide more context"
            else -> {
                val newContent = if (replaceAll) content.replace(oldStr, newStr)
                                 else            content.replaceFirst(oldStr, newStr)
                file.writeText(newContent)
                "Replaced ${if (replaceAll) count else 1} occurrence(s) in $path"
            }
        }
    }
}
```

**`VaultToolsTest.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VaultToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var vault: VaultManager
    private lateinit var read: ReadFileTool
    private lateinit var write: WriteFileTool
    private lateinit var edit: EditFileTool

    @Before fun setUp() {
        vault = VaultManager(tmp.newFolder("vault"))
        read  = ReadFileTool(vault)
        write = WriteFileTool(vault)
        edit  = EditFileTool(vault)
    }

    // ── ReadFileTool ─────────────────────────────────────────────────────────

    @Test fun `read file returns cat-n line numbers`() = runBlocking {
        File(vault.root, "notes.md").writeText("line one\nline two\nline three")
        val result = read.execute(mapOf("file_path" to "notes.md"))
        assertThat(result).contains("1\tline one")
        assertThat(result).contains("2\tline two")
    }

    @Test fun `read directory returns listing with DIR and FILE markers`() = runBlocking {
        File(vault.root, "subdir").mkdirs()
        File(vault.root, "notes.md").writeText("hello")
        val result = read.execute(mapOf("file_path" to ""))
        assertThat(result).contains("DIR")
        assertThat(result).contains("FILE")
        assertThat(result).contains("notes.md")
    }

    @Test fun `read with offset and limit returns correct window`() = runBlocking {
        File(vault.root, "big.md").writeText((1..10).joinToString("\n") { "line $it" })
        val result = read.execute(mapOf("file_path" to "big.md", "offset" to 3, "limit" to 2))
        assertThat(result).contains("3\tline 3")
        assertThat(result).contains("4\tline 4")
        assertThat(result).doesNotContain("line 5")
    }

    @Test fun `read traversal path returns error`() = runBlocking {
        val result = read.execute(mapOf("file_path" to "../../etc/passwd"))
        assertThat(result).startsWith("Error:")
    }

    // ── WriteFileTool ────────────────────────────────────────────────────────

    @Test fun `write creates file and reports line count`() = runBlocking {
        val result = write.execute(mapOf("file_path" to "new/dir/file.md", "content" to "hello\nworld"))
        assertThat(result).contains("2 lines")
        assertThat(File(vault.root, "new/dir/file.md").readText()).isEqualTo("hello\nworld")
    }

    // ── EditFileTool ─────────────────────────────────────────────────────────

    @Test fun `edit replaces single occurrence`() = runBlocking {
        File(vault.root, "doc.md").writeText("hello world")
        val result = edit.execute(mapOf(
            "file_path"  to "doc.md",
            "old_string" to "world",
            "new_string" to "vault",
        ))
        assertThat(result).contains("1 occurrence")
        assertThat(File(vault.root, "doc.md").readText()).isEqualTo("hello vault")
    }

    @Test fun `edit errors on ambiguous match without replace_all`() = runBlocking {
        File(vault.root, "dup.md").writeText("foo foo foo")
        val result = edit.execute(mapOf(
            "file_path"  to "dup.md",
            "old_string" to "foo",
            "new_string" to "bar",
        ))
        assertThat(result).startsWith("Error:")
        assertThat(result).contains("3 times")
    }
}
```

**Commit:** `feat(tools): add ReadFileTool, WriteFileTool, EditFileTool`

---

## Task 8: GlobTool and GrepTool

**WHAT:** The AI can call `glob("**/*.md")` to find files by pattern and `grep("TODO", output_mode="content")` to search file contents — both scoped to the vault root, both excluding `.git`.

**HOW:** `GlobTool` uses `java.nio.file.Files.walk()` + `FileSystems.getDefault().getPathMatcher("glob:$pattern")`. `GrepTool` uses `java.util.regex.Pattern` over file lines, three output modes. Both skip any path component named `.git`.

**PROOF:**
```
./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.tools.SearchToolsTest"
```
Expected: 5 tests pass — glob finds .md files, glob excludes .git contents, grep finds matching lines in content mode, grep files\_with\_matches returns file paths, grep -i is case-insensitive.

**New files:**
- `app/src/main/kotlin/com/vela/app/ai/tools/SearchTools.kt`
- `app/src/test/kotlin/com/vela/app/ai/tools/SearchToolsTest.kt`

**`SearchTools.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.vela.app.vault.VaultManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Files
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException

class GlobTool(private val vault: VaultManager) : Tool {
    override val name        = "glob"
    override val displayName = "Find Files"
    override val icon        = "🔍"
    override val description = "Find files by glob pattern (e.g. '**/*.md'). Excludes .git. Max 500 results."
    override val parameters  = listOf(
        ToolParameter("pattern", "string",  "Glob pattern (e.g. '**/*.md', 'src/*.kt')"),
        ToolParameter("path",    "string",  "Base directory to search from (default: vault root)", required = false),
        ToolParameter("type",    "string",  "Filter: 'file', 'dir', or 'any' (default: 'file')",  required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val pattern    = args["pattern"] as? String ?: return@withContext "Error: pattern is required"
        val basePath   = args["path"]    as? String
        val typeFilter = args["type"]    as? String ?: "file"

        val base = if (basePath != null) vault.resolve(basePath) ?: vault.root else vault.root
        if (!base.exists()) return@withContext "Error: base path not found"

        val matcher = try {
            FileSystems.getDefault().getPathMatcher("glob:$pattern")
        } catch (e: Exception) {
            return@withContext "Error: invalid glob pattern: ${e.message}"
        }

        val results = mutableListOf<String>()
        Files.walk(base.toPath())
            .filter { p ->
                val relative = base.toPath().relativize(p)
                relative.none { it.toString() == ".git" } &&
                matcher.matches(relative) &&
                when (typeFilter) {
                    "dir"  -> Files.isDirectory(p)
                    "file" -> Files.isRegularFile(p)
                    else   -> true
                }
            }
            .limit(500)
            .forEach { results.add(base.toPath().relativize(it).toString()) }

        if (results.isEmpty()) "(no matches for pattern: $pattern)"
        else results.joinToString("\n")
    }
}

class GrepTool(private val vault: VaultManager) : Tool {
    override val name        = "grep"
    override val displayName = "Search Content"
    override val icon        = "🔍"
    override val description = "Search file contents with regex. output_mode: 'files_with_matches' (default), 'content', 'count'. " +
                               "Excludes .git."
    override val parameters  = listOf(
        ToolParameter("pattern",     "string",  "Regex pattern to search for"),
        ToolParameter("path",        "string",  "File or directory to search (default: vault root)", required = false),
        ToolParameter("output_mode", "string",  "Output mode: 'files_with_matches', 'content', or 'count'", required = false),
        ToolParameter("-i",          "boolean", "Case-insensitive search",                           required = false),
        ToolParameter("-n",          "boolean", "Show line numbers in content mode (default: true)", required = false),
        ToolParameter("-A",          "integer", "Lines to show after each match",                    required = false),
        ToolParameter("-B",          "integer", "Lines to show before each match",                   required = false),
        ToolParameter("-C",          "integer", "Context lines before and after each match",         required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val patternStr  = args["pattern"]     as? String  ?: return@withContext "Error: pattern is required"
        val searchPath  = args["path"]        as? String
        val outputMode  = args["output_mode"] as? String  ?: "files_with_matches"
        val ignoreCase  = args["-i"]          as? Boolean ?: false
        val showLineNum = args["-n"]          as? Boolean ?: true
        val ctxAfter    = ((args["-A"] ?: args["-C"]) as? Number)?.toInt() ?: 0
        val ctxBefore   = ((args["-B"] ?: args["-C"]) as? Number)?.toInt() ?: 0

        val base  = if (searchPath != null) vault.resolve(searchPath) ?: vault.root else vault.root
        val flags = if (ignoreCase) Pattern.CASE_INSENSITIVE else 0
        val regex = try { Pattern.compile(patternStr, flags) }
                    catch (e: PatternSyntaxException) { return@withContext "Error: invalid regex: ${e.message}" }

        val matchingFiles   = mutableListOf<String>()
        val contentLines    = mutableListOf<String>()
        var totalMatchCount = 0

        fun searchFile(file: File) {
            val lines        = runCatching { file.readLines() }.getOrNull() ?: return
            val relativePath = vault.root.toPath().relativize(file.toPath()).toString()
            val matchIndices = lines.indices.filter { regex.matcher(lines[it]).find() }
            if (matchIndices.isEmpty()) return

            matchingFiles.add(relativePath)
            totalMatchCount += matchIndices.size

            if (outputMode == "content" && contentLines.size < 500) {
                val shown = mutableSetOf<Int>()
                matchIndices.forEach { idx ->
                    ((idx - ctxBefore).coerceAtLeast(0)..(idx + ctxAfter).coerceAtMost(lines.lastIndex))
                        .forEach { shown.add(it) }
                }
                contentLines.add("-- $relativePath --")
                shown.sorted().forEach { idx ->
                    val prefix = if (showLineNum) "${idx + 1}:" else ""
                    contentLines.add("$prefix${lines[idx]}")
                }
            }
        }

        if (base.isFile) {
            searchFile(base)
        } else {
            Files.walk(base.toPath())
                .filter { p ->
                    Files.isRegularFile(p) &&
                    p.none { it.toString() == ".git" }
                }
                .limit(200)
                .forEach { searchFile(it.toFile()) }
        }

        when (outputMode) {
            "content" -> if (contentLines.isEmpty()) "(no matches)" else contentLines.joinToString("\n")
            "count"   -> "$totalMatchCount matches in ${matchingFiles.size} files"
            else      -> if (matchingFiles.isEmpty()) "(no matches)" else matchingFiles.joinToString("\n")
        }
    }
}
```

**`SearchToolsTest.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.vault.VaultManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SearchToolsTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var vault: VaultManager
    private lateinit var glob: GlobTool
    private lateinit var grep: GrepTool

    @Before fun setUp() {
        vault = VaultManager(tmp.newFolder("vault"))
        glob  = GlobTool(vault)
        grep  = GrepTool(vault)
        // Set up a small tree: notes/, notes/a.md, notes/b.txt, .git/config
        File(vault.root, "notes").mkdirs()
        File(vault.root, "notes/a.md").writeText("# Alpha\nTODO: finish this")
        File(vault.root, "notes/b.txt").writeText("plain text")
        File(vault.root, ".git").mkdirs()
        File(vault.root, ".git/config").writeText("[core]")
    }

    @Test fun `glob finds only md files`() = runBlocking {
        val result = glob.execute(mapOf("pattern" to "**/*.md"))
        assertThat(result).contains("notes/a.md")
        assertThat(result).doesNotContain("b.txt")
    }

    @Test fun `glob excludes dot-git contents`() = runBlocking {
        val result = glob.execute(mapOf("pattern" to "**/*", "type" to "any"))
        assertThat(result).doesNotContain(".git/config")
    }

    @Test fun `grep content mode returns matching lines`() = runBlocking {
        val result = grep.execute(mapOf("pattern" to "TODO", "output_mode" to "content"))
        assertThat(result).contains("TODO: finish this")
    }

    @Test fun `grep files_with_matches returns file path`() = runBlocking {
        val result = grep.execute(mapOf("pattern" to "TODO"))
        assertThat(result).contains("notes/a.md")
        assertThat(result).doesNotContain("b.txt")
    }

    @Test fun `grep -i is case-insensitive`() = runBlocking {
        val result = grep.execute(mapOf("pattern" to "alpha", "-i" to true, "output_mode" to "content"))
        assertThat(result).contains("Alpha")
    }
}
```

**Commit:** `feat(tools): add GlobTool and GrepTool`

---

## Task 9: BashTool

**WHAT:** The AI can call `bash(command="git commit -am 'update notes'")` and it routes to the correct JGit operation. Unsupported commands return a clear message. This preserves compatibility with the lifeos system prompt which uses `bash` for all git operations.

**HOW:** The `BashTool` constructor takes a `suspend () -> ActiveVault?` lambda (not `VaultRegistry`) so it's trivially testable without any DAO or Room dependency. In production the lambda calls `VaultRegistry.getEnabledVaults().firstOrNull()`. Command routing is string prefix matching against the ~8 known patterns.

**PROOF:**
```
./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.tools.BashToolTest"
```
Expected: 5 tests pass — `git add .` routes correctly, `git commit -am "msg"` extracts message and sets addAll, `git commit -m "msg"` sets addAll=false, `date` returns a date string, unsupported command contains "not supported".

**New files:**
- `app/src/main/kotlin/com/vela/app/ai/tools/BashTool.kt`
- `app/src/test/kotlin/com/vela/app/ai/tools/BashToolTest.kt`

**`BashTool.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.vela.app.vault.VaultGitSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime

class BashTool(
    private val gitSync: VaultGitSync,
    /** Returns the active vault (id, path) or null if no vault is configured. */
    private val activeVault: suspend () -> ActiveVault?,
) : Tool {

    data class ActiveVault(val id: String, val path: File)

    override val name        = "bash"
    override val displayName = "Shell"
    override val icon        = "💻"
    override val description = "Execute shell commands. Supported: git (add/commit/push/pull/status/log), date, ls, mkdir. " +
                               "Unsupported commands return a descriptive error."
    override val parameters  = listOf(
        ToolParameter("command", "string", "Shell command to execute"),
    )

    override suspend fun execute(args: Map<String, Any>): String = withContext(Dispatchers.IO) {
        val cmd = (args["command"] as? String)?.trim()
            ?: return@withContext "Error: command is required"
        route(cmd)
    }

    private suspend fun route(cmd: String): String = when {
        cmd.startsWith("git ")   -> routeGit(cmd.removePrefix("git ").trim())
        cmd == "date"            -> LocalDateTime.now().toString()
        cmd.startsWith("ls")     -> routeLs(cmd)
        cmd.startsWith("mkdir")  -> routeMkdir(cmd)
        else -> "Command not supported on mobile: $cmd\nSupported: git (add/commit/push/pull/status/log), date, ls, mkdir"
    }

    private suspend fun routeGit(gitArgs: String): String {
        val v = activeVault() ?: return "Error: no vault configured. Add a vault in Settings first."
        return when {
            gitArgs == "add ." || gitArgs == "add -A"  -> gitSync.addAll(v.path)
            gitArgs == "status"                        -> gitSync.status(v.path)
            gitArgs.startsWith("commit")               -> parseCommit(gitArgs, v)
            gitArgs.startsWith("push")                 -> gitSync.push(v.id, v.path)
            gitArgs.startsWith("pull")                 -> gitSync.pull(v.id, v.path)
            gitArgs.startsWith("log")                  -> parseLog(gitArgs, v.path)
            else -> "Git subcommand not supported on mobile: git $gitArgs"
        }
    }

    private suspend fun parseCommit(gitArgs: String, v: ActiveVault): String {
        // Matches: commit -m "msg", commit -am "msg", commit -m 'msg', single or double quotes
        val regex = Regex("""commit\s+(-[am]+)\s+["'](.+?)["']\s*$""")
        val match = regex.find(gitArgs)
            ?: return "Error: could not parse commit message from: git $gitArgs\n" +
                      "Expected: git commit -m \"message\" or git commit -am \"message\""
        val flags   = match.groupValues[1]
        val message = match.groupValues[2].trim()
        return gitSync.commit(v.id, v.path, message, addAll = flags.contains('a'))
    }

    private suspend fun parseLog(gitArgs: String, path: File): String {
        val count = Regex("""-(\d+)""").find(gitArgs)?.groupValues?.get(1)?.toIntOrNull() ?: 10
        return gitSync.log(path, count)
    }

    private fun routeLs(cmd: String): String {
        val parts   = cmd.trim().split(Regex("\\s+"))
        val pathArg = parts.lastOrNull { !it.startsWith("-") && it != "ls" }
        val dir     = if (pathArg != null) File(pathArg) else File(".")
        if (!dir.exists()) return "ls: ${pathArg ?: "."}: No such file or directory"
        return dir.listFiles()?.joinToString("\n") { it.name } ?: "(empty)"
    }

    private fun routeMkdir(cmd: String): String {
        val parts   = cmd.trim().split(Regex("\\s+"))
        val pathArg = parts.lastOrNull { !it.startsWith("-") && it != "mkdir" }
            ?: return "mkdir: missing operand"
        return if (File(pathArg).mkdirs()) "Created: $pathArg"
               else "mkdir: $pathArg: directory already exists or could not be created"
    }
}
```

**`BashToolTest.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import com.vela.app.vault.VaultGitSync
import com.vela.app.vault.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BashToolTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var workDir: File
    private lateinit var bash: BashTool

    @Before fun setUp() {
        workDir = tmp.newFolder("vault")
        val settings = object : VaultSettings {
            override fun getPat(vaultId: String) = ""
            override fun setPat(vaultId: String, pat: String) {}
            override fun getRemoteUrl(vaultId: String) = ""
            override fun setRemoteUrl(vaultId: String, url: String) {}
            override fun getBranch(vaultId: String) = "main"
            override fun setBranch(vaultId: String, branch: String) {}
            override fun isConfiguredForSync(vaultId: String) = false
        }
        val gitSync = VaultGitSync(settings)
        runBlocking {
            gitSync.initRepo(workDir)
            File(workDir, "README.md").writeText("# Vault")
            gitSync.addAll(workDir)
            gitSync.commit("t", workDir, "initial commit")
        }
        bash = BashTool(
            gitSync      = gitSync,
            activeVault  = { BashTool.ActiveVault("test-vault", workDir) },
        )
    }

    @Test fun `git add dot stages changes`() = runBlocking {
        File(workDir, "new.md").writeText("new")
        val result = bash.execute(mapOf("command" to "git add ."))
        assertThat(result).isEqualTo("Staged all changes.")
    }

    @Test fun `git commit -m extracts message and commits`() = runBlocking {
        File(workDir, "note.md").writeText("content")
        bash.execute(mapOf("command" to "git add ."))
        val result = bash.execute(mapOf("command" to """git commit -m "add note""""))
        assertThat(result).contains("add note")
    }

    @Test fun `git commit -am adds and commits in one step`() = runBlocking {
        // Modify an already-tracked file
        File(workDir, "README.md").writeText("updated")
        val result = bash.execute(mapOf("command" to """git commit -am "update readme""""))
        assertThat(result).contains("update readme")
    }

    @Test fun `date returns a date string`() = runBlocking {
        val result = bash.execute(mapOf("command" to "date"))
        // LocalDateTime.now().toString() starts with the year
        assertThat(result).matches("\\d{4}-.*")
    }

    @Test fun `unsupported command says not supported`() = runBlocking {
        val result = bash.execute(mapOf("command" to "rm -rf /"))
        assertThat(result).contains("not supported")
    }
}
```

**Commit:** `feat(tools): add BashTool with git command routing`

---

## Task 10: TodoTool

**WHAT:** The AI can call `todo(action="create", todos=[...])` to manage a session todo list and `todo(action="list")` to retrieve it. State is in-memory per session.

**HOW:** The `todos` argument is passed as a JSON array string. Three actions: `create`/`update` (replace all), `list` (return formatted). Status icons: `✓` completed, `→` in\_progress, `○` pending.

**PROOF:**
```
./gradlew :app:testDebugUnitTest --tests "com.vela.app.ai.tools.TodoToolTest"
```
Expected: 4 tests pass — create sets list and returns summary, list formats with icons, update replaces the list, invalid action returns error.

**New files:**
- `app/src/main/kotlin/com/vela/app/ai/tools/TodoTool.kt`
- `app/src/test/kotlin/com/vela/app/ai/tools/TodoToolTest.kt`

**`TodoTool.kt`:**
```kotlin
package com.vela.app.ai.tools

import org.json.JSONArray

class TodoTool : Tool {
    override val name        = "todo"
    override val displayName = "Todo List"
    override val icon        = "✅"
    override val description = "Manage a session todo list. action: 'create' (replace all), 'update' (replace all), 'list' (read current)."
    override val parameters  = listOf(
        ToolParameter("action", "string", "Action: 'create', 'update', or 'list'"),
        ToolParameter("todos",  "string", "JSON array of todo objects: [{\"content\":\"...\",\"status\":\"pending|in_progress|completed\",\"activeForm\":\"...\"}]",
                      required = false),
    )

    private data class TodoItem(val content: String, val status: String, val activeForm: String)
    private var todos: List<TodoItem> = emptyList()

    override suspend fun execute(args: Map<String, Any>): String {
        val action = args["action"] as? String ?: return "Error: action is required"
        return when (action) {
            "create", "update" -> {
                val todosJson = args["todos"] as? String
                    ?: return "Error: todos is required for action '$action'"
                todos = parseTodos(todosJson) ?: return "Error: todos must be a valid JSON array"
                val byStatus = todos.groupBy { it.status }
                "Todo list updated: ${todos.size} items " +
                "(${byStatus["pending"]?.size ?: 0} pending, " +
                "${byStatus["in_progress"]?.size ?: 0} in progress, " +
                "${byStatus["completed"]?.size ?: 0} completed)"
            }
            "list" -> {
                if (todos.isEmpty()) return "(no todos)"
                todos.joinToString("\n") { item ->
                    val icon = when (item.status) {
                        "completed"  -> "✓"
                        "in_progress" -> "→"
                        else         -> "○"
                    }
                    "$icon ${item.content}"
                }
            }
            else -> "Error: unknown action '$action'. Use 'create', 'update', or 'list'"
        }
    }

    private fun parseTodos(json: String): List<TodoItem>? = runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val obj = arr.getJSONObject(i)
            TodoItem(
                content    = obj.getString("content"),
                status     = obj.optString("status", "pending"),
                activeForm = obj.optString("activeForm", ""),
            )
        }
    }.getOrNull()
}
```

**`TodoToolTest.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test

class TodoToolTest {

    private lateinit var tool: TodoTool

    @Before fun setUp() { tool = TodoTool() }

    @Test fun `create sets list and returns count summary`() = runBlocking {
        val result = tool.execute(mapOf(
            "action" to "create",
            "todos"  to """[{"content":"Task A","status":"pending","activeForm":"Doing A"},
                           {"content":"Task B","status":"in_progress","activeForm":"Doing B"}]""",
        ))
        assertThat(result).contains("2 items")
        assertThat(result).contains("1 pending")
        assertThat(result).contains("1 in progress")
    }

    @Test fun `list returns formatted todos with status icons`() = runBlocking {
        tool.execute(mapOf(
            "action" to "create",
            "todos"  to """[{"content":"Done task","status":"completed","activeForm":""},
                           {"content":"Active task","status":"in_progress","activeForm":""},
                           {"content":"Pending task","status":"pending","activeForm":""}]""",
        ))
        val result = tool.execute(mapOf("action" to "list"))
        assertThat(result).contains("✓ Done task")
        assertThat(result).contains("→ Active task")
        assertThat(result).contains("○ Pending task")
    }

    @Test fun `update replaces the entire list`() = runBlocking {
        tool.execute(mapOf("action" to "create", "todos" to """[{"content":"Old","status":"pending","activeForm":""}]"""))
        tool.execute(mapOf("action" to "update", "todos" to """[{"content":"New","status":"completed","activeForm":""}]"""))
        val result = tool.execute(mapOf("action" to "list"))
        assertThat(result).contains("New")
        assertThat(result).doesNotContain("Old")
    }

    @Test fun `invalid action returns error`() = runBlocking {
        val result = tool.execute(mapOf("action" to "delete"))
        assertThat(result).startsWith("Error:")
    }
}
```

**Commit:** `feat(tools): add TodoTool with in-memory session todo list`

---

## Task 11: LoadSkillTool (stub)

**WHAT:** The `load_skill` tool exists in the tool list and returns sensible stub responses. AI can call it and receive a non-crashing result. Full SkillsEngine comes in Phase 2.

**HOW:** Implement `LoadSkillTool` with the correct parameter schema. Return "(no skills available)" for list/search, "Skill not found" for specific lookups.

**PROOF:** `./gradlew :app:assembleDebug` succeeds. The tool appears in `buildToolsJson()` output (verify in Logcat with tag `AmplifierSession` after starting a conversation).

**New file:** `app/src/main/kotlin/com/vela/app/ai/tools/LoadSkillTool.kt`

```kotlin
package com.vela.app.ai.tools

class LoadSkillTool : Tool {
    override val name        = "load_skill"
    override val displayName = "Load Skill"
    override val icon        = "🧠"
    override val description = "Load domain knowledge skills. Operations: list (all available skills), " +
                               "search (filter by keyword), info (metadata only), skill_name (load full content)."
    override val parameters  = listOf(
        ToolParameter("list",       "boolean", "If true, return list of all available skills",              required = false),
        ToolParameter("search",     "string",  "Search term to filter skills by name or description",       required = false),
        ToolParameter("info",       "string",  "Get metadata for a specific skill without loading content", required = false),
        ToolParameter("skill_name", "string",  "Name of skill to load full content for",                   required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val listAll   = args["list"]       as? Boolean ?: false
        val search    = args["search"]     as? String
        val info      = args["info"]       as? String
        val skillName = args["skill_name"] as? String

        return when {
            listAll || search != null -> "(no skills available — skills directory not yet configured)"
            info       != null        -> "Skill '$info' not found"
            skillName  != null        -> "Skill '$skillName' not found"
            else -> "Error: specify one of: list=true, search=\"...\", info=\"...\", or skill_name=\"...\""
        }
    }
}
```

**Commit:** `feat(tools): add LoadSkillTool stub`

---

## Task 12: Wire all vault tools into AppModule

**WHAT:** All new vault tools appear in the AI's tool list. The app builds and runs. Vault infrastructure (VaultManager, VaultSettings, VaultGitSync, VaultRegistry) is provided as Hilt singletons.

**HOW:** Add providers for each vault layer component. Update `provideTools` to include all new tools. `BashTool` gets a lambda that reads from `VaultRegistry`.

**PROOF:** `./gradlew :app:assembleDebug` succeeds. Run the app, start a conversation, and observe in Logcat (`tag:AmplifierSession`) that the `toolsJson` string includes `read_file`, `write_file`, `edit_file`, `glob`, `grep`, `bash`, `todo`, `load_skill`.

**File to modify:** `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

Add these imports at the top:
```kotlin
import com.vela.app.data.db.VaultDao
import com.vela.app.vault.VaultGitSync
import com.vela.app.vault.VaultManager
import com.vela.app.vault.VaultRegistry
import com.vela.app.vault.SharedPrefsVaultSettings
import com.vela.app.vault.VaultSettings
import com.vela.app.ai.tools.BashTool
import com.vela.app.ai.tools.EditFileTool
import com.vela.app.ai.tools.GlobTool
import com.vela.app.ai.tools.GrepTool
import com.vela.app.ai.tools.LoadSkillTool
import com.vela.app.ai.tools.ReadFileTool
import com.vela.app.ai.tools.TodoTool
import com.vela.app.ai.tools.WriteFileTool
import java.io.File
```

Add these providers inside `object AppModule`:
```kotlin
@Provides @Singleton
fun provideVaultManager(@ApplicationContext ctx: Context): VaultManager =
    VaultManager(File(ctx.filesDir, "vaults")).also { it.init() }

@Provides @Singleton
fun provideVaultSettings(@ApplicationContext ctx: Context): VaultSettings =
    SharedPrefsVaultSettings(ctx)

@Provides @Singleton
fun provideVaultGitSync(settings: VaultSettings): VaultGitSync = VaultGitSync(settings)

@Provides @Singleton
fun provideVaultRegistry(dao: VaultDao, vaultManager: VaultManager): VaultRegistry =
    VaultRegistry(dao, vaultManager)

@Provides fun provideVaultDao(db: VelaDatabase): VaultDao = db.vaultDao()
```

Replace the existing `provideTools` function:
```kotlin
@Provides @Singleton
fun provideTools(
    @ApplicationContext ctx: Context,
    client: OkHttpClient,
    sshNodeRegistry: SshNodeRegistry,
    sshKeyManager: SshKeyManager,
    vaultManager: VaultManager,
    vaultRegistry: VaultRegistry,
    vaultGitSync: VaultGitSync,
): List<Tool> = listOf(
    // Existing tools
    GetTimeTool(), GetDateTool(), GetBatteryTool(ctx),
    SearchWebTool(client), FetchUrlTool(client),
    ListSshNodesTool(sshNodeRegistry),
    SshCommandTool(sshNodeRegistry, sshKeyManager),
    // Vault file tools
    ReadFileTool(vaultManager),
    WriteFileTool(vaultManager),
    EditFileTool(vaultManager),
    GlobTool(vaultManager),
    GrepTool(vaultManager),
    // Orchestration tools
    BashTool(
        gitSync     = vaultGitSync,
        activeVault = {
            vaultRegistry.getEnabledVaults().firstOrNull()
                ?.let { BashTool.ActiveVault(it.id, File(it.localPath)) }
        },
    ),
    TodoTool(),
    LoadSkillTool(),
)
```

**Commit:** `feat(di): wire vault tools and vault layer into AppModule`

---

## Task 13: Full build + smoke verification

**WHAT:** All Phase 1 unit tests pass and the debug APK builds cleanly.

**HOW:** Run both the test suite and the build.

**PROOF:**
```bash
# Run all unit tests — must show BUILD SUCCESSFUL with 0 failures
./gradlew :app:testDebugUnitTest

# Build the APK — must show BUILD SUCCESSFUL
./gradlew :app:assembleDebug
```

If any test fails: fix before considering Phase 1 complete. Common issues to check:
- JGit commit identity: ensure `VaultGitSync.commit()` sets `.setAuthor(VELA_IDENT).setCommitter(VELA_IDENT)`
- Room schema mismatch: ensure `ConversationEntity` has `@ColumnInfo(defaultValue = "default")` on `mode`
- Missing import: `org.json.JSONArray` is provided by `testImplementation("org.json:json:20231013")` in `build.gradle.kts` — already in the project

No commit needed if everything is clean. Fix-and-commit only if something needed repair.

---

## Summary

| Task | New / Modified Files | Proof |
|------|---------------------|-------|
| 1. JGit dep | `app/build.gradle.kts` | `assembleDebug` |
| 2. ToolParameter.required | `Tool.kt`, `AmplifierSession.kt` | `assembleDebug` |
| 3. VaultManager | `VaultManager.kt` | `VaultManagerTest` — 4 tests |
| 4. VaultSettings | `VaultSettings.kt` | `assembleDebug` |
| 5. Room vault layer | `VaultEntity.kt`, `VaultDao.kt`, `VaultRegistry.kt`, `ConversationEntity.kt`, `VelaDatabase.kt`, `AppModule.kt` (migrations) | `assembleDebug` |
| 6. VaultGitSync | `VaultGitSync.kt` | `VaultGitSyncTest` — 3 tests (local repo, no network) |
| 7. File tools | `VaultTools.kt` | `VaultToolsTest` — 7 tests |
| 8. Search tools | `SearchTools.kt` | `SearchToolsTest` — 5 tests |
| 9. BashTool | `BashTool.kt` | `BashToolTest` — 5 tests |
| 10. TodoTool | `TodoTool.kt` | `TodoToolTest` — 4 tests |
| 11. LoadSkillTool | `LoadSkillTool.kt` | `assembleDebug` |
| 12. AppModule wiring | `AppModule.kt` | `assembleDebug` + Logcat |
| 13. Smoke | — | All tests pass + clean build |

**Total test coverage added:** 28 unit tests across 5 test files.

**What Phase 2 builds on top of this:** Skills engine, hooks (SESSION\_START / AFTER\_WRITE\_FILE / SESSION\_END), session harness, Rust bridge `systemPrompt` param. Phase 2 does not require touching any files created in Phase 1 except `AppModule.kt` (to add new providers).
