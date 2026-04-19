# Profile Screen — Implementation Plan
Design: docs/plans/2026-04-19-vela-profile-screen-design.md
Tasks: 10 (single plan)

---

## Codebase context (confirmed by explorer survey)

- **Platform:** Kotlin Android, Jetpack Compose, Material 3, Hilt, Room
- **Source root:** `app/src/main/kotlin/com/vela/app/`
- **WorkManager:** NOT present — needs to be added
- **HiltWorker pattern:** `@HiltWorker` + `@AssistedInject constructor(@Assisted context, @Assisted params, ...)` — NOT regular `@Inject`
- **SnakeYAML:** NOT present — parse YAML frontmatter manually with string splitting
- **Application class:** Has `@HiltAndroidApp` — find it at `app/src/main/kotlin/com/vela/app/`
- **ViewModel pattern:** `@HiltViewModel class Foo @Inject constructor(...) : ViewModel()` with `viewModelScope.launch(Dispatchers.IO)`
- **AppModule:** `@Module @InstallIn(SingletonComponent::class) object AppModule` at `di/AppModule.kt`
- **NavigationScaffold PROFILE branch:** lines 160-168, currently calls `SettingsScreen(onNavigateBack={}, onNavigateToAi={}, ...all stubbed...)`
- **TurnDao:** No cross-conversation "recent N" query — needs new method
- **AmplifierSession:** `@Singleton @Inject constructor` — injectable into workers via Hilt
- **`runTurn` signature:** `suspend fun runTurn(historyJson, userInput, userContentJson, systemPrompt, onToolStart, onToolEnd, onToken, onProviderRequest, onServerTool)`
- **TurnEntity fields:** `id`, `conversationId`, `userMessage`, `status`, `timestamp: Long`, `error`, `userContentJson`
- **VaultRegistry:** `enabledVaults: StateFlow<List<VaultEntity>>`; VaultEntity has `id`, `name`, `localPath`, `isEnabled`, `createdAt`
- **VaultManager:** `resolve(path: String): File?` — safe path resolution, returns null for out-of-bounds

---

## Task ordering and dependencies

```
Task 1 (build.gradle deps) → Task 2 (Application HiltWorkerFactory) → Task 4 (ProfileWorker)
Task 3 (TurnDao) ──────────────────────────────────────────────────→ Task 4
Task 4 (ProfileWorker) → Task 5 (ProfileWorkerScheduler) → Task 6 (AppModule)
                                         ↓
Task 6 (AppModule) → Task 7 (ProfileViewModel) → Task 8 (ProfileScreen) → Task 9 (NavigationScaffold)
Task 10 (SettingsScreen cleanup) — independent, can run anytime after Task 1
```

---

## Task 1: WorkManager + Hilt-Work Dependencies

**Context:** Read `app/build.gradle.kts` lines 95-167 (dependencies block) and `gradle/libs.versions.toml` before editing.

**What to build:**

In `app/build.gradle.kts`, inside the `dependencies {}` block, add these three lines after the Hilt dependencies:

```kotlin
// WorkManager + Hilt integration for ProfileWorker
implementation("androidx.work:work-runtime-ktx:2.9.1")
implementation("androidx.hilt:hilt-work:1.2.0")
ksp("androidx.hilt:hilt-compiler:1.2.0")
```

Note: `androidx.hilt:hilt-compiler` is separate from `hilt-android-compiler` — both are needed.

**Theory of Success:** `./gradlew :app:compileDebugKotlin` succeeds. `CoroutineWorker`, `@HiltWorker`, `@AssistedInject`, and `WorkManager` can be imported.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "BUILD|error:" | head -10
```

**NFR scan:**
- **Dependency version pinning:** Use exact versions (2.9.1 and 1.2.0) not `+` wildcards — avoids unexpected breakage on dependency resolution
- **Separate hilt-compiler:** The `androidx.hilt:hilt-compiler` KSP processor is distinct from `hilt-android-compiler` — both must be present for Hilt to generate worker factories

---

## Task 2: HiltWorkerFactory Wiring in Application Class

**Context:** Find the `@HiltAndroidApp` Application class — search for `@HiltAndroidApp` in `app/src/main/kotlin/com/vela/app/`. Read the full file.

**What to build:**

Find the class annotated `@HiltAndroidApp` (likely `VelaApplication.kt` or `App.kt`). Inject `HiltWorkerFactory` and configure WorkManager to use it:

```kotlin
@HiltAndroidApp
class VelaApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

If the Application class already extends `Application()`, just add `Configuration.Provider`, the `@Inject lateinit var workerFactory`, and the `workManagerConfiguration` property.

