# Phase 2: Skills, Hooks & Harness — Implementation Plan

> **For execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Add the hooks lifecycle system, Skills Engine (Agent Skills spec), Session Harness (composite system prompt), and thread a `systemPrompt` parameter through the Kotlin/Rust stack so vault-mode conversations receive the lifeos system prompt on their first turn.

**Builds on:** Phase 1 — `VaultManager`, `VaultRegistry`, `VaultGitSync`, `VaultSettings`, `VaultEntity`, all file tools, `ConversationEntity.mode`, `MIGRATION_6_7` all already in place.

**Architecture:** `HookRegistry` fires `SESSION_START` hooks in priority order → `VaultSyncHook` pulls vaults, `PersonalizationHook` appends `_personalization/` content, `VaultConfigHook` appends `<lifeos-config>` block → `SessionHarness` assembles SYSTEM.md + addenda into a composite system prompt → `InferenceEngine` calls harness on first turn of vault-mode conversations → `AmplifierSession.runTurn()` passes `systemPrompt` to Rust → Rust adds `"system"` field to the Anthropic API request body.

**Testability design:** Hook classes and `SkillsEngine` are constructed with plain values / lambdas — no Android `Context`, no Room DAO in constructors. Android dependencies are resolved in `AppModule` and passed as `String`, `File`, or lambda values. This allows pure JVM unit tests with `TemporaryFolder`, matching Phase 1's established pattern (see `VaultManager` taking `File`, `BashTool` taking a lambda for `activeVault`).

**Tech Stack:** Kotlin, Hilt, Coroutines, JGit (Phase 1), Rust/JNI (minimal one-param addition), Google Truth + JUnit4 (tests)

---

## Codebase orientation

- **Existing test pattern:** JUnit4 + Google Truth (`com.google.common.truth.Truth.assertThat`) + `TemporaryFolder` + `runBlocking { }` — see `VaultGitSyncTest`, `VaultManagerTest`
- **Test location:** `app/src/test/kotlin/com/vela/app/`
- **DI wiring:** `app/src/main/kotlin/com/vela/app/di/AppModule.kt`
- **Phase 1 new packages:** `com.vela.app.vault`, `com.vela.app.ai.tools` (VaultTools, SearchTools, BashTool, TodoTool, LoadSkillTool stub)
- **Rust source:** `app/src/main/rust/amplifier-android/src/` — `lib.rs`, `provider.rs`, `orchestrator.rs`, `context.rs`
- **Build commands:** `./gradlew :app:testDebugUnitTest` (tests), `./gradlew :app:assembleDebug` (build + Rust compile)

---

## Task 1: Hook.kt + HookRegistry.kt

**WHAT:** The hook lifecycle system exists. `Hook` interface, `HookEvent` enum, `HookContext`, and `HookResult` sealed class are defined. `HookRegistry.collectAddenda()` runs all matching hooks in priority order (ascending) and concatenates `SystemPromptAddendum` results.

**New files:**
- `app/src/main/kotlin/com/vela/app/hooks/Hook.kt`
- `app/src/main/kotlin/com/vela/app/hooks/HookRegistry.kt`
- `app/src/test/kotlin/com/vela/app/hooks/HookRegistryTest.kt`

**HOW — `Hook.kt`:**
```kotlin
package com.vela.app.hooks

import com.vela.app.data.db.VaultEntity

interface Hook {
    val event: HookEvent
    val priority: Int get() = 0
    suspend fun execute(ctx: HookContext): HookResult
}

enum class HookEvent { SESSION_START, AFTER_WRITE_FILE, SESSION_END, VAULT_TOGGLED }

data class HookContext(
    val conversationId: String,
    val activeVaults: List<VaultEntity>,
    val event: HookEvent,
    val metadata: Map<String, Any> = emptyMap(),
)

sealed class HookResult {
    object Continue : HookResult()
    data class SystemPromptAddendum(val text: String) : HookResult()
    data class Error(val message: String) : HookResult()
}
```

**HOW — `HookRegistry.kt`:**
```kotlin
package com.vela.app.hooks

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HookRegistry @Inject constructor(
    private val hooks: @JvmSuppressWildcards List<Hook>
) {
    suspend fun fire(event: HookEvent, ctx: HookContext): List<HookResult> =
        hooks.filter { it.event == event }
             .sortedBy { it.priority }
             .map { it.execute(ctx) }

    suspend fun collectAddenda(event: HookEvent, ctx: HookContext): String =
        fire(event, ctx)
            .filterIsInstance<HookResult.SystemPromptAddendum>()
            .joinToString("\n\n") { it.text }
}
```

**HOW — `HookRegistryTest.kt`:**
```kotlin
package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test

class HookRegistryTest {

    private val executionOrder = mutableListOf<Int>()

    private val priority0 = object : Hook {
        override val event    = HookEvent.SESSION_START
        override val priority = 0
        override suspend fun execute(ctx: HookContext): HookResult {
            executionOrder.add(0)
            return HookResult.SystemPromptAddendum("first")
        }
    }

    private val priority10 = object : Hook {
        override val event    = HookEvent.SESSION_START
        override val priority = 10
        override suspend fun execute(ctx: HookContext): HookResult {
            executionOrder.add(10)
            return HookResult.SystemPromptAddendum("second")
        }
    }

    private val wrongEvent = object : Hook {
        override val event = HookEvent.SESSION_END
        override suspend fun execute(ctx: HookContext) =
            HookResult.SystemPromptAddendum("should-not-appear")
    }

    // Registry created with high-priority first to confirm sort is applied
    private val registry = HookRegistry(listOf(priority10, priority0, wrongEvent))

    private val ctx = HookContext(
        conversationId = "test-conv",
        activeVaults   = emptyList(),
        event          = HookEvent.SESSION_START,
    )

    @Test fun `hooks execute in ascending priority order regardless of registration order`() = runBlocking {
        registry.fire(HookEvent.SESSION_START, ctx)
        assertThat(executionOrder).isEqualTo(listOf(0, 10))
    }

    @Test fun `only hooks matching the event fire`() = runBlocking {
        val results = registry.fire(HookEvent.SESSION_START, ctx)
        assertThat(results).hasSize(2)  // priority0 + priority10, NOT wrongEvent
    }

    @Test fun `collectAddenda joins addenda in priority order with double newline`() = runBlocking {
        val text = registry.collectAddenda(HookEvent.SESSION_START, ctx)
        assertThat(text).isEqualTo("first\n\nsecond")
    }

    @Test fun `Continue results are excluded from collectAddenda`() = runBlocking {
        val continueHook = object : Hook {
            override val event = HookEvent.SESSION_START
            override suspend fun execute(ctx: HookContext) = HookResult.Continue
        }
        val r = HookRegistry(listOf(continueHook))
        assertThat(r.collectAddenda(HookEvent.SESSION_START, ctx)).isEmpty()
    }
}
```

