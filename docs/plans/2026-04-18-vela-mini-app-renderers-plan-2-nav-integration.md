# Plan 2 — Navigation Refactor + Integration
## Vela Mini App Renderers

**Design:** `docs/plans/2026-04-18-vela-mini-app-renderers-design.md`
**Depends on:** Plan 1 — Data Layer + SDK Bridge (must be complete)
**Prerequisite files from Plan 1:** `MiniAppRuntime.kt` (exports `MiniAppContainer`), `CapabilitiesGraphRepository.kt`, `MiniAppDocumentStore.kt`, `RendererGenerator.kt`, `VelaJSInterface.kt`

---

## Overview

Six tasks that must run in order. Tasks 1–3 are independent of each other once Plan 1 is complete and can be parallelized across developers if needed. Tasks 4–6 depend on Task 3 (NavigationScaffold) being wired into MainActivity.

| # | Task | Key input | Key output |
|---|------|-----------|------------|
| 1 | ConversationScreen decomposition | `ConversationScreen.kt` (1,263 lines) | 5 new files; screen reduced to ~300 lines |
| 2 | ConnectorsScreen | `NodesScreen.kt` | `ConnectorsScreen.kt`; `NodesScreen.kt` deleted |
| 3 | NavigationScaffold | `MainActivity.kt`, all screen files | `NavigationScaffold.kt`; `MainActivity.kt` updated |
| 4 | VaultBrowserScreen integration | `VaultBrowserScreen.kt`, `MiniAppContainer` | Tap → MiniApp; two-pane tablet |
| 5 | AppModule Hilt wiring | `AppModule.kt`, `VelaDatabase.kt` | All new Plan 1 + Plan 2 components injectable |
| 6 | Error handling + system events | `MiniAppRuntime.kt`, `VelaJSInterface.kt`, `VaultSyncHook` | 4 failure modes handled; 4 system events wired |

---

## Task 1: ConversationScreen Decomposition

**Context — read before implementing:**
- `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt` — full 1,263-line file; understand every composable before moving anything

**What to build:**

### Step 1.1 — Create `SessionsPage.kt`

File: `app/src/main/kotlin/com/vela/app/ui/conversation/SessionsPage.kt`  
Package: `com.vela.app.ui.conversation`

Move these composables verbatim from `ConversationScreen.kt` (lines 234–290):
- `SessionsPage` — promote from `private` to `internal`
- `SessionCard` — promote from `private` to `internal`

Exact public signature after promotion:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionsPage(
    viewModel: ConversationViewModel,
    onBack: () -> Unit,
    onSelect: () -> Unit,
)
```

### Step 1.2 — Create `TurnRow.kt`

File: `app/src/main/kotlin/com/vela/app/ui/conversation/TurnRow.kt`  
Package: `com.vela.app.ui.conversation`

Move from `ConversationScreen.kt`:
- Private data classes `ToolGroup`, `TextEvt`, `TurnItem` (sealed) — keep `internal`
- Private `val AssistantShape` and `val UserShape` — keep `internal`
- `TurnRow` — promote from `private` to `internal`
- `TextEventRow` — promote from `private` to `internal`

Exact public signature after promotion:
```kotlin
@Composable
internal fun TurnRow(
    twe: TurnWithEvents,
    streamingText: String?,
    isLive: Boolean,
)
```

### Step 1.3 — Create `ToolGroupRow.kt`

File: `app/src/main/kotlin/com/vela/app/ui/conversation/ToolGroupRow.kt`  
Package: `com.vela.app.ui.conversation`

Move from `ConversationScreen.kt`:
- `ToolGroupRow` — promote from `private` to `internal`
- `smartLabel` — promote from `private` to `internal`

Exact public signature:
```kotlin
@Composable
internal fun ToolGroupRow(events: List<TurnEventEntity>)

internal fun smartLabel(event: TurnEventEntity): String
```

### Step 1.4 — Create `TodoChecklistRow.kt`

File: `app/src/main/kotlin/com/vela/app/ui/conversation/TodoChecklistRow.kt`  
Package: `com.vela.app.ui.conversation`

Move from `ConversationScreen.kt`:
- `TodoChecklistRow` — promote from `private` to `internal`
- `Quadruple` data class — promote from `private` to `internal`

Exact public signature:
```kotlin
@Composable
internal fun TodoChecklistRow(event: TurnEventEntity)

internal data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
```

### Step 1.5 — Create `ComposerBox.kt` (vault chip removed)

File: `app/src/main/kotlin/com/vela/app/ui/conversation/ComposerBox.kt`  
Package: `com.vela.app.ui.conversation`

Move from `ConversationScreen.kt`:
- `AttachmentItem` data class — promote from `private` to `internal`
- `ComposerBox` — promote from `private` to `internal`
- `AttachmentSheet` — promote from `private` to `internal`
- `AttachmentChip` — promote from `private` to `internal`
- `AttachOption` — promote from `private` to `internal`

**Vault chip removal** — these items are deleted from `ComposerBox` entirely and do not appear in the new file:
- Parameter `allVaults: List<VaultEntity>`
- Parameter `sessionActiveVaultIds: Set<String>`
- Parameter `onToggleVault: (String) -> Unit`
- Parameter `onBrowseVault: (VaultEntity) -> Unit`
- Local state `var showVaultPicker by remember { mutableStateOf(false) }`
- The `Box(Modifier.weight(1f)) { if (allVaults.isNotEmpty()) { FilterChip(...) } }` vault chip block (lines 976–993)
- The vault picker `ModalBottomSheet` block at the bottom of `ComposerBox` (lines 1047–1093)
- Import `com.vela.app.data.db.VaultEntity`

Exact public signature after trimming:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerBox(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onRecord: () -> Unit,
    speechTranscriber: SpeechTranscriber?,
    isListening: Boolean,
    onMicClick: () -> Unit,
    attachments: List<AttachmentItem>,
    onRemoveAttachment: (String) -> Unit,
    onPickPhoto: () -> Unit,
    onPickFile: () -> Unit,
    onCamera: () -> Unit,
)
```

The `Row 2: action bar` section now has an empty `Box(Modifier.weight(1f))` spacer where the vault chip was, keeping the send and mic buttons at their ends.

### Step 1.6 — Update `ConversationScreen.kt`