Also verify `AndroidManifest.xml` has `android:name=".VelaApplication"` (or whatever the class is named) in the `<application>` tag — add it if missing.

**Theory of Success:** `./gradlew :app:assembleDebug` succeeds with no `HiltWorkerFactory` or `WorkManager` initialization errors. The Hilt component graph includes `WorkerAssistedFactory`.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:|HiltWorker" | head -10
```

**NFR scan:**
- **WorkManager init:** WorkManager must be initialized exactly once via `Configuration.Provider` — if `WorkManager.initialize()` is called elsewhere, remove those calls to avoid `IllegalStateException: WorkManager is already initialized`
- **Manifest registration:** Without `android:name` pointing to the correct Application subclass, `HiltWorkerFactory` injection never fires and workers crash on first run

---

## Task 3: TurnDao — Add `getRecentTurns(limit)`

**Context:** Read `app/src/main/kotlin/com/vela/app/data/db/TurnDao.kt` fully. Read `TurnEntity.kt` and `TurnWithEvents.kt` for field names.

**What to build:**

Add one new method to `TurnDao` interface — a cross-conversation query returning the most recent completed turns across ALL conversations:

```kotlin
/**
 * Returns the [limit] most recently completed turns across all conversations.
 * Used by [ProfileWorker] to summarise recent session activity for the profile.
 */
@Transaction
@Query("SELECT * FROM turns WHERE status = 'complete' ORDER BY timestamp DESC LIMIT :limit")
suspend fun getRecentCompletedTurns(limit: Int = 30): List<TurnWithEvents>
```

No other changes to the file.

**Theory of Success:** `./gradlew :app:compileDebugKotlin` passes and Room generates `TurnDao_Impl` containing the new `getRecentCompletedTurns` method.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "BUILD|error:" | head -10
find app/build -name "TurnDao_Impl.java" | head -1
```

**NFR scan:**
- **`@Transaction` required:** `TurnWithEvents` is a `@Relation`-backed data class — Room requires `@Transaction` to load the relation correctly in a single DB transaction, preventing partial reads
- **`status = 'complete'` filter:** Excludes in-flight and errored turns, keeping the profile feed clean and coherent

---

## Task 4: ProfileWorker

**Context:** Read `app/src/main/kotlin/com/vela/app/ai/AmplifierSession.kt` (runTurn signature), `app/src/main/kotlin/com/vela/app/vault/VaultManager.kt` (resolve + root), `app/src/main/kotlin/com/vela/app/vault/VaultRegistry.kt` (enabledVaults), `app/src/main/kotlin/com/vela/app/data/db/TurnDao.kt` (getRecentCompletedTurns from Task 3).

**What to build:**

Create `app/src/main/kotlin/com/vela/app/workers/ProfileWorker.kt` (new directory `workers/`):