**PROOF:** `./gradlew :app:testDebugUnitTest --tests "com.vela.app.hooks.HookRegistryTest"` → 4 tests PASS

**Commit:** `feat(hooks): add Hook, HookEvent, HookContext, HookResult, HookRegistry`

---

## Task 2: VaultSyncHook.kt

**WHAT:** At `SESSION_START`, each active vault with sync configured is git-pulled. The pull operation is injected as a lambda (not `VaultGitSync` directly) following Phase 1's BashTool pattern — keeps the hook testable without mocking a concrete class.

**New files:**
- `app/src/main/kotlin/com/vela/app/hooks/VaultSyncHook.kt`
- `app/src/test/kotlin/com/vela/app/hooks/VaultSyncHookTest.kt`

**HOW — `VaultSyncHook.kt`:**
```kotlin
package com.vela.app.hooks

import com.vela.app.vault.VaultSettings
import java.io.File

/**
 * Pulls every configured active vault at SESSION_START.
 *
 * [pull] is a lambda injected by AppModule:
 *   `pull = { id, path -> gitSync.pull(id, path) }`
 * Injecting a lambda (not VaultGitSync directly) keeps the hook testable
 * without requiring a spy or open class — same pattern as Phase 1 BashTool.
 */
class VaultSyncHook(
    private val pull: suspend (vaultId: String, vaultPath: File) -> Unit,
    private val vaultSettings: VaultSettings,
) : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 0

    override suspend fun execute(ctx: HookContext): HookResult {
        ctx.activeVaults.forEach { vault ->
            if (vaultSettings.isConfiguredForSync(vault.id)) {
                pull(vault.id, File(vault.localPath))
            }
        }
        return HookResult.Continue
    }
}
```

**HOW — `VaultSyncHookTest.kt`:**
```kotlin
package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class VaultSyncHookTest {

    private val vaultA = VaultEntity(id = "a", name = "A", localPath = "/tmp/a")
    private val vaultB = VaultEntity(id = "b", name = "B", localPath = "/tmp/b")

    // Only vault "a" is configured for sync
    private val fakeSettings = object : VaultSettings {
        override fun isConfiguredForSync(vaultId: String) = vaultId == "a"
        override fun getRemoteUrl(vaultId: String) = ""
        override fun setRemoteUrl(vaultId: String, url: String) {}
        override fun getPat(vaultId: String) = ""
        override fun setPat(vaultId: String, pat: String) {}
        override fun getBranch(vaultId: String) = "main"
        override fun setBranch(vaultId: String, branch: String) {}
    }

    @Test fun `pull called only for vault configured for sync`() = runBlocking {
        val pulledIds = mutableListOf<String>()
        val hook = VaultSyncHook(
            pull          = { id, _ -> pulledIds.add(id) },
            vaultSettings = fakeSettings,
        )
        val ctx    = HookContext("conv", listOf(vaultA, vaultB), HookEvent.SESSION_START)
        val result = hook.execute(ctx)

        assertThat(pulledIds).containsExactly("a")
        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }

    @Test fun `no active vaults — pull never called`() = runBlocking {
        var pullCalled = false
        val hook = VaultSyncHook(
            pull          = { _, _ -> pullCalled = true },
            vaultSettings = fakeSettings,
        )
        hook.execute(HookContext("conv", emptyList(), HookEvent.SESSION_START))
        assertThat(pullCalled).isFalse()
    }
}
```

**PROOF:** `./gradlew :app:testDebugUnitTest --tests "com.vela.app.hooks.VaultSyncHookTest"` → 2 tests PASS

**Commit:** `feat(hooks): add VaultSyncHook`

---

## Task 3: PersonalizationHook.kt

**WHAT:** At `SESSION_START`, reads all `_personalization/*.md` files from each active vault (alphabetically sorted by filename) and returns a `SystemPromptAddendum`. Vault with no `_personalization/` directory returns `Continue`.

**New files:**
- `app/src/main/kotlin/com/vela/app/hooks/PersonalizationHook.kt`
- `app/src/test/kotlin/com/vela/app/hooks/PersonalizationHookTest.kt`

**HOW — `PersonalizationHook.kt`:**
```kotlin
package com.vela.app.hooks

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class PersonalizationHook : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 10

    override suspend fun execute(ctx: HookContext): HookResult = withContext(Dispatchers.IO) {
        val text = buildString {
            ctx.activeVaults.forEach { vault ->
                val dir = File(vault.localPath, "_personalization")
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles { f -> f.extension == "md" }
                        ?.sortedBy { it.name }
                        ?.forEach { file ->
                            appendLine("## ${file.nameWithoutExtension}")
                            appendLine(file.readText())
                            appendLine()
                        }
                }
            }
        }.trim()
        if (text.isBlank()) HookResult.Continue
        else HookResult.SystemPromptAddendum(text)
    }
}
```

**HOW — `PersonalizationHookTest.kt`:**
```kotlin
package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class PersonalizationHookTest {

    @get:Rule val tmp = TemporaryFolder()

    private val hook = PersonalizationHook()

    @Test fun `_personalization md files included in addendum`() = runBlocking {
        val vaultDir = tmp.newFolder("vault")
        File(vaultDir, "_personalization").mkdirs()
        File(vaultDir, "_personalization/profile.md").writeText("I am Ken")

        val vault  = VaultEntity(id = "v1", name = "Test", localPath = vaultDir.absolutePath)
        val result = hook.execute(HookContext("conv", listOf(vault), HookEvent.SESSION_START))

        assertThat(result).isInstanceOf(HookResult.SystemPromptAddendum::class.java)
        assertThat((result as HookResult.SystemPromptAddendum).text).contains("I am Ken")
    }

    @Test fun `vault without _personalization returns Continue`() = runBlocking {
        val vaultDir = tmp.newFolder("empty-vault")
        val vault    = VaultEntity(id = "v2", name = "Empty", localPath = vaultDir.absolutePath)
        val result   = hook.execute(HookContext("conv", listOf(vault), HookEvent.SESSION_START))

        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }

    @Test fun `multiple md files sorted alphabetically by filename`() = runBlocking {
        val vaultDir = tmp.newFolder("multi")
        File(vaultDir, "_personalization").mkdirs()
        File(vaultDir, "_personalization/b-prefs.md").writeText("Pref B")
        File(vaultDir, "_personalization/a-profile.md").writeText("Profile A")

        val vault  = VaultEntity(id = "v3", name = "Multi", localPath = vaultDir.absolutePath)
        val result = hook.execute(HookContext("conv", listOf(vault), HookEvent.SESSION_START))
                         as HookResult.SystemPromptAddendum

        // a-profile (sorted first) must appear before b-prefs
        assertThat(result.text.indexOf("Profile A")).isLessThan(result.text.indexOf("Pref B"))
    }
}
```