Remove from the file:
- The `private enum class Page { CHAT, SESSIONS, NODES, SETTINGS, ... }` declaration (lines 88–92)
- The entire `AnimatedContent` block inside `ConversationRoot` and all `prevPage` / `page` state (lines 121–229)
- Imports that are no longer used: `com.vela.app.ui.vault.VaultBrowserScreen`, `com.vela.app.ui.vault.VaultFileViewerScreen`, `com.vela.app.ui.nodes.NodesScreen`, and all settings screen imports that were only used in the `AnimatedContent` block
- All five composables extracted in Steps 1.1–1.5 (their bodies, not just the definitions)

Replace `ConversationRoot` with a thin wrapper:
```kotlin
@Composable
fun ConversationRoot(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    ConversationScreen(speechTranscriber = speechTranscriber, viewModel = viewModel)
}
```

Update the `ConversationScreen` composable's `bottomBar` lambda to use the new trimmed `ComposerBox` signature — remove the four vault-related arguments (`allVaults`, `sessionActiveVaultIds`, `onToggleVault`, `onBrowseVault`).

Also remove these ViewModel state observations that were only used for vault-chip in `ComposerBox`:
- `val allVaults by viewModel.allVaults.collectAsState()`
- `val sessionActiveVaultIds by viewModel.sessionActiveVaultIds.collectAsState()`