```kotlin
package com.vela.app.workers

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.vela.app.ai.AmplifierSession
import com.vela.app.data.db.TurnDao
import com.vela.app.vault.VaultRegistry
import com.vela.app.vault.VaultManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@HiltWorker
class ProfileWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val vaultRegistry: VaultRegistry,
    private val vaultManager: VaultManager,
    private val amplifierSession: AmplifierSession,
    private val turnDao: TurnDao,
) : CoroutineWorker(context, params) {

    companion object {
        const val TAG = "profile_worker"
        private const val PROFILE_PATH = ".vela/profile.md"
        private const val MAX_PULSE_ENTRIES = 20
        private const val MAX_TURNS = 30
        private const val TAG_LOG = "ProfileWorker"
    }

    override suspend fun doWork(): Result {
        val vaults = vaultRegistry.enabledVaults.first()
        if (vaults.isEmpty()) {
            Log.d(TAG_LOG, "No vaults configured — skipping profile update")
            return Result.success()
        }

        // Primary vault = oldest by createdAt
        val primaryVault = vaults.minByOrNull { it.createdAt } ?: return Result.success()
        val profileFile = File(primaryVault.localPath, PROFILE_PATH)

        // Read current profile (or empty string for first run)
        val currentProfile = if (profileFile.exists()) profileFile.readText() else ""
        val lastUpdated = parseLastUpdated(currentProfile)

        // Read changed vault files since last update
        val vaultDelta = buildVaultDelta(vaults, lastUpdated)

        // Read recent sessions
        val recentTurns = turnDao.getRecentCompletedTurns(MAX_TURNS)
        val sessionLines = recentTurns.map { twe ->
            val date = Instant.ofEpochMilli(twe.turn.timestamp)
                .atOffset(ZoneOffset.UTC)
                .format(DateTimeFormatter.ISO_LOCAL_DATE)
            "- $date: Session — ${twe.turn.userMessage.take(80)}"
        }

        // Build LLM prompt
        val prompt = buildPrompt(currentProfile, vaultDelta, sessionLines)

        // Call LLM
        val sb = StringBuilder()
        try {
            amplifierSession.runTurn(
                historyJson       = "[]",
                userInput         = prompt,
                userContentJson   = null,
                systemPrompt      = SYSTEM_PROMPT,
                onToolStart       = { _, _ -> "" },
                onToolEnd         = { _, _ -> },
                onToken           = { token -> sb.append(token) },
                onProviderRequest = { null },
                onServerTool      = { _, _ -> },
            )
        } catch (e: Exception) {
            Log.e(TAG_LOG, "LLM call failed — profile not updated", e)
            return Result.failure()
        }

        val updated = sb.toString().trim()
        if (updated.isEmpty()) {
            Log.e(TAG_LOG, "LLM returned empty response — profile not updated")
            return Result.failure()
        }

        // Write back — only on success
        profileFile.parentFile?.mkdirs()
        profileFile.writeText(updated)
        Log.i(TAG_LOG, "Profile updated: ${profileFile.absolutePath}")
        return Result.success()
    }

    /** Parses `last_updated` ISO string from YAML frontmatter. Returns epoch 0 on missing/failure. */
    private fun parseLastUpdated(profile: String): Long {
        if (profile.isBlank()) return 0L
        val match = Regex("""last_updated:\s*(.+)""").find(profile) ?: return 0L
        return try {
            Instant.parse(match.groupValues[1].trim()).toEpochMilli()
        } catch (e: Exception) { 0L }
    }

    /** Returns map of vaultName → concatenated content of changed files (up to 8 KB each). */
    private fun buildVaultDelta(
        vaults: List<com.vela.app.data.db.VaultEntity>,
        sinceMs: Long,
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (vault in vaults) {
            val root = File(vault.localPath)
            if (!root.exists()) continue
            val changed = root.walkTopDown()
                .filter { it.isFile && it.lastModified() > sinceMs }
                .filter { !it.path.contains("/.vela/") }   // skip our own generated files
                .take(50)
                .joinToString("\n\n---\n\n") { file ->
                    "# ${file.relativeTo(root).path}\n${file.readText().take(8_192)}"
                }
            if (changed.isNotEmpty()) result[vault.name] = changed
        }
        return result
    }

    private fun buildPrompt(
        current: String,
        vaultDelta: Map<String, String>,
        sessionLines: List<String>,
    ): String = buildString {
        appendLine("Current profile:")
        appendLine(current.ifBlank { "(none — first run, create from scratch)" })
        appendLine()
        if (vaultDelta.isNotEmpty()) {
            appendLine("New vault content since last update:")
            vaultDelta.forEach { (name, content) ->
                appendLine("=== $name ===")
                appendLine(content.take(16_384))
                appendLine()
            }
        }
        if (sessionLines.isNotEmpty()) {
            appendLine("Recent sessions:")
            sessionLines.forEach { appendLine(it) }
            appendLine()
        }
        appendLine(UPDATE_INSTRUCTIONS)
    }

    private companion object {
        const val SYSTEM_PROMPT = """You maintain a Vela user profile document. Return ONLY the complete updated profile.md. No explanation."""

        const val UPDATE_INSTRUCTIONS = """
Update the profile document:
1. YAML frontmatter — add new facts conservatively; never remove existing facts without clear evidence; update last_updated to today's ISO date; increment profile_version by 1
2. <vela:knows vault="X" updated="YYYY-MM-DD"> blocks — update each vault's block; add new blocks for new vaults
3. <vela:pulse> — prepend new session entries (format: "- YYYY-MM-DD: Session — [brief summary]"); keep only the 20 most recent entries
Return ONLY the complete updated profile.md. No markdown fences. No explanation."""
    }
}
```

**Theory of Success:** `./gradlew :app:compileDebugKotlin` passes. Room generates Hilt worker factory for `ProfileWorker`.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "BUILD|error:|unresolved" | head -10
find app/build -name "ProfileWorker_AssistedFactory*" 2>/dev/null | head -3
```

**NFR scan:**
- **`@AssistedInject` not `@Inject`:** Regular `@Inject` on a worker constructor causes a runtime `IllegalArgumentException` from WorkManager — `@HiltWorker` + `@AssistedInject` with `@Assisted context` and `@Assisted params` is mandatory
- **Write safety:** Profile file is only written after a non-empty LLM response — `currentProfile` is never truncated/lost on LLM failure or timeout
- **Vault delta safety:** Files inside `.vela/` are excluded from the delta to prevent the worker from feeding its own output back into the LLM

---

## Task 5: ProfileWorkerScheduler

**Context:** Read `app/src/main/kotlin/com/vela/app/workers/ProfileWorker.kt` (from Task 4) to confirm the `TAG` constant (`"profile_worker"`).

**What to build:**

Create `app/src/main/kotlin/com/vela/app/workers/ProfileWorkerScheduler.kt`:

```kotlin
package com.vela.app.workers

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Schedules the periodic [ProfileWorker] job and provides an on-demand trigger
 * for the profile refresh FAB.
 *
 * Call [schedule] once on app startup (from [AppModule] or Application.onCreate).
 * Call [triggerRefresh] when the user taps the refresh FAB in [ProfileScreen].
 */