**PROOF:** `./gradlew :app:testDebugUnitTest --tests "com.vela.app.hooks.PersonalizationHookTest"` → 3 tests PASS

**Commit:** `feat(hooks): add PersonalizationHook`

---

## Task 4: VaultConfigHook.kt

**WHAT:** At `SESSION_START`, injects a `<lifeos-config>` XML block listing every active vault's name + local path so the AI knows the vault layout. Empty vault list → `Continue`.

**New files:**
- `app/src/main/kotlin/com/vela/app/hooks/VaultConfigHook.kt`
- `app/src/test/kotlin/com/vela/app/hooks/VaultConfigHookTest.kt`

**HOW — `VaultConfigHook.kt`:**
```kotlin
package com.vela.app.hooks

class VaultConfigHook : Hook {
    override val event    = HookEvent.SESSION_START
    override val priority = 20

    override suspend fun execute(ctx: HookContext): HookResult {
        if (ctx.activeVaults.isEmpty()) return HookResult.Continue
        val block = buildString {
            appendLine("<lifeos-config>")
            appendLine("vaults:")
            ctx.activeVaults.forEach { vault ->
                appendLine("  - name: ${vault.name}")
                appendLine("    type: personal")
                appendLine("    location: ${vault.localPath}")
            }
            append("</lifeos-config>")
        }
        return HookResult.SystemPromptAddendum(block)
    }
}
```

**HOW — `VaultConfigHookTest.kt`:**
```kotlin
package com.vela.app.hooks

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test

class VaultConfigHookTest {

    private val hook = VaultConfigHook()

    @Test fun `two vaults produce block containing both names and paths`() = runBlocking {
        val vaults = listOf(
            VaultEntity(id = "1", name = "Personal", localPath = "/data/vaults/personal"),
            VaultEntity(id = "2", name = "Work",     localPath = "/data/vaults/work"),
        )
        val result = hook.execute(HookContext("conv", vaults, HookEvent.SESSION_START))
                         as HookResult.SystemPromptAddendum

        assertThat(result.text).contains("<lifeos-config>")
        assertThat(result.text).contains("</lifeos-config>")
        assertThat(result.text).contains("name: Personal")
        assertThat(result.text).contains("location: /data/vaults/personal")
        assertThat(result.text).contains("name: Work")
        assertThat(result.text).contains("location: /data/vaults/work")
    }

    @Test fun `empty vault list returns Continue`() = runBlocking {
        val result = hook.execute(HookContext("conv", emptyList(), HookEvent.SESSION_START))
        assertThat(result).isInstanceOf(HookResult.Continue::class.java)
    }
}
```

**PROOF:** `./gradlew :app:testDebugUnitTest --tests "com.vela.app.hooks.VaultConfigHookTest"` → 2 tests PASS

**Commit:** `feat(hooks): add VaultConfigHook`

---

## Task 5: SkillMeta + SkillLoadResult + SkillsEngine

**WHAT:** `SkillsEngine` discovers `SKILL.md` files from vault-local `skills/` directories and a bundled `assets/skills/` directory. Parses YAML frontmatter per Agent Skills spec: `name` + `description` required, `name` must equal the parent directory name (spec rule — enforced). `SkillsEngine` takes lambdas/Files instead of Android `Context` or `VaultRegistry` directly — fully JVM-testable.

**New package:** `com.vela.app.skills`

**New files:**
- `app/src/main/kotlin/com/vela/app/skills/SkillMeta.kt`
- `app/src/main/kotlin/com/vela/app/skills/SkillLoadResult.kt`
- `app/src/main/kotlin/com/vela/app/skills/SkillsEngine.kt`
- `app/src/test/kotlin/com/vela/app/skills/SkillsEngineTest.kt`

**HOW — `SkillMeta.kt`:**
```kotlin
package com.vela.app.skills

data class SkillMeta(
    val name: String,
    val description: String,
    val isFork: Boolean = false,
    val isUserInvocable: Boolean = false,
    val directory: String,
)
```

**HOW — `SkillLoadResult.kt`:**
```kotlin
package com.vela.app.skills

sealed class SkillLoadResult {
    data class Content(val body: String, val skillDirectory: String) : SkillLoadResult()
    data class ForkResult(val response: String) : SkillLoadResult()
    data class NotFound(val name: String) : SkillLoadResult()
    data class Error(val message: String) : SkillLoadResult()
}
```

**HOW — `SkillsEngine.kt`:**
```kotlin
package com.vela.app.skills

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Discovers and loads skills from vault-local and bundled skill directories.
 *
 * [getVaultSkillDirs] is a suspend lambda injected by AppModule:
 *   `{ vaultRegistry.getEnabledVaults().map { File(it.localPath, "skills") }.filter { it.exists() } }`
 *
 * [bundledSkillsDir] is the directory where assets/skills/ was extracted to cacheDir.
 * Both are injected as plain values so no Android Context is needed here.
 */
class SkillsEngine(
    private val getVaultSkillDirs: suspend () -> List<File>,
    private val bundledSkillsDir: File,
) {
    suspend fun discoverAll(): List<SkillMeta> = withContext(Dispatchers.IO) {
        skillDirectories().flatMap { base ->
            base.listFiles { f -> f.isDirectory }
                ?.mapNotNull { parseSkillDir(it) }
                ?: emptyList()
        }
    }

    suspend fun search(query: String): List<SkillMeta> {
        val q = query.lowercase()
        return discoverAll().filter { q in it.name.lowercase() || q in it.description.lowercase() }
    }

    suspend fun info(name: String): SkillMeta? = discoverAll().firstOrNull { it.name == name }

    suspend fun load(name: String): SkillLoadResult = withContext(Dispatchers.IO) {
        val dir = skillDirectories()
            .flatMap { base ->
                base.listFiles { f -> f.isDirectory && f.name == name }?.toList() ?: emptyList()
            }
            .firstOrNull() ?: return@withContext SkillLoadResult.NotFound(name)

        val skillFile = File(dir, "SKILL.md")
        if (!skillFile.exists()) return@withContext SkillLoadResult.Error("SKILL.md missing in $name")

        val (_, body) = parseFrontmatter(skillFile.readText())
        SkillLoadResult.Content(body = body, skillDirectory = dir.absolutePath)
    }

    private suspend fun skillDirectories(): List<File> =
        getVaultSkillDirs() + listOf(bundledSkillsDir).filter { it.exists() }

    private fun parseSkillDir(dir: File): SkillMeta? {
        val skillFile = File(dir, "SKILL.md")
        if (!skillFile.exists()) return null
        val (fm, _) = parseFrontmatter(skillFile.readText())
        // Agent Skills spec: name field must equal the directory name
        val name        = fm["name"]?.takeIf { it == dir.name } ?: return null
        val description = fm["description"] ?: return null
        return SkillMeta(
            name            = name,
            description     = description,
            isFork          = fm["context"] == "fork",
            isUserInvocable = fm["user-invocable"] == "true",
            directory       = dir.absolutePath,
        )
    }

    private fun parseFrontmatter(content: String): Pair<Map<String, String>, String> {
        if (!content.startsWith("---")) return emptyMap<String, String>() to content
        val end = content.indexOf("\n---", 3).takeIf { it != -1 }
            ?: return emptyMap<String, String>() to content
        val fm = content.substring(3, end).trim().lines()
            .mapNotNull { line ->
                line.indexOf(':').takeIf { it != -1 }?.let { i ->
                    line.substring(0, i).trim() to line.substring(i + 1).trim().trim('"')
                }
            }.toMap()
        return fm to content.substring(end + 4).trimStart('\n')
    }
}
```