Target line count: **≤ 310 lines** (header, `buildAttachedMessage`, `ConversationRoot`, `ConversationScreen` + launcher setup, that's it).

**Theory of Success:** The app compiles and the Projects screen shows the chat interface with the composer — no vault chip visible in the composer action bar, no `Page` enum remaining in `ConversationScreen.kt`.

**Proof:**
```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -5
# Must show: BUILD SUCCESSFUL
wc -l app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt
# Must show: ≤310 lines
```

**NFR scan:**
- **Recomposition safety**: Each extracted composable uses only the state/lambdas it receives as parameters — no shared `remember` blocks that would break when moved to separate files. Verify by scanning for any `remember { }` calls in extracted files that reference composables from a different file.
- **Import hygiene**: Each new file must compile cleanly with no unused imports (Kotlin compiler warning). Run `./gradlew :app:compileDebugKotlin` with `-Werror` or review compiler output for `UNUSED_IMPORT` warnings before marking done.
- **Accessibility stability**: `ComposerBox` contained no `semantics {}` modifiers tied to vault state, so removal does not break any accessibility labels. Confirm no `contentDescription` strings referenced vault entities.

---

## Task 2: ConnectorsScreen

**Context — read before implementing:**
- `app/src/main/kotlin/com/vela/app/ui/nodes/NodesScreen.kt` — 413-line file to migrate
- `app/src/main/kotlin/com/vela/app/ui/nodes/NodesViewModel.kt` — HiltViewModel that stays in place

**What to build:**

### Step 2.1 — Create `ConnectorsScreen.kt`

File: `app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt`  
Package: `com.vela.app.ui.connectors`

This is a top-level navigation destination, so there is no `onBack` parameter.

Exact public signature:
```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectorsScreen(
    viewModel: com.vela.app.ui.nodes.NodesViewModel = hiltViewModel(),
)
```

Port all logic and composables from `NodesScreen.kt` verbatim, adjusting:
- Remove `onBack: () -> Unit` parameter from `ConnectorsScreen` (was `NodesScreen`)
- Remove the `navigationIcon` from the `TopAppBar` (no back button needed on a tab)
- Change `TopAppBar` `title` from `Text("Nodes")` to `Text("Connectors")`
- Keep `DeviceKeyCard`, `NodeCard`, `SshNodeBody`, `AmplifierdNodeBody`, `AddNodeSheet` as `private` composables within the file
- Keep all `NodeType`, `SshNode` imports from `com.vela.app.ssh`

**Stub section** — add the following block immediately after the `if (nodes.isEmpty()) ... else { items(...) }` block, before the closing `}` of the `LazyColumn`:

```kotlin
item {
    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    Spacer(Modifier.height(16.dp))
    Text(
        "External Service Connectors",
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        // TODO: External service connectors — deferred to follow-on design
        "External connectors (GitHub, Linear, Notion, etc.) will appear here in a future update.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}
```

### Step 2.2 — Delete `NodesScreen.kt`

Delete the file `app/src/main/kotlin/com/vela/app/ui/nodes/NodesScreen.kt`.

`NodesViewModel.kt` remains in `com.vela.app.ui.nodes` — do not move it. `ConnectorsScreen` imports it from that package with a fully-qualified import.

**Theory of Success:** `ConnectorsScreen` renders the full Nodes UI (Device Key card + node list + add-node sheet) with the word "Connectors" in the top app bar and a deferred stub section below the node list. The old `NodesScreen.kt` file no longer exists.

**Proof:**
```bash
./gradlew :app:compileDebugKotlin 2>&1 | tail -5
# Must show: BUILD SUCCESSFUL
ls app/src/main/kotlin/com/vela/app/ui/nodes/NodesScreen.kt 2>&1
# Must show: No such file or directory
ls app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt
# Must show the file exists
```

**NFR scan:**
- **No regression in node management**: `ConnectorsScreen` shares `NodesViewModel` with the deleted `NodesScreen`. Since it uses `hiltViewModel()` which is scoped to the composable's `ViewModelStoreOwner`, verify no duplicate ViewModel instances are created — in the tab-based nav (Task 3), each destination gets its own scoped ViewModel, which is the correct behavior.
- **Scope for stub**: The TODO stub is read-only text — no click target, no state, no ViewModel call. This is intentional so it compiles and ships cleanly without partial external connector infrastructure.

---

## Task 3: NavigationScaffold

**Context — read before implementing:**
- `app/src/main/kotlin/com/vela/app/MainActivity.kt` — current entry point calling `ConversationRoot`; will be updated here
- `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt` — after Task 1 (thin `ConversationRoot`)
- `app/src/main/kotlin/com/vela/app/ui/connectors/ConnectorsScreen.kt` — after Task 2
- `app/src/main/kotlin/com/vela/app/ui/vault/VaultBrowserScreen.kt` — existing, will be extended in Task 4
- `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt` — existing Profile destination

**Build changes — do this first:**

In `app/build.gradle.kts`, inside the `dependencies {}` block, add:
```kotlin
// Adaptive window size class — NavigationBar vs NavigationRail switching
implementation("androidx.compose.material3:material3-window-size-class")
```
(No explicit version — governed by the Compose BOM `2025.04.01`.)

### Step 3.1 — Create destination enum

The enum lives at the top of `NavigationScaffold.kt`:
```kotlin
private enum class AppDestination(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val contentDescription: String,
) {
    PROJECTS(
        label = "Projects",
        icon = Icons.Default.ChatBubbleOutline,
        contentDescription = "Projects — chat sessions",
    ),
    VAULT(
        label = "Vault",
        icon = Icons.Default.Folder,
        contentDescription = "Vault — files and mini apps",
    ),
    CONNECTORS(
        label = "Connectors",
        icon = Icons.Default.Hub,
        contentDescription = "Connectors — SSH nodes and services",
    ),
    PROFILE(
        label = "Profile",
        icon = Icons.Default.Person,
        contentDescription = "Profile — settings",
    ),
}
```

### Step 3.2 — Create `NavigationScaffold.kt`

File: `app/src/main/kotlin/com/vela/app/ui/NavigationScaffold.kt`  
Package: `com.vela.app.ui`

Exact public signature:
```kotlin
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun NavigationScaffold(
    windowSizeClass: WindowSizeClass,
    speechTranscriber: com.vela.app.voice.SpeechTranscriber? = null,
    modifier: Modifier = Modifier,
)
```

Required imports:
```kotlin
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Person
```

Internal state:
```kotlin
var currentDestination by remember { mutableStateOf(AppDestination.PROJECTS) }
```

Layout structure — read `windowSizeClass.widthSizeClass`:

**Compact (phone)** — `NavigationBar` at the bottom:
```kotlin
WindowWidthSizeClass.Compact -> {
    Scaffold(
        modifier = modifier,
        bottomBar = {
            NavigationBar {
                AppDestination.entries.forEach { dest ->
                    NavigationBarItem(
                        selected = currentDestination == dest,
                        onClick = { currentDestination = dest },
                        icon = { Icon(dest.icon, contentDescription = dest.contentDescription) },
                        label = { Text(dest.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        DestinationContent(
            destination = currentDestination,
            speechTranscriber = speechTranscriber,
            windowSizeClass = windowSizeClass,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
```

**Medium / Expanded (tablet)** — `NavigationRail` on the left:
```kotlin
else -> {
    Row(modifier = modifier.fillMaxSize()) {
        NavigationRail {
            Spacer(Modifier.weight(1f))
            AppDestination.entries.forEach { dest ->
                NavigationRailItem(
                    selected = currentDestination == dest,
                    onClick = { currentDestination = dest },
                    icon = { Icon(dest.icon, contentDescription = dest.contentDescription) },
                    label = { Text(dest.label) },
                )
            }
            Spacer(Modifier.weight(1f))
        }
        DestinationContent(
            destination = currentDestination,
            speechTranscriber = speechTranscriber,
            windowSizeClass = windowSizeClass,
            modifier = Modifier.weight(1f),
        )
    }
}
```

Private `DestinationContent` composable (in the same file):
```kotlin
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
private fun DestinationContent(
    destination: AppDestination,
    speechTranscriber: com.vela.app.voice.SpeechTranscriber?,
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
) {
    when (destination) {
        AppDestination.PROJECTS -> com.vela.app.ui.conversation.ConversationRoot(
            speechTranscriber = speechTranscriber,
            modifier = modifier,
        )
        AppDestination.VAULT -> {
            // Vault destination — VaultBrowserScreen with vault selection
            // VaultBrowserViewModel handles active vault selection internally
            // Full integration in Task 4; placeholder until then:
            com.vela.app.ui.vault.VaultBrowserScreen(
                windowSizeClass = windowSizeClass,
                modifier = modifier,
            )
        }
        AppDestination.CONNECTORS -> com.vela.app.ui.connectors.ConnectorsScreen(
            modifier = modifier,
        )
        AppDestination.PROFILE -> com.vela.app.ui.settings.SettingsScreen(
            onNavigateBack = {},           // no back in tab nav
            onNavigateToAi = {},           // sub-nav wired in follow-on task
            onNavigateToConnections = {},
            onNavigateToVaults = {},
            onNavigateToRecording = {},
            onNavigateToGitHub = {},
            modifier = modifier,
        )
    }
}
```

> **Note on SettingsScreen sub-navigation:** SettingsScreen currently requires callbacks for drilling into sub-screens (AI, Connections, Vaults, etc.). These are internal-navigation concerns inside the Profile tab. If `SettingsScreen` does not yet accept a `modifier` parameter, add it in a minimal signature update. The sub-nav callbacks may remain as no-ops temporarily — they do not affect the shell compilation.

### Step 3.3 — Update `MainActivity.kt`

Replace the `ConversationRoot` call inside `setContent { VelaTheme { ... } }` with:
```kotlin
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import com.vela.app.ui.NavigationScaffold

// Inside setContent { VelaTheme { ... } }:
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
val windowSizeClass = calculateWindowSizeClass(this)

if (showApiKeyDialog) {
    ApiKeyDialog(onConfirm = { key -> vm.setApiKey(key); showApiKeyDialog = false })
} else {
    NavigationScaffold(
        windowSizeClass = windowSizeClass,
        speechTranscriber = speechTranscriber,
    )
}
```

`calculateWindowSizeClass` takes a `ComponentActivity` receiver — `this` inside `onCreate` satisfies that. No other changes to `MainActivity.kt`.

**Theory of Success:** The app launches and shows a 4-item `NavigationBar` at the bottom on a Compact (phone) device/emulator. On a Medium or Expanded (tablet) emulator, a `NavigationRail` appears on the left side. Tapping each tab switches content without crashing.

**Proof:**
```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:"
# Must show: BUILD SUCCESSFUL with zero error: lines

# On a Compact emulator (e.g. Pixel 6, 411dp width):
adb shell am start -n com.vela.app/.MainActivity
# Manually verify bottom NavigationBar with 4 items visible

# On a tablet emulator (e.g. Pixel Tablet, 800dp+ width):
# Manually verify left NavigationRail with 4 items visible
```

**NFR scan:**
- **Back-gesture safety**: The Android system back gesture must not pop the entire app when switching tabs. `NavigationScaffold` holds a single `var currentDestination` — pressing back when `currentDestination != PROJECTS` should either navigate back to PROJECTS or let the system handle it. Add `BackHandler(enabled = currentDestination != AppDestination.PROJECTS) { currentDestination = AppDestination.PROJECTS }` inside `NavigationScaffold` for phone. On tablet, no back interception is needed since all tabs are permanently visible.
- **No ViewModel re-creation on tab switch**: Using `var currentDestination by remember { mutableStateOf(...) }` inside a single composable means all destination composables are host in the same `NavBackStackEntry`-equivalent scope. `hiltViewModel()` inside each destination composable will reuse existing ViewModel instances if the composable stays in the composition. Verify by logging ViewModel init; switching tabs must not print the init log a second time.
- **WindowSizeClass passed through to children**: `VaultBrowserScreen` (Task 4) and the two-pane layout both need `windowSizeClass`. It is passed as a parameter through `DestinationContent` — not re-computed per destination — so there is a single `calculateWindowSizeClass` call site in `MainActivity`.

---

## Task 4: VaultBrowserScreen Integration

**Context — read before implementing:**
- `app/src/main/kotlin/com/vela/app/ui/vault/VaultBrowserScreen.kt` — full 458-line file
- `app/src/main/kotlin/com/vela/app/ui/vault/VaultBrowserViewModel.kt` — read to understand what vault state is already observable
- `MiniAppRuntime.kt` (Plan 1 output) — signature: `@Composable fun MiniAppContainer(itemPath: String, itemContent: String, contentType: String, layout: String, modifier: Modifier = Modifier)`

**What to build:**

### Step 4.1 — Update `VaultBrowserScreen` public signature

The `VaultBrowserScreen` composable currently requires a `vault: VaultEntity` parameter passed from outside. This task updates it to support the Vault tab in `NavigationScaffold`, where vault selection is managed internally.

New signature (replaces the existing one):
```kotlin
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3WindowSizeClassApi::class)
@Composable
fun VaultBrowserScreen(
    windowSizeClass: WindowSizeClass,
    modifier: Modifier = Modifier,
    viewModel: VaultBrowserViewModel = hiltViewModel(),
)
```

The old `vault: VaultEntity` + `onBack: () -> Unit` + `onOpenFile: (String) -> Unit` parameters are removed. `VaultBrowserViewModel` already exposes an `allVaults: StateFlow<List<VaultEntity>>` (or similar) — the screen shows a vault picker if no vault is selected. Read `VaultBrowserViewModel` to confirm the exact property name before writing code.

If `VaultBrowserViewModel` does not yet expose `allVaults`, add:
```kotlin
val allVaults: StateFlow<List<VaultEntity>> = vaultRegistry.enabledVaults
    .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
```

### Step 4.2 — Add file-selection state

Inside `VaultBrowserScreen`, add:
```kotlin
var selectedFilePath by remember { mutableStateOf<String?>(null) }
val selectedVault by viewModel.activeVault.collectAsState()   // single active vault
val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact
```

`viewModel.activeVault` is the vault currently being browsed. If no vault is set, render a vault-picker UI (reuse the existing vault-list pattern from `ComposerBox`'s now-removed vault picker — build a simple `LazyColumn` of vault cards with a tap-to-select action that calls `viewModel.setVault(vault)`).

### Step 4.3 — Phone layout (Compact): tap → full-screen MiniAppContainer

When `isCompact == true`:
```kotlin
val vault = selectedVault
if (vault != null && selectedFilePath != null) {
    // Full-screen MiniAppContainer — back clears selection
    BackHandler { selectedFilePath = null }
    MiniAppContainerView(
        vault = vault,
        relPath = selectedFilePath!!,
        layout = "phone",
        modifier = modifier.fillMaxSize(),
    )
} else {
    // Existing file-browser Scaffold (unchanged, except onOpenFile now sets selectedFilePath)
    VaultFileListPane(
        vault = vault,
        viewModel = viewModel,
        onOpenFile = { path -> selectedFilePath = path },
        modifier = modifier,
    )
}
```

### Step 4.4 — Tablet layout (Medium/Expanded): permanent two-pane

When `isCompact == false`:
```kotlin
val vault = selectedVault
Row(modifier = modifier.fillMaxSize()) {
    // Left pane — fixed width file list
    VaultFileListPane(
        vault = vault,
        viewModel = viewModel,
        onOpenFile = { path -> selectedFilePath = path },
        modifier = Modifier.width(320.dp).fillMaxHeight(),
    )
    VerticalDivider()
    // Right pane — MiniAppContainer or empty state
    val path = selectedFilePath
    if (vault != null && path != null) {
        MiniAppContainerView(
            vault = vault,
            relPath = path,
            layout = "tablet",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    } else {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Select a file to view",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
```

### Step 4.5 — Extract `VaultFileListPane` private composable

Wrap the existing `Scaffold { ... }` body of `VaultBrowserScreen` (search bar + `LazyColumn` entries) into a private composable:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultFileListPane(
    vault: VaultEntity?,
    viewModel: VaultBrowserViewModel,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
)
```

This is a refactor-only step — behavior is identical to the existing file-browser body; the only change is that file taps call `onOpenFile(entry.relativePath)` instead of a navigation callback.

### Step 4.6 — Create `MiniAppContainerView` private composable

```kotlin
@Composable
private fun MiniAppContainerView(
    vault: VaultEntity,
    relPath: String,
    layout: String,
    modifier: Modifier = Modifier,
) {
    val itemPath = remember(vault.localPath, relPath) {
        "${vault.localPath}/$relPath"
    }
    var itemContent by remember(itemPath) { mutableStateOf("") }
    val contentType = remember(relPath) { detectContentType(relPath) }

    LaunchedEffect(itemPath) {
        itemContent = withContext(Dispatchers.IO) {
            runCatching { java.io.File(itemPath).readText() }.getOrElse { "" }
        }
    }

    MiniAppContainer(
        itemPath = itemPath,
        itemContent = itemContent,
        contentType = contentType,
        layout = layout,
        modifier = modifier,
    )
}

/** Maps file extension to a content type string for the capabilities graph. */
private fun detectContentType(relPath: String): String {
    val ext = relPath.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "md"                         -> "markdown"
        "jpg", "jpeg", "png", "webp" -> "image"
        "pdf"                        -> "pdf"
        "json"                       -> "json"
        "csv"                        -> "csv"
        "html", "htm"                -> "html"
        else                         -> "text"
    }
}
```

### Step 4.7 — Update `NavigationScaffold.kt` (Task 3 placeholder)

In `DestinationContent`, the `AppDestination.VAULT` branch already passes `windowSizeClass` to `VaultBrowserScreen`. Confirm the call matches the updated signature:
```kotlin
AppDestination.VAULT -> com.vela.app.ui.vault.VaultBrowserScreen(
    windowSizeClass = windowSizeClass,
    modifier = modifier,
)
```

**Theory of Success:** On phone: tapping a non-directory file in the Vault tab navigates to a full-screen `MiniAppContainer` and the system back gesture returns to the file list. On tablet (emulator ≥ 800dp): the file list is permanently visible on the left at 320dp, and tapping a file loads `MiniAppContainer` on the right without navigating away.

**Proof:**
```bash
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:"
# Must show: BUILD SUCCESSFUL

# Phone emulator manual smoke test:
# 1. Launch app → tap Vault tab → tap any .md file
# 2. MiniAppContainer loads (WebView visible)
# 3. System back → file list visible again
# adb logcat -s MiniAppRuntime | grep "loaded" should show a log line
```

**NFR scan:**
- **Content load on IO thread**: `File.readText()` inside `LaunchedEffect` dispatches to `Dispatchers.IO` (Step 4.6). File reads must never happen on the main thread — confirm `withContext(Dispatchers.IO)` wraps the call.
- **State isolation between files**: `selectedFilePath` is a simple `String?` — switching files replaces the state, causing `MiniAppContainerView` to recompose with the new `itemPath`. `remember(itemPath)` on `itemContent` resets the loaded content on path change. Confirm no content bleed between consecutive file taps by testing: tap file A → back → tap file B — the right pane must show B's content, not A's.
- **VaultFileViewerScreen retirement**: The old `VaultFileViewerScreen` composable remains in `VaultBrowserScreen.kt` for now (it's used by nothing after this change). Flag it with `@Deprecated("Replaced by MiniAppContainer — remove after rollout")` rather than deleting it immediately, to allow a clean rollback window.

---

## Task 5: AppModule Hilt Wiring

**Context — read before implementing:**
- `app/src/main/kotlin/com/vela/app/di/AppModule.kt` — 276-line module; add new `@Provides` methods here
- `app/src/main/kotlin/com/vela/app/data/db/VelaDatabase.kt` — version 12; needs v13 entities + migration
- `app/src/main/kotlin/com/vela/app/di/EnabledVaultPaths.kt` — model for `@Qualifier` pattern to follow
- Plan 1 output files: `CapabilitiesGraphRepository.kt`, `MiniAppDocumentStore.kt`, `RendererGenerator.kt`, `VelaJSInterface.kt` — read constructors before writing `@Provides` methods

**What to build:**

### Step 5.1 — Create `@VelaEventBus` qualifier

File: `app/src/main/kotlin/com/vela/app/di/VelaEventBus.kt`  
Package: `com.vela.app.di`

```kotlin
package com.vela.app.di

import javax.inject.Qualifier

/**
 * Qualifier for the singleton MutableSharedFlow<String> that carries
 * JSON-encoded Vela system and mini-app events between native code and WebViews.
 * Topic format: "{source}:{event-name}" — e.g. "vela:theme-changed",
 * "recipe:ingredients-ready".
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VelaEventBus
```

### Step 5.2 — Update `VelaDatabase.kt`

**Entities** — add to the `@Database` entities list:
```kotlin
MiniAppRegistryEntity::class,
MiniAppDocumentEntity::class,
```

**Version** — bump `version = 12` to `version = 13`.

**Abstract DAO methods** — add:
```kotlin
abstract fun miniAppRegistryDao(): MiniAppRegistryDao
abstract fun miniAppDocumentDao(): MiniAppDocumentDao
```

**Migration constant** — add at the end of the file, following the exact style of `MIGRATION_11_12`:
```kotlin
/** v12→v13: add mini_app_registry and mini_app_documents tables for WebView renderer caching. */
val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS mini_app_registry (
                content_type  TEXT NOT NULL PRIMARY KEY,
                renderer_path TEXT NOT NULL,
                provides      TEXT NOT NULL DEFAULT '[]',
                consumes      TEXT NOT NULL DEFAULT '[]',
                db_collections TEXT NOT NULL DEFAULT '[]',
                version       INTEGER NOT NULL DEFAULT 1,
                last_used     INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS mini_app_documents (
                id           TEXT NOT NULL PRIMARY KEY,
                scope_prefix TEXT NOT NULL,
                collection   TEXT NOT NULL,
                doc_id       TEXT NOT NULL,
                data         TEXT NOT NULL,
                updated_at   INTEGER NOT NULL
            )
        """.trimIndent())
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_mini_app_docs_collection " +
            "ON mini_app_documents (scope_prefix, collection)"
        )
    }
}
```

### Step 5.3 — Update `AppModule.kt` — database migrations

In `provideDatabase`, extend the `addMigrations(...)` call:
```kotlin
.addMigrations(
    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12,
    MIGRATION_12_13,   // <-- new
)
```

### Step 5.4 — Add DAO providers to `AppModule.kt`

Add alongside the existing DAO providers (after `provideGitHubIdentityDao`):
```kotlin
@Provides
fun provideMiniAppRegistryDao(db: VelaDatabase): MiniAppRegistryDao =
    db.miniAppRegistryDao()

@Provides
fun provideMiniAppDocumentDao(db: VelaDatabase): MiniAppDocumentDao =
    db.miniAppDocumentDao()
```

### Step 5.5 — Add Plan 1 component providers to `AppModule.kt`

Read each Plan 1 file's primary constructor before writing these — the parameter names below reflect the expected signatures from the design, but match exactly to what was built:

```kotlin
@Provides @Singleton
fun provideCapabilitiesGraphRepository(
    dao: MiniAppRegistryDao,
): com.vela.app.miniapp.CapabilitiesGraphRepository =
    com.vela.app.miniapp.CapabilitiesGraphRepository(dao)

@Provides @Singleton
fun provideMiniAppDocumentStore(
    dao: MiniAppDocumentDao,
): com.vela.app.miniapp.MiniAppDocumentStore =
    com.vela.app.miniapp.MiniAppDocumentStore(dao)

@Provides @Singleton
fun provideRendererGenerator(
    session: AmplifierSession,
    capabilitiesRepo: com.vela.app.miniapp.CapabilitiesGraphRepository,
    vaultManager: VaultManager,
): com.vela.app.miniapp.RendererGenerator =
    com.vela.app.miniapp.RendererGenerator(session, capabilitiesRepo, vaultManager)
```

### Step 5.6 — Add EventBus provider to `AppModule.kt`

```kotlin
@Provides @Singleton @VelaEventBus
fun provideVelaEventBus(): kotlinx.coroutines.flow.MutableSharedFlow<String> =
    kotlinx.coroutines.flow.MutableSharedFlow(
        replay = 0,
        extraBufferCapacity = 64,
    )
```

The event bus carries JSON strings in the format `{"topic":"vela:theme-changed","payload":{...}}`. Topics follow `{source}:{event-name}` convention.

### Step 5.7 — Add VelaJSInterface factory provider to `AppModule.kt`

```kotlin
@Provides @Singleton
fun provideVelaJSInterfaceFactory(
    documentStore: com.vela.app.miniapp.MiniAppDocumentStore,
    session: AmplifierSession,
    vaultManager: VaultManager,
    @VelaEventBus eventBus: kotlinx.coroutines.flow.MutableSharedFlow<String>,
): com.vela.app.miniapp.VelaJSInterface.Factory =
    com.vela.app.miniapp.VelaJSInterface.Factory(documentStore, session, vaultManager, eventBus)
```

If `VelaJSInterface` is a direct `@Singleton` rather than a factory (depends on Plan 1 implementation), replace with:
```kotlin
@Provides @Singleton
fun provideVelaJSInterface(
    documentStore: com.vela.app.miniapp.MiniAppDocumentStore,
    session: AmplifierSession,
    vaultManager: VaultManager,
    @VelaEventBus eventBus: kotlinx.coroutines.flow.MutableSharedFlow<String>,
): com.vela.app.miniapp.VelaJSInterface =
    com.vela.app.miniapp.VelaJSInterface(documentStore, session, vaultManager, eventBus)
```

**Theory of Success:** Hilt generates the DI graph without missing-binding errors. `MiniAppContainer` can be injected with all its Plan 1 dependencies. The app does not crash on launch with a `ProviderNotFoundException` or `UnsatisfiedDependencyException`.

**Proof:**
```bash
./gradlew :app:hiltJavaCompileDebug 2>&1 | grep -E "BUILD|error:|missing"
# Must show: BUILD SUCCESSFUL with zero binding errors

./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:"
# Must show: BUILD SUCCESSFUL
```

**NFR scan:**
- **Singleton EventBus scope**: The `@VelaEventBus MutableSharedFlow<String>` is `@Singleton` and `replay = 0` — new subscribers do not receive old events. This is the correct semantics for UI theme/layout events (you want the current state, not a replay of past events). If any consumer needs current-value semantics (e.g., current theme), they should observe `isSystemInDarkTheme()` directly rather than replaying from the bus.
- **Room migration correctness**: `MIGRATION_12_13` creates both new tables with `CREATE TABLE IF NOT EXISTS` to be safe on repeated migration runs. The index on `(scope_prefix, collection)` covers the primary `vela.db.watch()` query pattern described in the design. Verify the schema against `app/schemas/com.vela.app.data.db.VelaDatabase/13.json` after the first successful `hiltJavaCompileDebug` run — the Room schema export will generate this file.
- **No circular dependencies**: `RendererGenerator` depends on `AmplifierSession`, `CapabilitiesGraphRepository`, and `VaultManager`. None of those depend on `RendererGenerator`. Confirm by running `./gradlew :app:kspDebugKotlin` and checking for `DependencyCycleException` — there should be none.

---

## Task 6: Error Handling + System Events

**Context — read before implementing:**
- `MiniAppRuntime.kt` (Plan 1) — owns WebView lifecycle and calls `RendererGenerator`; error handling hooks go here
- `VelaJSInterface.kt` (Plan 1) — `Db`, `Events`, `Ai`, `Vault` inner objects; error path per namespace
- `app/src/main/kotlin/com/vela/app/ui/NavigationScaffold.kt` — system event trigger for `vela:layout-changed` and `vela:theme-changed`
- `app/src/main/kotlin/com/vela/app/vault/VaultGitSync.kt` — `pull()` / `cloneIfNeeded()` success/failure paths
- `app/src/main/kotlin/com/vela/app/di/AppModule.kt` — `VaultSyncHook` wiring in `provideHooks`

**What to build:**

This task addresses all four named failure modes from design §5 and wires all four system events to their triggers.

---

### Failure Mode 1 — "LLM fails to generate renderer"

**Location:** `MiniAppRuntime.kt` — the composable that calls `RendererGenerator.generate()` and displays the result.

**Implementation:** Wrap the renderer generation call in a `try/catch`. On any exception (network timeout, malformed JSON from LLM, IO failure writing HTML to vault):

```kotlin
// Inside MiniAppRuntime / MiniAppContainer composable:
var rendererState by remember(itemPath) {
    mutableStateOf<RendererState>(RendererState.Loading)
}

LaunchedEffect(itemPath, itemContent, contentType) {
    rendererState = RendererState.Loading
    rendererState = try {
        val html = rendererGenerator.getOrGenerate(itemPath, itemContent, contentType)
        RendererState.Ready(html)
    } catch (e: Exception) {
        Log.w("MiniAppRuntime", "Renderer generation failed for $contentType, falling back", e)
        RendererState.Fallback(contentType, itemContent)
    }
}

when (val s = rendererState) {
    is RendererState.Loading  -> Box(modifier, Alignment.Center) { CircularProgressIndicator() }
    is RendererState.Ready    -> MiniAppWebView(html = s.html, ...)
    is RendererState.Fallback -> FallbackRenderer(contentType = s.contentType, content = s.content, modifier)
}
```

`FallbackRenderer` dispatches to existing extension-based composables already present in `VaultBrowserScreen.kt`:

```kotlin
@Composable
private fun FallbackRenderer(contentType: String, content: String, modifier: Modifier = Modifier) {
    when (contentType) {
        "markdown" -> Box(modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
            MarkdownText(text = content, color = MaterialTheme.colorScheme.onSurface)
        }
        "image"    -> ImageViewer(file = java.io.File(itemPath), modifier = modifier)
        else       -> PlainTextViewer(content = content, modifier = modifier)
    }
}
```

**No crash, no blank screen** — the fallback always renders something.

---

### Failure Mode 2 — "`vela.db` / `vela.vault` throws"

**Location:** `VelaJSInterface.kt` — `Db` and `Vault` inner objects.

**Implementation:** Every `@JavascriptInterface`-annotated method in `Db` and `Vault` that performs async work returns a JS Promise via a callback ID pattern. On exception, call the error callback:

```kotlin
// Pattern for every Db/Vault method:
@JavascriptInterface
fun dbPut(collection: String, id: String, data: String, callbackId: String) {
    scope.launch {
        try {
            documentStore.put(collection, id, data)
            webView.evaluateJavascript(
                "window.__vela_resolve('$callbackId', null, null)", null
            )
        } catch (e: Exception) {
            val errorJson = buildErrorJson(
                code = "DB_WRITE_FAILED",
                message = e.message ?: "Unknown error",
                detail = collection,
            )
            webView.evaluateJavascript(
                "window.__vela_reject('$callbackId', $errorJson)", null
            )
        }
    }
}

private fun buildErrorJson(code: String, message: String, detail: String = ""): String =
    """{"code":"$code","message":${message.toJsonString()},"detail":${detail.toJsonString()}}"""
```

The generated mini app JS has `window.__vela_resolve` / `window.__vela_reject` wired to a Promise pool injected at load time. This is a Plan 1 concern; Task 6 verifies that the `catch` blocks call `__vela_reject` with the structured error object rather than swallowing exceptions or rethrowing into native code.

**Observable rule:** Kotlin exceptions must never propagate out of a `@JavascriptInterface` method — doing so crashes the WebView renderer process. The `try/catch` at the boundary is non-negotiable.

---

### Failure Mode 3 — "`vela.ai` stream interrupted"

**Location:** `VelaJSInterface.kt` — `Ai` inner object, `stream()` method.

**Implementation:** When the `AmplifierSession` stream is cancelled or throws mid-stream:

```kotlin
@JavascriptInterface
fun aiStream(prompt: String, callbackId: String) {
    scope.launch {
        try {
            session.stream(prompt).collect { chunk ->
                webView.evaluateJavascript(
                    "window.__vela_stream_chunk('$callbackId', ${chunk.toJsonString()})", null
                )
            }
            webView.evaluateJavascript(
                "window.__vela_stream_done('$callbackId')", null
            )
        } catch (e: CancellationException) {
            // Stream cancelled (e.g. user navigated away) — publish system event
            eventBus.emit("""{"topic":"vela:ai-interrupted","payload":{"callbackId":"$callbackId"}}""")
            webView.evaluateJavascript(
                "window.__vela_stream_interrupted('$callbackId')", null
            )
            throw e  // re-throw CancellationException as per coroutines contract
        } catch (e: Exception) {
            eventBus.emit("""{"topic":"vela:ai-interrupted","payload":{"callbackId":"$callbackId","error":${e.message.toJsonString()}}}""")
            webView.evaluateJavascript(
                "window.__vela_stream_interrupted('$callbackId')", null
            )
        }
    }
}
```

The mini app's JS `vela.ai.stream()` wrapper subscribes to `vela:ai-interrupted` via `vela.events.subscribe` so it can show a "stream stopped" indicator or offer a retry button.

---

### Failure Mode 4 — "Vault sync fails"

**Location:** `app/src/main/kotlin/com/vela/app/hooks/VaultSyncHook.kt` (existing) + `AppModule.kt` `provideHooks`.

**Implementation:** The existing `VaultSyncHook` has access to `onAfterSync` and the sync call site. Inject the `@VelaEventBus` into `VaultSyncHook` and publish on failure:

```kotlin
// In VaultSyncHook constructor or via setter injection:
class VaultSyncHook(
    private val cloneIfNeeded: suspend (id: String, path: String) -> Unit,
    private val pull: suspend (id: String, path: String) -> Unit,
    private val vaultSettings: VaultSettings,
    private val onAfterSync: suspend (vault: VaultEntity) -> Unit,
    @VelaEventBus private val eventBus: kotlinx.coroutines.flow.MutableSharedFlow<String>,
) : Hook {
    // ... existing logic ...

    // In the catch block around pull():
    } catch (e: Exception) {
        Log.e("VaultSyncHook", "Vault sync failed", e)
        eventBus.tryEmit(
            """{"topic":"vela:sync-failed","payload":{"vaultId":"$vaultId","error":${e.message.toJsonString()}}}"""
        )
        // Do NOT rethrow — sync failure must not crash the session
    }
}
```

In `AppModule.provideHooks`, add `@VelaEventBus eventBus: MutableSharedFlow<String>` as a parameter and pass it to `VaultSyncHook`:
```kotlin
@Provides @Singleton
fun provideHooks(
    vaultSettings: VaultSettings,
    vaultGitSync: VaultGitSync,
    embeddingEngine: EmbeddingEngine,
    @VelaEventBus eventBus: kotlinx.coroutines.flow.MutableSharedFlow<String>,
): @JvmSuppressWildcards List<Hook> = listOf(
    VaultSyncHook(
        cloneIfNeeded = { id, path -> vaultGitSync.cloneIfNeeded(id, path) },
        pull          = { id, path -> vaultGitSync.pull(id, path) },
        vaultSettings = vaultSettings,
        onAfterSync   = { vault -> embeddingEngine.startIndexing(vault) },
        eventBus      = eventBus,
    ),
    // ... rest unchanged
)
```

Mini apps subscribed to `vela:sync-failed` via `vela.events.subscribe("vela:sync-failed", cb)` will receive the payload and can show a stale-data indicator in their UI.

---

### System Event — `vela:theme-changed`

**Trigger location:** `NavigationScaffold.kt`

Wire in a `LaunchedEffect` that observes dark mode and publishes on change:
```kotlin
// Inside NavigationScaffold composable:
val isDark = isSystemInDarkTheme()
val eventBus: kotlinx.coroutines.flow.MutableSharedFlow<String> = hiltViewModel<NavigationViewModel>().eventBus

LaunchedEffect(isDark) {
    eventBus.emit("""{"topic":"vela:theme-changed","payload":{"isDark":$isDark}}""")
}
```

`NavigationViewModel` is a thin `@HiltViewModel` holding `@VelaEventBus val eventBus`. Create it:

File: `app/src/main/kotlin/com/vela/app/ui/NavigationViewModel.kt`
```kotlin
@HiltViewModel
class NavigationViewModel @Inject constructor(
    @VelaEventBus val eventBus: kotlinx.coroutines.flow.MutableSharedFlow<String>,
) : ViewModel()
```

---

### System Event — `vela:vault-changed`

**Trigger location:** `VelaJSInterface.kt` — `Vault.write()` method, after a successful `vaultManager.write()` call:
```kotlin
vaultManager.write(path, content)
eventBus.tryEmit("""{"topic":"vela:vault-changed","payload":{"path":${path.toJsonString()}}}""")
```

---

### System Event — `vela:vault-synced`

**Trigger location:** `VaultSyncHook.kt` — `onAfterSync` lambda call site, after `embeddingEngine.startIndexing()` completes (or immediately after a successful `pull()`):
```kotlin
onAfterSync(vault)
eventBus.tryEmit("""{"topic":"vela:vault-synced","payload":{"vaultId":"${vault.id}"}}""")
```

---

### System Event — `vela:layout-changed`

**Trigger location:** `NavigationScaffold.kt` — in the same `LaunchedEffect` or a separate one observing `windowSizeClass`:
```kotlin
val layoutMode = if (windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact) "phone" else "tablet"
LaunchedEffect(layoutMode) {
    eventBus.emit("""{"topic":"vela:layout-changed","payload":{"layout":"$layoutMode"}}""")
}
```

Mini apps receive this event and can update their CSS variable `--vela-layout` via:
```js
vela.events.subscribe('vela:layout-changed', ({ layout }) => {
  document.documentElement.style.setProperty('--vela-layout', layout);
});
```

---

**Theory of Success:** All four failure modes are covered with no crashes:
1. Renderer generation failure → fallback composable renders; logcat shows `W/MiniAppRuntime: Renderer generation failed`.
2. `vela.db` / `vela.vault` throws → JS Promise rejects with `{code, message, detail}`; logcat shows the error but no crash.
3. `vela.ai` stream interrupted → `vela:ai-interrupted` emitted on event bus; mini app receives it.
4. Vault sync fails → `vela:sync-failed` emitted; logcat shows `E/VaultSyncHook: Vault sync failed`.

All four system events fire: theme toggle → `vela:theme-changed`; `vela.vault.write()` → `vela:vault-changed`; successful sync → `vela:vault-synced`; emulator window resize → `vela:layout-changed`.

**Proof:**
```bash
# Build check:
./gradlew :app:assembleDebug 2>&1 | grep -E "BUILD|error:"
# Must show: BUILD SUCCESSFUL

# Failure mode 1 — force renderer failure by mocking RendererGenerator to throw,
# then verify logcat:
adb logcat -s MiniAppRuntime | grep "Renderer generation failed"

# System events — after launching the app:
adb logcat -s VelaJSInterface | grep "vela:theme-changed\|vela:layout-changed"
# Toggle dark mode in emulator settings → "vela:theme-changed" must appear
```

**NFR scan:**
- **CancellationException contract**: Failure Mode 3 re-throws `CancellationException` after emitting the event. This is mandatory — structured concurrency requires `CancellationException` to propagate. Consuming it silently breaks coroutine cancellation (e.g., ViewModel cleanup on navigation). The `re-throw` comment must be present in code to prevent future maintainers from removing it.
- **`tryEmit` vs `emit` semantics**: Sync hooks and the `@JavascriptInterface` methods use `tryEmit` (non-suspending, drops event if buffer is full at `extraBufferCapacity = 64`). The `NavigationScaffold` `LaunchedEffect` uses suspending `emit` since it runs in a coroutine. Buffer overflow on the event bus is acceptable for UI events (a dropped `vela:theme-changed` during a rapid theme toggle is harmless) but never acceptable for data-mutation events. If `vela:vault-changed` must be reliable, increase `extraBufferCapacity` or use `emit` in a dedicated coroutine scope.

---

## Dependency Graph

```
Task 1 (decomposition) ──────────────────────────────┐
Task 2 (ConnectorsScreen) ───────────────────────────┤
                                                       ▼
                                               Task 3 (NavigationScaffold)
                                                       │
                         ┌─────────────────────────────┤
                         ▼                             ▼
                Task 4 (VaultBrowserScreen)     Task 5 (AppModule)
                         │                             │
                         └────────────┬────────────────┘
                                      ▼
                               Task 6 (Error handling + events)
```

Tasks 1 and 2 can run concurrently (no shared state between them). Task 3 requires Tasks 1 and 2 complete so that `ConversationRoot` and `ConnectorsScreen` have their final signatures. Tasks 4, 5, and 6 all require Task 3. Tasks 4 and 5 can run concurrently. Task 6 requires Tasks 4 and 5 complete (it modifies `MiniAppRuntime.kt` and `VelaJSInterface.kt` which Task 5 must have wired into the DI graph, and it modifies `NavigationScaffold.kt` which Task 3 creates).

---

## Checklist

- [ ] Task 1: `ConversationScreen.kt` ≤ 310 lines; 5 new files created; vault chip absent from `ComposerBox`; `Page` enum deleted
- [ ] Task 2: `ConnectorsScreen.kt` exists in `com.vela.app.ui.connectors`; `NodesScreen.kt` deleted; TODO stub present
- [ ] Task 3: `NavigationScaffold.kt` created; `material3-window-size-class` in `build.gradle.kts`; `MainActivity.kt` calls `NavigationScaffold`; 4 tabs visible on phone and tablet
- [ ] Task 4: File tap in Vault tab loads `MiniAppContainer`; phone back gesture returns to list; tablet shows permanent two-pane
- [ ] Task 5: `VelaDatabase` at version 13 with 2 new tables; `MIGRATION_12_13` in migrations list; all Plan 1 types injectable; `@VelaEventBus` qualifier exists
- [ ] Task 6: All 4 failure modes handled at their source; all 4 system events wired to triggers; `NavigationViewModel.kt` created; `VaultSyncHook` updated with eventBus parameter