@Singleton
class ProfileWorkerScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Registers a [PeriodicWorkRequest] that fires once daily when the device is
     * idle and battery is not low (lazy — opportunistic overnight execution).
     *
     * Uses [ExistingPeriodicWorkPolicy.KEEP] so re-registering on every app start
     * does not reset the existing schedule.
     */
    fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresDeviceIdle(true)
            .build()

        val request = PeriodicWorkRequestBuilder<ProfileWorker>(
            repeatInterval = 24,
            repeatIntervalTimeUnit = TimeUnit.HOURS,
            flexTimeInterval = 4,
            flexIntervalTimeUnit = TimeUnit.HOURS,
        )
            .setConstraints(constraints)
            .addTag(ProfileWorker.TAG)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ProfileWorker.TAG,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Immediately enqueues a one-time [ProfileWorker] run.
     * If a run is already in progress, [ExistingWorkPolicy.REPLACE] cancels it
     * and starts fresh — matching the FAB's "force refresh" semantics.
     */
    fun triggerRefresh() {
        val request = OneTimeWorkRequestBuilder<ProfileWorker>()
            .addTag(ProfileWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(
            "${ProfileWorker.TAG}_manual",
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}
```

**Theory of Success:** `./gradlew :app:compileDebugKotlin` passes. `ProfileWorkerScheduler` is a `@Singleton` that Hilt auto-binds via `@Inject constructor`.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "BUILD|error:" | head -10
```

**NFR scan:**
- **`KEEP` vs `UPDATE`:** `ExistingPeriodicWorkPolicy.KEEP` preserves the existing scheduled window — `UPDATE` resets it to 24h from now on every app start, which is wasteful. `KEEP` ensures the job fires on its natural schedule.
- **Unique work name for manual vs periodic:** Using `"profile_worker_manual"` for the `OneTimeWorkRequest` prevents it from interfering with the periodic work queue — they are observed together via the shared `TAG` (`"profile_worker"`).

---

## Task 6: AppModule — Hilt Bindings for ProfileWorkerScheduler

**Context:** Read `app/src/main/kotlin/com/vela/app/di/AppModule.kt` fully — specifically the existing `@Provides @Singleton` block structure. Look for where to add after line 202 (after `provideAmplifierSession`). Also update the Application class from Task 2 to call `schedule()` on start.

**What to build:**

**Step 6.1** — `AppModule.kt`: `ProfileWorkerScheduler` already has `@Singleton @Inject constructor` — Hilt auto-binds it. No `@Provides` entry needed. However, the scheduler must be called to register the periodic work. Add an injection site in the Application class:

In the `@HiltAndroidApp` Application class (found in Task 2), inject `ProfileWorkerScheduler` and call `schedule()` in `onCreate()`:

```kotlin
@HiltAndroidApp
class VelaApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var profileWorkerScheduler: ProfileWorkerScheduler

    override fun onCreate() {
        super.onCreate()
        profileWorkerScheduler.schedule()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
```

**Step 6.2** — Verify `AppModule.kt` already has `@Provides @Singleton fun provideVaultRegistry(...)` and `@Provides @Singleton fun provideVaultManager(...)` — `ProfileWorker` needs both injected. (They should already exist — just confirm.)

**Theory of Success:** `./gradlew :app:assembleDebug` succeeds. The app launches without a `WorkManager` initialization crash. `profileWorkerScheduler.schedule()` is called exactly once at app startup.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:" | head -10
adb -s 10.0.0.106:46619 shell am force-stop com.vela.app && sleep 1 && \
  adb -s 10.0.0.106:46619 shell am start -n com.vela.app/.MainActivity && sleep 3 && \
  adb -s 10.0.0.106:46619 logcat -d 2>/dev/null | grep -E "ProfileWorker|WorkManager|FATAL" | tail -10
```

**NFR scan:**
- **Single `schedule()` call:** `ExistingPeriodicWorkPolicy.KEEP` means calling `schedule()` repeatedly is idempotent — safe to call in `onCreate()` on every cold start
- **No `AppModule` `@Provides` entry needed:** Both `ProfileWorker` (`@HiltWorker`) and `ProfileWorkerScheduler` (`@Singleton @Inject constructor`) are auto-bound by Hilt without explicit providers

---

## Task 7: ProfileViewModel

**Context:** Read `app/src/main/kotlin/com/vela/app/vault/VaultRegistry.kt`, `app/src/main/kotlin/com/vela/app/workers/ProfileWorkerScheduler.kt` (Task 5), `app/src/main/kotlin/com/vela/app/data/db/VaultEntity.kt`. The ViewModel follows the exact `ConversationViewModel` pattern.

**What to build:**

Create `app/src/main/kotlin/com/vela/app/ui/profile/ProfileViewModel.kt`:

```kotlin
package com.vela.app.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.vela.app.vault.VaultRegistry
import com.vela.app.workers.ProfileWorker
import com.vela.app.workers.ProfileWorkerScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

/** Parsed representation of .vela/profile.md for use in ProfileScreen. */
data class ProfileData(
    // From YAML frontmatter
    val name: String = "",
    val role: String = "",
    val location: String = "",
    val keyProjects: List<String> = emptyList(),
    val interests: List<String> = emptyList(),
    val keyPeople: List<String> = emptyList(),
    val lastUpdated: String = "",          // human-readable relative time
    val lastUpdatedMs: Long = 0L,
    // From <vela:knows> blocks
    val knowledgeBlocks: List<KnowledgeBlock> = emptyList(),
    // From <vela:pulse>
    val pulseEntries: List<String> = emptyList(),
)

data class KnowledgeBlock(
    val vaultName: String,
    val updatedDate: String,
    val content: String,
)

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRegistry: VaultRegistry,
    private val scheduler: ProfileWorkerScheduler,
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)

    private val _profileData = MutableStateFlow<ProfileData?>(null)
    val profileData: StateFlow<ProfileData?> = _profileData.asStateFlow()

    private val _hasVault = MutableStateFlow(false)
    val hasVault: StateFlow<Boolean> = _hasVault.asStateFlow()

    /** True while ProfileWorker is actively running (shows LinearProgressIndicator). */
    val isRefreshing: StateFlow<Boolean> = run {
        val flow = MutableStateFlow(false)
        viewModelScope.launch {
            workManager.getWorkInfosByTagFlow(ProfileWorker.TAG).collect { infos ->
                flow.value = infos.any { it.state == WorkInfo.State.RUNNING }
            }
        }
        flow.asStateFlow()
    }

    init {
        viewModelScope.launch {
            vaultRegistry.enabledVaults.collect { vaults ->
                _hasVault.value = vaults.isNotEmpty()
                if (vaults.isNotEmpty()) {
                    loadProfile(vaults.minByOrNull { it.createdAt }!!.localPath)
                } else {
                    _profileData.value = null
                }
            }
        }
    }

    fun refresh() = scheduler.triggerRefresh()

    private fun loadProfile(vaultLocalPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val file = File(vaultLocalPath, ".vela/profile.md")
            if (!file.exists()) {
                _profileData.value = ProfileData()   // empty state — triggers first-run UI
                return@launch
            }
            _profileData.value = parseProfileMd(file.readText())
        }
    }

    // ──────────────────────────────────────────────────────────────────
    // Parsing helpers
    // ──────────────────────────────────────────────────────────────────

    private fun parseProfileMd(content: String): ProfileData {
        val frontmatter = extractFrontmatter(content)
        val knowledgeBlocks = extractKnowledgeBlocks(content)
        val pulse = extractPulse(content)
        val lastUpdatedMs = parseFrontmatterInstant(frontmatter["last_updated"])

        return ProfileData(
            name           = frontmatter["name"] ?: "",
            role           = frontmatter["role"] ?: "",
            location       = frontmatter["location"] ?: "",
            keyProjects    = parseFrontmatterList(frontmatter["key_projects"], content, "key_projects"),
            interests      = parseFrontmatterList(frontmatter["interests"], content, "interests"),
            keyPeople      = parseFrontmatterList(frontmatter["key_people"], content, "key_people"),
            lastUpdated    = formatRelative(lastUpdatedMs),
            lastUpdatedMs  = lastUpdatedMs,
            knowledgeBlocks = knowledgeBlocks,
            pulseEntries   = pulse,
        )
    }

    /** Extracts YAML frontmatter between the two `---` delimiters as a flat key→value map. */
    private fun extractFrontmatter(content: String): Map<String, String> {
        val parts = content.split("---")
        if (parts.size < 3) return emptyMap()
        val yaml = parts[1]
        return yaml.lines()
            .filter { it.contains(":") && !it.trimStart().startsWith("-") }
            .associate { line ->
                val idx = line.indexOf(':')
                line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
    }

    /**
     * Parses a YAML list field from either inline `[a, b, c]` or multi-line `- item` form.
     * [inlineValue] is the value already extracted from the flat frontmatter map.
     * [fullYaml] + [key] are used for the multi-line fallback.
     */
    private fun parseFrontmatterList(inlineValue: String?, fullContent: String, key: String): List<String> {
        if (inlineValue != null && inlineValue.startsWith("[")) {
            return inlineValue.trim('[', ']').split(",").map { it.trim().trim('"', '\'') }.filter { it.isNotEmpty() }
        }
        // Multi-line list: find the key then collect "- item" lines until next key or end
        val lines = fullContent.split("---").getOrElse(1) { "" }.lines()
        val keyIdx = lines.indexOfFirst { it.trimStart().startsWith("$key:") }
        if (keyIdx < 0) return emptyList()
        return lines.drop(keyIdx + 1)
            .takeWhile { it.trimStart().startsWith("-") || it.isBlank() }
            .filter { it.trimStart().startsWith("-") }
            .map { it.trimStart().removePrefix("-").trim() }
    }

    private fun extractKnowledgeBlocks(content: String): List<KnowledgeBlock> {
        val regex = Regex("""<vela:knows[^>]*vault="([^"]+)"[^>]*(?:updated="([^"]*)")?[^>]*>([\s\S]*?)</vela:knows>""")
        return regex.findAll(content).map { m ->
            KnowledgeBlock(
                vaultName   = m.groupValues[1],
                updatedDate = m.groupValues[2],
                content     = m.groupValues[3].trim(),
            )
        }.toList()
    }

    private fun extractPulse(content: String): List<String> {
        val regex = Regex("""<vela:pulse>([\s\S]*?)</vela:pulse>""")
        val block = regex.find(content)?.groupValues?.getOrNull(1) ?: return emptyList()
        return block.lines().map { it.trim() }.filter { it.startsWith("-") }
    }

    private fun parseFrontmatterInstant(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return try { Instant.parse(value).toEpochMilli() } catch (e: Exception) { 0L }
    }

    private fun formatRelative(epochMs: Long): String {
        if (epochMs == 0L) return "never"
        val now = System.currentTimeMillis()
        val diff = now - epochMs
        return when {
            diff < 60_000L           -> "just now"
            diff < 3_600_000L        -> "${diff / 60_000}m ago"
            diff < 86_400_000L       -> "${diff / 3_600_000}h ago"
            diff < 7 * 86_400_000L   -> "${diff / 86_400_000}d ago"
            else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(epochMs))
        }
    }
}
```

**Theory of Success:** `./gradlew :app:compileDebugKotlin` passes. `ProfileViewModel` is a `@HiltViewModel` that Hilt resolves. `profileData`, `hasVault`, and `isRefreshing` StateFlows are accessible.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:compileDebugKotlin 2>&1 | grep -E "BUILD|error:|ProfileViewModel" | head -10
find app/build -name "ProfileViewModel_HiltModules*" 2>/dev/null | head -3
```