**HOW — `SkillsEngineTest.kt`:**
```kotlin
package com.vela.app.skills

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SkillsEngineTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var skillsDir: File
    private lateinit var engine: SkillsEngine

    @Before fun setUp() {
        skillsDir = tmp.newFolder("skills")
        engine = SkillsEngine(
            getVaultSkillDirs = { listOf(skillsDir) },
            bundledSkillsDir  = tmp.newFolder("bundled"),  // empty — no bundled skills
        )
    }

    /** Creates a skill directory with a valid SKILL.md under skillsDir. */
    private fun createSkill(name: String, extraFrontmatter: String = ""): File {
        val dir = File(skillsDir, name).also { it.mkdirs() }
        File(dir, "SKILL.md").writeText(
            """
            ---
            name: $name
            description: Test skill $name
            $extraFrontmatter
            ---

            This is the body of $name.
            """.trimIndent()
        )
        return dir
    }

    @Test fun `skill with valid SKILL_md is discovered`() = runBlocking {
        createSkill("my-skill")
        val skills = engine.discoverAll()
        assertThat(skills).hasSize(1)
        assertThat(skills[0].name).isEqualTo("my-skill")
        assertThat(skills[0].description).isEqualTo("Test skill my-skill")
    }

    @Test fun `load returns Content with body and skillDirectory`() = runBlocking {
        createSkill("python-standards")
        val result = engine.load("python-standards")
        assertThat(result).isInstanceOf(SkillLoadResult.Content::class.java)
        val content = result as SkillLoadResult.Content
        assertThat(content.body).contains("This is the body of python-standards")
        assertThat(content.skillDirectory).endsWith("python-standards")
    }

    @Test fun `skill with mismatched name field not discovered (spec enforcement)`() = runBlocking {
        // Directory is "my-skill" but SKILL.md says name: wrong-name
        val dir = File(skillsDir, "my-skill").also { it.mkdirs() }
        File(dir, "SKILL.md").writeText(
            """
            ---
            name: wrong-name
            description: Bad
            ---
            Body.
            """.trimIndent()
        )
        assertThat(engine.discoverAll()).isEmpty()
    }

    @Test fun `load returns NotFound for unknown skill`() = runBlocking {
        val result = engine.load("does-not-exist")
        assertThat(result).isInstanceOf(SkillLoadResult.NotFound::class.java)
    }

    @Test fun `search filters by name and description`() = runBlocking {
        createSkill("python-standards")
        createSkill("go-patterns")
        val results = engine.search("python")
        assertThat(results).hasSize(1)
        assertThat(results[0].name).isEqualTo("python-standards")
    }

    @Test fun `fork context flag parsed correctly`() = runBlocking {
        createSkill("fork-skill", "context: fork")
        val meta = engine.info("fork-skill")
        assertThat(meta).isNotNull()
        assertThat(meta!!.isFork).isTrue()
    }
}
```

**PROOF:** `./gradlew :app:testDebugUnitTest --tests "com.vela.app.skills.SkillsEngineTest"` → 6 tests PASS

**Commit:** `feat(skills): add SkillMeta, SkillLoadResult, SkillsEngine`

---

## Task 6: LoadSkillTool — wire Phase 1 stub to SkillsEngine

**WHAT:** `load_skill` does real skill discovery. `list=true` returns actual skills. `skill_name="x"` returns body + `skill_directory`. Replaces the Phase 1 no-op stub.

**Modify:** `app/src/main/kotlin/com/vela/app/ai/tools/LoadSkillTool.kt`

Read the Phase 1 stub first. Then replace the entire file content:

**HOW — complete `LoadSkillTool.kt`:**
```kotlin
package com.vela.app.ai.tools

import com.vela.app.skills.SkillLoadResult
import com.vela.app.skills.SkillsEngine

class LoadSkillTool(private val engine: SkillsEngine) : Tool {
    override val name        = "load_skill"
    override val displayName = "Load Skill"
    override val icon        = "🧠"
    override val description =
        "Load domain knowledge from a skill. Operations: list (all available skills), " +
        "search (filter by keyword), info (metadata only), skill_name (load full content)."
    override val parameters = listOf(
        ToolParameter("list",       "boolean", "If true, return list of all available skills",                required = false),
        ToolParameter("search",     "string",  "Search term to filter skills by name or description",        required = false),
        ToolParameter("info",       "string",  "Get metadata for a skill without loading body",              required = false),
        ToolParameter("skill_name", "string",  "Name of skill to load full content",                        required = false),
    )

    override suspend fun execute(args: Map<String, Any>): String {
        val listAll   = args["list"]       as? Boolean ?: false
        val search    = args["search"]     as? String
        val info      = args["info"]       as? String
        val skillName = args["skill_name"] as? String

        return when {
            listAll -> {
                val skills = engine.discoverAll()
                if (skills.isEmpty()) "(no skills available)"
                else skills.joinToString("\n") { "- **${it.name}**: ${it.description}" }
            }
            search != null -> {
                val skills = engine.search(search)
                if (skills.isEmpty()) "No skills matching: $search"
                else skills.joinToString("\n") { "- **${it.name}**: ${it.description}" }
            }
            info != null -> {
                engine.info(info)?.let {
                    "**${it.name}** — ${it.description}\n" +
                    "fork=${it.isFork}  user-invocable=${it.isUserInvocable}\n" +
                    "directory: ${it.directory}"
                } ?: "Skill '$info' not found"
            }
            skillName != null -> when (val r = engine.load(skillName)) {
                is SkillLoadResult.Content    -> "${r.body}\n\nskill_directory: ${r.skillDirectory}"
                is SkillLoadResult.NotFound   -> "Skill '$skillName' not found"
                is SkillLoadResult.ForkResult -> r.response
                is SkillLoadResult.Error      -> "Error: ${r.message}"
            }
            else -> "Error: specify list=true, search=\"<query>\", info=\"<name>\", or skill_name=\"<name>\""
        }
    }
}
```