**NFR scan:**
- **YAML frontmatter parser:** Handles both inline `[a, b]` and multi-line `- item` list formats without a library dependency; falls back gracefully on malformed frontmatter (returns empty strings/lists, never crashes)
- **WorkManager `getWorkInfosByTagFlow`:** Available in `work-runtime-ktx:2.9.1` — returns a `Flow<List<WorkInfo>>` that updates as worker state changes; drives the `isRefreshing` StateFlow without polling

---

## Task 8: ProfileScreen

**Context:** Read `app/src/main/kotlin/com/vela/app/ui/profile/ProfileViewModel.kt` (Task 7) for `ProfileData`, `KnowledgeBlock`, and the three StateFlows. Study `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt` for existing `MarkdownText` import path, and `app/src/main/kotlin/com/vela/app/ui/theme/` for colour tokens.

**What to build:**

Create `app/src/main/kotlin/com/vela/app/ui/profile/ProfileScreen.kt`:

```kotlin
package com.vela.app.ui.profile

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileScreen(
    onNavigateToSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProfileViewModel = hiltViewModel(),
) {
    val profileData by viewModel.profileData.collectAsState()
    val hasVault    by viewModel.hasVault.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Profile") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            if (hasVault) {
                FloatingActionButton(onClick = { viewModel.refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh profile")
                }
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            if (isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            when {
                !hasVault -> EmptyState(onNavigateToSettings)
                profileData == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> ProfileContent(profileData!!, Modifier.fillMaxSize())
            }
        }
    }
}

@Composable
private fun ProfileContent(data: ProfileData, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(bottom = 88.dp)) {

        // Zone 1 — Identity card
        item {
            IdentityCard(data, modifier = Modifier.padding(16.dp))
        }

        // Zone 2 — Vela knows
        item {
            SectionHeader(
                title = "Vela knows",
                subtitle = if (data.lastUpdated.isNotEmpty()) "updated ${data.lastUpdated}" else null,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (data.knowledgeBlocks.isEmpty()) {
            item {
                Text(
                    "No profile data yet — tap ↻ to generate",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        } else {
            items(data.knowledgeBlocks) { block ->
                KnowledgeCard(block, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
        }

        // Zone 3 — Life pulse
        item {
            SectionHeader(
                title = "Life pulse",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        items(data.pulseEntries) { entry ->
            PulseEntry(entry, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
    }
}

@Composable
private fun IdentityCard(data: ProfileData, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.extraLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp),
                ) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        if (data.name.isNotEmpty()) {
                            Text(
                                data.name.first().uppercaseChar().toString(),
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontWeight = FontWeight.Bold,
                            )
                        } else {
                            Icon(Icons.Default.Person, contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary)
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        data.name.ifEmpty { "Your name" },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (data.role.isNotEmpty()) {
                        Text(
                            data.role,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        )
                    }
                }
            }
            if (data.keyProjects.isNotEmpty() || data.interests.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    (data.keyProjects + data.interests).take(8).forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KnowledgeCard(block: KnowledgeBlock, modifier: Modifier = Modifier) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        block.vaultName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                if (block.updatedDate.isNotEmpty()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        block.updatedDate,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(block.content, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PulseEntry(entry: String, modifier: Modifier = Modifier) {
    val icon = if (entry.contains("Session", ignoreCase = true)) "💬" else "📄"
    Row(modifier = modifier, verticalAlignment = Alignment.Top) {
        Text(icon, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(28.dp))
        Text(
            entry.removePrefix("- ").trim(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp).alpha(0.3f))
}

@Composable
private fun SectionHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        if (subtitle != null) {
            Text(
                " · $subtitle",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun EmptyState(onNavigateToSettings: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Person,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text("Add a vault to see your profile", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Vela will build a living portrait of who you are from your vault content.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onNavigateToSettings) { Text("Add a vault") }
    }
}
```