**PROOF:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (`LoadSkillTool` compiles against `SkillsEngine` API — no unresolved references)

**Commit:** `feat(skills): wire LoadSkillTool to real SkillsEngine`

---

## Task 7: SessionHarness.kt

**WHAT:** `SessionHarness.buildSystemPrompt(conversationId, activeVaults)` assembles the composite system prompt: vault `SYSTEM.md` (or injected fallback string) + hook addenda. Tracks initialized conversation IDs so the harness only runs once per conversation.

`SessionHarness` takes `fallbackPrompt: String` — the SYSTEM.md asset content loaded by AppModule at startup. **No Android `Context` in the constructor.** Trivially testable.

**New files:**
- `app/src/main/kotlin/com/vela/app/harness/SessionHarness.kt`
- `app/src/test/kotlin/com/vela/app/harness/SessionHarnessTest.kt`

**HOW — `SessionHarness.kt`:**
```kotlin
package com.vela.app.harness

import com.vela.app.data.db.VaultEntity
import com.vela.app.hooks.HookContext
import com.vela.app.hooks.HookEvent
import com.vela.app.hooks.HookRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Assembles the composite system prompt for vault-mode conversations.
 *
 * [fallbackPrompt] is the content of assets/lifeos/SYSTEM.md, loaded by
 * AppModule at construction time — no Android Context needed here.
 */
class SessionHarness(
    private val hookRegistry: HookRegistry,
    private val fallbackPrompt: String = DEFAULT_FALLBACK,
) {
    private val initialized = ConcurrentHashMap.newKeySet<String>()

    fun isInitialized(conversationId: String): Boolean = conversationId in initialized

    suspend fun buildSystemPrompt(
        conversationId: String,
        activeVaults: List<VaultEntity>,
    ): String = withContext(Dispatchers.IO) {
        initialized.add(conversationId)
        val hookCtx = HookContext(conversationId, activeVaults, HookEvent.SESSION_START)
        val addenda = hookRegistry.collectAddenda(HookEvent.SESSION_START, hookCtx)
        buildString {
            append(loadSystemMd(activeVaults))
            if (addenda.isNotBlank()) {
                append("\n\n")
                append(addenda)
            }
        }
    }

    private fun loadSystemMd(activeVaults: List<VaultEntity>): String {
        activeVaults.forEach { vault ->
            val f = File(vault.localPath, "SYSTEM.md")
            if (f.exists()) return f.readText()
        }
        return fallbackPrompt
    }

    companion object {
        const val DEFAULT_FALLBACK =
            "# Vault session\n\nYou are a personal AI assistant with access to the user's vault."
    }
}
```

**HOW — `SessionHarnessTest.kt`:**
```kotlin
package com.vela.app.harness

import com.google.common.truth.Truth.assertThat
import com.vela.app.data.db.VaultEntity
import com.vela.app.hooks.Hook
import com.vela.app.hooks.HookContext
import com.vela.app.hooks.HookEvent
import com.vela.app.hooks.HookRegistry
import com.vela.app.hooks.HookResult
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionHarnessTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun makeHarness(addendum: String = "", fallback: String = "FALLBACK"): SessionHarness {
        val hook = object : Hook {
            override val event = HookEvent.SESSION_START
            override suspend fun execute(ctx: HookContext): HookResult =
                if (addendum.isBlank()) HookResult.Continue
                else HookResult.SystemPromptAddendum(addendum)
        }
        return SessionHarness(HookRegistry(listOf(hook)), fallback)
    }

    @Test fun `uses vault SYSTEM_md when present`() = runBlocking {
        val vaultDir = tmp.newFolder("vault")
        File(vaultDir, "SYSTEM.md").writeText("VAULT SYSTEM PROMPT")
        val vault  = VaultEntity(id = "v", name = "V", localPath = vaultDir.absolutePath)
        val harness = makeHarness(fallback = "FALLBACK")

        val prompt = harness.buildSystemPrompt("conv-1", listOf(vault))

        assertThat(prompt).contains("VAULT SYSTEM PROMPT")
        assertThat(prompt).doesNotContain("FALLBACK")
    }

    @Test fun `falls back to constructor string when no vault SYSTEM_md`() = runBlocking {
        val vaultDir = tmp.newFolder("empty")
        val vault    = VaultEntity(id = "v", name = "V", localPath = vaultDir.absolutePath)
        val harness  = makeHarness(fallback = "INJECTED FALLBACK")

        val prompt = harness.buildSystemPrompt("conv-2", listOf(vault))

        assertThat(prompt).contains("INJECTED FALLBACK")
    }

    @Test fun `hook addenda appended after system prompt`() = runBlocking {
        val vaultDir = tmp.newFolder("vault2")
        File(vaultDir, "SYSTEM.md").writeText("BASE PROMPT")
        val vault   = VaultEntity(id = "v", name = "V", localPath = vaultDir.absolutePath)
        val harness = makeHarness(addendum = "PERSONALIZATION DATA")

        val prompt = harness.buildSystemPrompt("conv-3", listOf(vault))

        assertThat(prompt).contains("BASE PROMPT")
        assertThat(prompt).contains("PERSONALIZATION DATA")
        assertThat(prompt.indexOf("BASE PROMPT")).isLessThan(prompt.indexOf("PERSONALIZATION DATA"))
    }

    @Test fun `isInitialized is false before first build, true after`() = runBlocking {
        val harness = makeHarness()
        assertThat(harness.isInitialized("conv-4")).isFalse()
        harness.buildSystemPrompt("conv-4", emptyList())
        assertThat(harness.isInitialized("conv-4")).isTrue()
    }

    @Test fun `different conversation IDs tracked independently`() = runBlocking {
        val harness = makeHarness()
        harness.buildSystemPrompt("conv-a", emptyList())
        assertThat(harness.isInitialized("conv-a")).isTrue()
        assertThat(harness.isInitialized("conv-b")).isFalse()
    }
}
```

**PROOF:** `./gradlew :app:testDebugUnitTest --tests "com.vela.app.harness.SessionHarnessTest"` → 5 tests PASS

**Commit:** `feat(harness): add SessionHarness`

---

## Task 8: Bundle lifeos SYSTEM.md as app asset

**WHAT:** The app ships a lifeos SYSTEM.md as a bundled fallback that `SessionHarness` uses when no vault provides its own `SYSTEM.md`.

**HOW:**

Create the assets directory:
```bash
mkdir -p app/src/main/assets/lifeos
```

If you have the lifeos-core SYSTEM.md locally:
```bash
cp ~/workspace/ms/lifeos-core/SYSTEM.md app/src/main/assets/lifeos/SYSTEM.md
```

If not, create a minimal placeholder (replace with the real file when available):
```bash
cat > app/src/main/assets/lifeos/SYSTEM.md << 'EOF'
# lifeos

You are a personal AI assistant helping manage the user's life operating system. You have access to their vaults — personal knowledge stores containing notes, tasks, projects, and more.

Use your file tools (`read_file`, `write_file`, `edit_file`, `glob`, `grep`) to read and update vault contents. Use `load_skill` to load domain knowledge. Use `bash` for git operations (`git status`, `git commit`, `git push`, `git pull`).

Always work locally in the vault unless the user explicitly asks to push. Respect the vault structure — don't create files outside of known vault paths.
EOF
```

**PROOF:**
```bash
./gradlew :app:assembleDebug
ls app/src/main/assets/lifeos/SYSTEM.md
```
Both succeed. File present, build passes.

**Commit:** `feat(assets): add lifeos SYSTEM.md as bundled fallback`

---

## Task 9: Add `systemPrompt` to InferenceSession, AmplifierSession, AmplifierBridge

**WHAT:** `runTurn()` accepts `systemPrompt: String = ""` at every layer. Default is `""` so all existing callers compile unchanged. The new parameter is placed between `userInput` and `onToolStart`.

**Modify:**
- `app/src/main/kotlin/com/vela/app/engine/InferenceSession.kt`
- `app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt`
- `app/src/main/kotlin/com/vela/app/ai/AmplifierBridge.kt`

---

**HOW — `InferenceSession.kt`** — replace the `runTurn` signature:

Find:
```kotlin
    suspend fun runTurn(
        historyJson: String,
        userInput:   String,
        onToolStart: (suspend (name: String, argsJson: String) -> String),
        onToolEnd:   (suspend (stableId: String, result: String) -> Unit),
        onToken:     (suspend (token: String) -> Unit),
    )
```

Replace with:
```kotlin
    suspend fun runTurn(
        historyJson:  String,
        userInput:    String,
        systemPrompt: String = "",
        onToolStart:  (suspend (name: String, argsJson: String) -> String),
        onToolEnd:    (suspend (stableId: String, result: String) -> Unit),
        onToken:      (suspend (token: String) -> Unit),
    )
```

---

**HOW — `AmplifierSession.kt`** — update the `override suspend fun runTurn` signature and the `AmplifierBridge.nativeRun` call inside it.

Replace the `override suspend fun runTurn(` signature block:
```kotlin
    override suspend fun runTurn(
        historyJson: String,
        userInput: String,
        onToolStart: (suspend (name: String, argsJson: String) -> String),
        onToolEnd:   (suspend (stableId: String, result: String) -> Unit),
        onToken:     (suspend (token: String) -> Unit),
    ) {
```
With:
```kotlin
    override suspend fun runTurn(
        historyJson:  String,
        userInput:    String,
        systemPrompt: String,
        onToolStart:  (suspend (name: String, argsJson: String) -> String),
        onToolEnd:    (suspend (stableId: String, result: String) -> Unit),
        onToken:      (suspend (token: String) -> Unit),
    ) {
```

Inside that method, update the `AmplifierBridge.nativeRun(` call. Find:
```kotlin
        val finalText = AmplifierBridge.nativeRun(
            apiKey      = getApiKey(),
            model       = getModel(),
            toolsJson   = toolsJson,
            historyJson = historyJson,
            userInput   = userInput,
            tokenCb     = { token ->
```
Replace with:
```kotlin
        val finalText = AmplifierBridge.nativeRun(
            apiKey       = getApiKey(),
            model        = getModel(),
            toolsJson    = toolsJson,
            historyJson  = historyJson,
            userInput    = userInput,
            systemPrompt = systemPrompt,
            tokenCb      = { token ->
```

Also update the `Log.d` line inside `runTurn` to include the new field (optional but helpful for debugging):
```kotlin
        Log.d(TAG, "runTurn model=${getModel()} historyLen=${historyJson.length} hasSystemPrompt=${systemPrompt.isNotBlank()}")
```

---

**HOW — `AmplifierBridge.kt`** — add `systemPrompt` to `external fun nativeRun`.

Find:
```kotlin
        external fun nativeRun(
            apiKey: String,
            model: String,
            toolsJson: String,
            historyJson: String,
            userInput: String,
            tokenCb: TokenCallback,
            toolCb: ToolCallback,
        ): String
```

Replace with:
```kotlin
        external fun nativeRun(
            apiKey:       String,
            model:        String,
            toolsJson:    String,
            historyJson:  String,
            userInput:    String,
            systemPrompt: String,
            tokenCb:      TokenCallback,
            toolCb:       ToolCallback,
        ): String
```

---

**PROOF:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

Note: The Kotlin JNI declaration change (adding `systemPrompt`) and the Rust change (Task 10) must both be done before running the app. The **build** will succeed after Task 9 alone (Kotlin compiles independently of Rust function signatures — JNI mismatches are runtime errors, not link errors). Task 10 completes the pair.

**Commit:** `feat(session): add systemPrompt param to InferenceSession, AmplifierSession, AmplifierBridge`

---

## Task 10: Rust bridge — pass systemPrompt to Anthropic API "system" field

**WHAT:** Rust `nativeRun` accepts `system_prompt` as a new JNI parameter. When non-empty, the Anthropic API request body includes `"system": "<prompt>"`. When empty, the field is omitted (default conversations unaffected).

**Modify:**
- `app/src/main/rust/amplifier-android/src/lib.rs`
- `app/src/main/rust/amplifier-android/src/provider.rs`

---

**HOW — `lib.rs`:**

**Step 1.** Find the JNI function signature:
```rust
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
    token_cb: JObject,
    tool_cb: JObject,
) -> jstring {
```