Note: `HorizontalDivider` alpha is applied via `Modifier.alpha(0.3f)` — requires `import androidx.compose.ui.draw.alpha`.

**Theory of Success:** `./gradlew :app:assembleDebug` passes. `ProfileScreen` is a `@Composable` that accepts `onNavigateToSettings` and `modifier`.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:|ProfileScreen" | head -10
```

**NFR scan:**
- **Empty state first:** `hasVault == false` shows empty state before `profileData` is loaded — prevents a brief "loading" flash when there's nothing to show
- **`contentPadding = PaddingValues(bottom = 88.dp)`:** Ensures the last item isn't occluded by the FAB (56dp) + standard bottom padding

---

## Task 9: NavigationScaffold — Swap PROFILE Destination

**Context:** Read `app/src/main/kotlin/com/vela/app/ui/NavigationScaffold.kt` lines 140-170 — the `DestinationContent` composable's `PROFILE` branch. Read `ProfileScreen.kt` (Task 8) for its exact signature.

**What to build:**

In `ui/NavigationScaffold.kt`, in the `DestinationContent` `when` block, replace the `AppDestination.PROFILE` branch:

**Before:**
```kotlin
AppDestination.PROFILE -> com.vela.app.ui.settings.SettingsScreen(
    onNavigateBack = {},
    onNavigateToAi = {},
    onNavigateToConnections = {},
    onNavigateToVaults = {},
    onNavigateToRecording = {},
    onNavigateToGitHub = {},
    modifier = modifier,
)
```

**After:**
```kotlin
AppDestination.PROFILE -> {
    // Local nav state — tracks whether the user has navigated from Profile into Settings
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) {
        com.vela.app.ui.settings.SettingsScreen(
            onNavigateBack        = { showSettings = false },
            onNavigateToAi        = {},
            onNavigateToConnections = {},
            onNavigateToVaults    = {},
            onNavigateToRecording = {},
            onNavigateToGitHub    = {},
            modifier              = modifier,
        )
    } else {
        com.vela.app.ui.profile.ProfileScreen(
            onNavigateToSettings = { showSettings = true },
            modifier             = modifier,
        )
    }
}
```

Add `import androidx.compose.runtime.getValue`, `import androidx.compose.runtime.setValue`, `import androidx.compose.runtime.mutableStateOf`, `import androidx.compose.runtime.remember` if not already imported.

**Theory of Success:** `./gradlew :app:assembleDebug` passes. The Profile tab shows `ProfileScreen` (not `SettingsScreen`). Tapping ⚙ shows `SettingsScreen`. Pressing the system back button from Settings returns to `ProfileScreen`.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:installDebug 2>&1 | tail -4
adb -s 10.0.0.106:46619 shell am force-stop com.vela.app && sleep 1 && \
  adb -s 10.0.0.106:46619 shell am start -n com.vela.app/.MainActivity && sleep 3 && \
  adb -s 10.0.0.106:46619 shell uiautomator dump /sdcard/profile_tab.xml && \
  adb -s 10.0.0.106:46619 pull /sdcard/profile_tab.xml /tmp/profile_tab.xml 2>/dev/null && \
  grep -oE 'text="[^"]*"' /tmp/profile_tab.xml | grep -E "Profile|Vela knows|Life pulse|Add a vault" | head -10
```