Replace with (add `system_prompt: JString` between `user_input` and `token_cb`):
```rust
pub extern "C" fn Java_com_vela_app_ai_AmplifierBridge_nativeRun(
    mut env: JNIEnv,
    _class: JClass,
    api_key: JString,
    model: JString,
    tools_json: JString,
    history_json: JString,
    user_input: JString,
    system_prompt: JString,
    token_cb: JObject,
    tool_cb: JObject,
) -> jstring {
```

**Step 2.** In the string extraction section (after `let user_input = jni_string(...)`), add:
```rust
    let system_prompt = jni_string(&mut env, &system_prompt, "system_prompt");
```

**Step 3.** Update the `info!` log to include the new field. Find:
```rust
    info!(
        "nativeRun: model={model} user_input_len={} history_json_len={} tools_json_len={}",
        user_input.len(),
        history_json.len(),
        tools_json.len(),
    );
```
Replace with:
```rust
    info!(
        "nativeRun: model={model} user_input_len={} history_json_len={} tools_json_len={} has_system_prompt={}",
        user_input.len(),
        history_json.len(),
        tools_json.len(),
        !system_prompt.is_empty(),
    );
```

**Step 4.** Update the `AnthropicProvider::new(...)` call. Find:
```rust
        let provider = AnthropicProvider::new(api_key, model, tools);
```
Replace with:
```rust
        let provider = AnthropicProvider::new(api_key, model, tools, system_prompt);
```

---

**HOW — `provider.rs`:**

**Step 1.** Add `system_prompt: String` field to the `AnthropicProvider` struct:
```rust
    pub struct AnthropicProvider {
        api_key: String,
        model: String,
        /// Tools in **Anthropic** format (converted from OpenAI on construction).
        tools: Vec<Value>,
        system_prompt: String,
        client: Client,
    }
```

**Step 2.** Update `new()` to accept and store `system_prompt`:
```rust
        pub fn new(api_key: String, model: String, openai_tools: Vec<Value>, system_prompt: String) -> Self {
            let tools = Self::convert_tools(openai_tools);
            Self {
                api_key,
                model,
                tools,
                system_prompt,
                client: Client::new(),
            }
        }
```

**Step 3.** In `complete()`, conditionally add the `"system"` field to the request body. Find:
```rust
            let body = json!({
                "model": self.model,
                "max_tokens": 4096,
                "messages": messages,
                "tools": self.tools,
                "stream": false,
            });
```
Replace with:
```rust
            let mut body = json!({
                "model": self.model,
                "max_tokens": 4096,
                "messages": messages,
                "tools": self.tools,
                "stream": false,
            });
            if !self.system_prompt.is_empty() {
                body["system"] = Value::String(self.system_prompt.clone());
            }
```

**Step 4.** Update the three existing Rust unit tests in `provider.rs` — they call `AnthropicProvider::new` with 3 args. Add `"".into()` as the fourth argument to each:

Find every occurrence of `AnthropicProvider::new("key".into(), "model".into(), ...)` in the `#[cfg(test)]` block and add `"".into()` as the last argument before the closing `)`:
```rust
// Before:
AnthropicProvider::new("key".into(), "model".into(), openai)
// After:
AnthropicProvider::new("key".into(), "model".into(), openai, "".into())
```

There are 3 test functions — update each one.

---

**PROOF:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (Rust compiles; JNI parameter order matches Kotlin declaration from Task 9)

**Commit:** `feat(rust): add system_prompt param to nativeRun and AnthropicProvider`

---

## Task 11: InferenceEngine — call SessionHarness on first vault turn

**WHAT:** On the **first** turn of a `mode = "vault"` conversation, `InferenceEngine` calls `SessionHarness.buildSystemPrompt()` and passes the result as `systemPrompt` to `session.runTurn()`. Subsequent turns of the same conversation pass `""` (harness tracks initialization). All non-vault conversations always pass `""`.

**Modify:** `app/src/main/kotlin/com/vela/app/engine/InferenceEngine.kt`

**HOW:**

**Step 1.** Add three new constructor parameters. Read the current constructor first, then replace it:

Find:
```kotlin
@Singleton
class InferenceEngine @Inject constructor(
    private val session: InferenceSession,
    private val toolRegistry: com.vela.app.ai.tools.ToolRegistry,
    private val turnDao: TurnDao,
    private val turnEventDao: TurnEventDao,
) {
```

Replace with:
```kotlin
@Singleton
class InferenceEngine @Inject constructor(
    private val session: InferenceSession,
    private val toolRegistry: com.vela.app.ai.tools.ToolRegistry,
    private val turnDao: TurnDao,
    private val turnEventDao: TurnEventDao,
    private val conversationDao: com.vela.app.data.db.ConversationDao,
    private val vaultRegistry: com.vela.app.vault.VaultRegistry,
    private val harness: com.vela.app.harness.SessionHarness,
) {
```

**Step 2.** In `processTurn()`, compute the system prompt before calling `session.runTurn()`. Find:
```kotlin
        val historyJson = buildHistory(conversationId)

        session.runTurn(
            historyJson = historyJson,
            userInput   = userMessage,
```

Replace with:
```kotlin
        val historyJson = buildHistory(conversationId)

        val conversation  = conversationDao.getById(conversationId)
        val systemPrompt  = if (conversation?.mode == "vault" && !harness.isInitialized(conversationId)) {
            harness.buildSystemPrompt(conversationId, vaultRegistry.getEnabledVaults())
        } else {
            ""
        }

        session.runTurn(
            historyJson  = historyJson,
            userInput    = userMessage,
            systemPrompt = systemPrompt,
```

Leave all three lambda bodies (`onToken`, `onToolStart`, `onToolEnd`) completely unchanged.

**PROOF:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL

**Commit:** `feat(engine): thread systemPrompt through InferenceEngine via SessionHarness`

---

## Task 12: Wire Phase 2 into AppModule

**WHAT:** All Phase 2 components are Hilt-provided. The app builds with a complete, wired dependency graph.

**Modify:** `app/src/main/kotlin/com/vela/app/di/AppModule.kt`

**HOW:**

**Step 1.** Add imports at the top of `AppModule.kt`:
```kotlin
import com.vela.app.hooks.Hook
import com.vela.app.hooks.HookRegistry
import com.vela.app.hooks.VaultSyncHook
import com.vela.app.hooks.PersonalizationHook
import com.vela.app.hooks.VaultConfigHook
import com.vela.app.skills.SkillsEngine
import com.vela.app.harness.SessionHarness
```

(Phase 1 already imports `VaultGitSync`, `VaultSettings`, `VaultRegistry`, `ConversationDao` — confirm they are present.)

**Step 2.** Add four new `@Provides` methods inside `object AppModule`:

```kotlin
    @Provides @Singleton
    fun provideHooks(
        gitSync:       VaultGitSync,
        vaultSettings: VaultSettings,
    ): @JvmSuppressWildcards List<Hook> = listOf(
        VaultSyncHook(
            pull          = { id, path -> gitSync.pull(id, path) },
            vaultSettings = vaultSettings,
        ),
        PersonalizationHook(),
        VaultConfigHook(),
    )

    @Provides @Singleton
    fun provideHookRegistry(hooks: @JvmSuppressWildcards List<Hook>): HookRegistry =
        HookRegistry(hooks)

    @Provides @Singleton
    fun provideSkillsEngine(
        vaultRegistry:     VaultRegistry,
        @ApplicationContext ctx: Context,
    ): SkillsEngine {
        val bundledDir = extractBundledSkills(ctx)
        return SkillsEngine(
            getVaultSkillDirs = {
                vaultRegistry.getEnabledVaults()
                    .map { java.io.File(it.localPath, "skills") }
                    .filter { it.exists() }
            },
            bundledSkillsDir = bundledDir,
        )
    }

    @Provides @Singleton
    fun provideSessionHarness(
        hookRegistry:       HookRegistry,
        @ApplicationContext ctx: Context,
    ): SessionHarness {
        val fallback = try {
            ctx.assets.open("lifeos/SYSTEM.md").bufferedReader().readText()
        } catch (_: Exception) {
            SessionHarness.DEFAULT_FALLBACK
        }
        return SessionHarness(hookRegistry, fallback)
    }
```

**Step 3.** Add a private helper method to `AppModule` object (at the bottom, before the closing `}`):

```kotlin
    private fun extractBundledSkills(context: Context): java.io.File {
        val cache = java.io.File(context.cacheDir, "bundled_skills")
        cache.mkdirs()
        runCatching {
            context.assets.list("skills")?.forEach { skillName ->
                val dest = java.io.File(cache, skillName).also { it.mkdirs() }
                context.assets.list("skills/$skillName")?.forEach { fileName ->
                    context.assets.open("skills/$skillName/$fileName").use { input ->
                        java.io.File(dest, fileName).outputStream().use(input::copyTo)
                    }
                }
            }
        }
        return cache
    }
```

**Step 4.** Update `provideTools` — add `skillsEngine: SkillsEngine` parameter and change `LoadSkillTool()` to `LoadSkillTool(skillsEngine)`.

Read the current `provideTools` function. Find its parameter list and add `skillsEngine: SkillsEngine`. Find `LoadSkillTool()` in the tool list and change it to `LoadSkillTool(skillsEngine)`.

**Step 5.** Update `provideInferenceEngine` — add the three new parameters. Read the current function first, then replace:

```kotlin
    @Provides @Singleton
    fun provideInferenceEngine(
        session:         InferenceSession,
        toolRegistry:    ToolRegistry,
        turnDao:         TurnDao,
        turnEventDao:    TurnEventDao,
        conversationDao: ConversationDao,
        vaultRegistry:   VaultRegistry,
        harness:         SessionHarness,
    ): InferenceEngine = InferenceEngine(
        session, toolRegistry, turnDao, turnEventDao, conversationDao, vaultRegistry, harness
    )
```

**PROOF:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL — no unresolved references, Hilt graph complete

**Commit:** `feat(di): wire Phase 2 hooks, skills engine, session harness into AppModule`

---

## Task 13: Full build + test smoke

**WHAT:** All Phase 1 + Phase 2 unit tests pass. App builds cleanly.

**HOW:**

```bash
# Run all JVM unit tests
./gradlew :app:testDebugUnitTest

# Build the debug APK (also triggers Rust cargo build)
./gradlew :app:assembleDebug
```

Expected test output includes tests from all these packages:
- `com.vela.app.vault.*` — Phase 1 (VaultManagerTest, VaultGitSyncTest, etc.)
- `com.vela.app.ai.tools.*` — Phase 1 (VaultToolsTest, BashToolTest, TodoToolTest, etc.)
- `com.vela.app.engine.*` — existing InferenceFlowTest
- `com.vela.app.hooks.*` — Phase 2 Tasks 1–4 (4 test classes, 11 tests)
- `com.vela.app.skills.*` — Phase 2 Task 5 (6 tests)
- `com.vela.app.harness.*` — Phase 2 Task 7 (5 tests)

**PROOF:**
```
BUILD SUCCESSFUL
Tests run: N, Failures: 0, Errors: 0, Skipped: 0
```

Fix any failures before marking Phase 2 complete. Common issues to check:
- `ConversationDao` import in `InferenceEngine` — ensure fully qualified or imported
- `VaultRegistry` import in `InferenceEngine` — ensure imported from `com.vela.app.vault`
- Hilt `@JvmSuppressWildcards` on `List<Hook>` — required at both the provider return type and the `HookRegistry` constructor parameter
- `provideTools` still passes `LoadSkillTool(skillsEngine)` — confirm not accidentally reverted

**Commit (only if something needed fixing):** `fix: Phase 2 smoke — <describe fix>`

---

## Summary

| Task | Files | Proof |
|------|-------|-------|
| 1. Hook + HookRegistry | `Hook.kt`, `HookRegistry.kt` | `HookRegistryTest` — 4 tests |
| 2. VaultSyncHook | `VaultSyncHook.kt` | `VaultSyncHookTest` — 2 tests |
| 3. PersonalizationHook | `PersonalizationHook.kt` | `PersonalizationHookTest` — 3 tests |
| 4. VaultConfigHook | `VaultConfigHook.kt` | `VaultConfigHookTest` — 2 tests |
| 5. SkillsEngine | `SkillMeta.kt`, `SkillLoadResult.kt`, `SkillsEngine.kt` | `SkillsEngineTest` — 6 tests |
| 6. LoadSkillTool wired | `LoadSkillTool.kt` (replace stub) | `assembleDebug` |
| 7. SessionHarness | `SessionHarness.kt` | `SessionHarnessTest` — 5 tests |
| 8. lifeos SYSTEM.md asset | `assets/lifeos/SYSTEM.md` | `assembleDebug` + `ls` |
| 9. systemPrompt Kotlin | `InferenceSession.kt`, `AmplifierSession.kt`, `AmplifierBridge.kt` | `assembleDebug` |
| 10. systemPrompt Rust | `lib.rs`, `provider.rs` | `assembleDebug` (Rust + Kotlin) |
| 11. InferenceEngine harness | `InferenceEngine.kt` | `assembleDebug` |
| 12. AppModule wiring | `AppModule.kt` | `assembleDebug` |
| 13. Full smoke | — | All tests pass + clean build |

**Total new tests:** 22 unit tests across 6 test files