**NFR scan:**
- **Local `showSettings` state:** Kept inside the `PROFILE` branch of `DestinationContent` — scoped to the composable, not a ViewModel. This means navigating away from the Profile tab and back resets to `ProfileScreen`, which is the correct UX (tabs always open to their primary view).
- **Back handler:** The ⚙ → Settings back flow is handled by `onNavigateBack = { showSettings = false }` — this is sufficient; no `BackHandler` needed since `showSettings` is a local state toggle.

---

## Task 10: SettingsScreen — Remove Connections and GitHub Rows

**Context:** Read `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt` lines 31-101 (the `SettingsScreen` composable with its five `SettingsNavRow` entries). Identify the exact code blocks for the Connections row and the GitHub row.

**What to build:**

In `ui/settings/SettingsScreen.kt`, in the `SettingsScreen` composable's `LazyColumn` (or `Column`) body, delete the two rows:

**Delete the Connections row** (the row with `Icons.Default.Hub`, title "Connections", and `onClick = onNavigateToConnections`).

**Delete the GitHub row** (the row with the GitHub icon, title "GitHub", and `onClick = onNavigateToGitHub`).

The remaining three rows in order: **AI**, **Vaults**, **Recording**.

Also remove the now-unused parameters from the `SettingsScreen` signature:
- Remove `onNavigateToConnections: () -> Unit`
- Remove `onNavigateToGitHub: () -> Unit`

Update all call sites (should be just `NavigationScaffold.kt` after Task 9 — the `showSettings` branch):
```kotlin
com.vela.app.ui.settings.SettingsScreen(
    onNavigateBack        = { showSettings = false },
    onNavigateToAi        = {},
    onNavigateToVaults    = {},
    onNavigateToRecording = {},
    modifier              = modifier,
)
```

**Theory of Success:** `./gradlew :app:assembleDebug` passes. The Settings screen (reachable via ⚙ in the Profile tab) shows only three rows: AI, Vaults, Recording. Connections and GitHub rows are absent.

**Proof:**
```bash
cd /Users/ken/workspace/vela
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:" | head -10
grep -n "onNavigateToConnections\|onNavigateToGitHub\|Connections\|GitHub" \
  app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt | head -10
# Expected: zero or only residual comments — no functional references
```

**NFR scan:**
- **All call sites updated:** The `onNavigateToConnections` and `onNavigateToGitHub` parameters must be removed from BOTH the composable signature AND every call site — a single missed call site causes a compile error that catches this
- **`SettingsViewModel` unchanged:** The ViewModel behind `SettingsScreen` manages AI keys, vault CRUD, and GitHub identities (for vault PAT/OAuth). The GitHub identity management code remains — only the navigation row to a standalone GitHub settings screen is removed, not the underlying data model

---

## Amendment — Multi-Turn Generation Loop

**Added:** 2026-04-19

### Task 11 (follow-on): Update ProfileWorker to use InferenceEngine loop

**Problem:** `ProfileWorker.doWork()` currently calls `AmplifierSession.runTurn()` once with a large prompt. This is a single-turn call with no tool access and no ability to self-correct.

**Required change:** Replace the single `runTurn()` call with the `InferenceEngine` multi-turn loop so ProfileWorker can:
- Use tools (read vault files incrementally, search)
- Reason across multiple steps (analyse → draft → refine)
- Match the quality bar of the main chat sessions

**What to build:**
- Inject `InferenceEngine` into `ProfileWorker` alongside or instead of `AmplifierSession`
- Create an ephemeral inference session with a focused system prompt
- Run the loop to completion; extract the final `.vela/profile.md` artifact from the session output
- Remove the manual `StringBuilder` accumulation pattern

**Deferred from current plan** — Tasks 1-10 cover the Profile screen UI and the infrastructure. This is a quality improvement to the generation backend.
