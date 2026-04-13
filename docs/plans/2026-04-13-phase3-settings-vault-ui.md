# Phase 3: Settings & Vault UI — Implementation Plan

> **For execution:** Use `/execute-plan` mode or the subagent-driven-development recipe.

**Goal:** Add a unified Settings screen consolidating AI credentials, Connections (SSH nodes — moved from top bar), and Vault management. Add per-session vault chips row in ConversationScreen. Move the existing Connections top-bar button into Settings.

**Builds on:** Phase 1 (VaultRegistry, VaultManager, VaultGitSync, VaultSettings, VaultEntity, MIGRATION_6_7) and Phase 2 (SessionHarness, HookRegistry)

**Architecture:** Add `Page.SETTINGS` and `Page.VAULT_DETAIL` to the existing `Page` enum in `ConversationScreen.kt`. Single Settings destination reachable via gear icon replacing the Hub icon in ConversationScreen's top bar. Three scrollable sections: AI (API key, model), Connections (SSH nodes content embedded without nested Scaffold), Vaults (vault list with add/edit/delete). ConversationScreen gains an optional horizontal chips row for per-session vault toggling. `prevPage` tracking added to `ConversationRoot` so SETTINGS → NODES → SETTINGS back navigation works cleanly.

**Tech Stack:** Jetpack Compose, Hilt, ViewModel, StateFlow, Room (VaultDao from Phase 1), `SharedPreferences` (existing pattern)

**Codebase orientation (confirmed by reading):**
- Navigation lives in `ConversationScreen.kt` as `private enum class Page { CHAT, SESSIONS, NODES }` at line 56
- `ConversationRoot` composable (line 58–83) owns `var page` and `BackHandler`
- Top bar Hub icon: `IconButton(onClick = onOpenNodes) { Icon(Icons.Default.Hub, ...) }` at line 191
- `ConversationScreen` signature has `onOpenNodes: () -> Unit` parameter at line 153
- ViewModel pattern: `@HiltViewModel`, `@Inject constructor`, `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ...)`
- State collection: `by viewModel.xxx.collectAsState()` throughout (NOT `collectAsStateWithLifecycle`)
- `ConversationEntity` gains `mode: String = "default"` in Phase 1 (MIGRATION_6_7)
- `VaultEntity` fields from Phase 1: `id, name, localPath, isEnabled, createdAt`
- `VaultRegistry` API: `observeAll(): Flow<List<VaultEntity>>`, `addVault(name): VaultEntity`, `setEnabled(vaultId, enabled)`, `delete(vaultId)`
- `VaultSettings` API: `getRemoteUrl(vaultId)`, `setRemoteUrl(vaultId, url)`, `getPat(vaultId)`, `setPat(vaultId, pat)`, `isConfiguredForSync(vaultId)`
- `VaultGitSync` constructor takes `VaultSettings`; sync methods take `(vaultId: String, vaultPath: File)`
- `SharedPreferences` name for API key: `"amplifier_prefs"`, key: `"anthropic_api_key"`
- DB is version 7 after Phase 1

---

## Task 1: SettingsViewModel

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsViewModel.kt`

---

**WHAT**

A Hilt ViewModel that exposes API key, model selection, and vault list as StateFlows, with methods to update each. Used by both `SettingsScreen` and `VaultDetailScreen`.

---

**HOW**

Create `app/src/main/kotlin/com/vela/app/ui/settings/SettingsViewModel.kt`:

```kotlin
package com.vela.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultGitSync
import com.vela.app.vault.VaultRegistry
import com.vela.app.vault.VaultSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val vaultRegistry: VaultRegistry,
    private val vaultSettings: VaultSettings,
    private val vaultGitSync: VaultGitSync,
) : ViewModel() {

    companion object {
        private const val PREFS         = "amplifier_prefs"
        private const val KEY_API_KEY   = "anthropic_api_key"
        private const val KEY_MODEL     = "selected_model"
        const val DEFAULT_MODEL         = "claude-sonnet-4-6"

        val AVAILABLE_MODELS = listOf(
            "claude-sonnet-4-6",
            "claude-opus-4-5",
            "claude-haiku-4-5",
        )
    }

    private val prefs get() = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(prefs.getString(KEY_API_KEY, "").orEmpty())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow(
        prefs.getString(KEY_MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL }
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    val vaults: StateFlow<List<VaultEntity>> = vaultRegistry.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncMessage = MutableStateFlow<String?>(null)
    val syncMessage: StateFlow<String?> = _syncMessage.asStateFlow()

    // ── API key ──────────────────────────────────────────────────────────────

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
        _apiKey.value = key
    }

    // ── Model ────────────────────────────────────────────────────────────────

    fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
        _selectedModel.value = model
    }

    // ── Vaults ───────────────────────────────────────────────────────────────

    fun addVault(name: String, remoteUrl: String = "", pat: String = "") {
        viewModelScope.launch {
            val entity = vaultRegistry.addVault(name)
            if (remoteUrl.isNotBlank()) {
                vaultSettings.setRemoteUrl(entity.id, remoteUrl)
                if (pat.isNotBlank()) vaultSettings.setPat(entity.id, pat)
            }
        }
    }

    fun deleteVault(vaultId: String) {
        viewModelScope.launch { vaultRegistry.delete(vaultId) }
    }

    fun setVaultEnabled(vaultId: String, enabled: Boolean) {
        viewModelScope.launch { vaultRegistry.setEnabled(vaultId, enabled) }
    }

    fun setVaultRemote(vaultId: String, remoteUrl: String, pat: String) {
        vaultSettings.setRemoteUrl(vaultId, remoteUrl)
        if (pat.isNotBlank()) vaultSettings.setPat(vaultId, pat)
    }

    fun getVaultRemoteUrl(vaultId: String): String = vaultSettings.getRemoteUrl(vaultId)

    fun isVaultSyncConfigured(vaultId: String): Boolean = vaultSettings.isConfiguredForSync(vaultId)

    fun syncVault(vaultId: String) {
        val vault = vaults.value.firstOrNull { it.id == vaultId } ?: return
        val vaultPath = File(vault.localPath)
        viewModelScope.launch {
            _syncMessage.value = "Syncing…"
            val pullResult = vaultGitSync.pull(vaultId, vaultPath)
            val pushResult = vaultGitSync.push(vaultId, vaultPath)
            _syncMessage.value = "Pull: $pullResult | Push: $pushResult"
        }
    }

    fun clearSyncMessage() { _syncMessage.value = null }
}
```

**Note:** `VaultRegistry`, `VaultSettings`, and `VaultGitSync` are all `@Singleton` provided in `AppModule` by Phase 1. Hilt will inject them automatically — no `AppModule` changes needed for this task.

---

**PROOF**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL — SettingsViewModel compiles. If Hilt complains about a missing binding for `VaultGitSync` or `VaultSettings`, verify Phase 1's AppModule additions are in place (look for `provideVaultGitSync` and `provideVaultSettings` in `AppModule.kt`).

**Commit:** `feat(settings): add SettingsViewModel with vault and API key state`

---

## Task 2: Extend Page enum and wire Settings navigation

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

---

**WHAT**

`Page.SETTINGS` and `Page.VAULT_DETAIL` are added to the enum. The Hub icon in `ConversationScreen`'s top bar is replaced with a Settings gear icon. `ConversationRoot` routes the new pages and adds `prevPage` tracking so SETTINGS → NODES back navigation returns to Settings rather than Chat.

---

**HOW**

**Step 1:** In `ConversationScreen.kt`, replace the `Page` enum at line 56:

```kotlin
// OLD:
private enum class Page { CHAT, SESSIONS, NODES }

// NEW:
private enum class Page { CHAT, SESSIONS, NODES, SETTINGS, VAULT_DETAIL }
```

**Step 2:** In `ConversationRoot`, replace the existing function body. The current function is lines 58–83. Replace it with:

```kotlin
@Composable
fun ConversationRoot(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
) {
    var page         by remember { mutableStateOf(Page.CHAT) }
    var prevPage     by remember { mutableStateOf(Page.CHAT) }
    var detailVaultId by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = page != Page.CHAT) {
        val dest = prevPage
        prevPage = Page.CHAT
        page = dest
    }

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            if (targetState != Page.CHAT) {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it / 3 } + fadeOut()
            } else {
                slideInHorizontally { -it / 3 } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            }
        },
        label = "page-swap",
    ) { p ->
        when (p) {
            Page.CHAT     -> ConversationScreen(
                speechTranscriber,
                viewModel,
                onOpenSessions = { prevPage = page; page = Page.SESSIONS },
                onOpenSettings = { prevPage = page; page = Page.SETTINGS },
            )
            Page.SESSIONS -> SessionsPage(
                viewModel,
                onBack   = { prevPage = Page.CHAT; page = Page.CHAT },
                onSelect = { prevPage = Page.CHAT; page = Page.CHAT },
            )
            Page.NODES    -> NodesScreen(onBack = {
                val dest = prevPage; prevPage = Page.CHAT; page = dest
            })
            Page.SETTINGS -> SettingsScreen(
                onNavigateBack    = { prevPage = Page.CHAT; page = Page.CHAT },
                onNavigateToNodes = { prevPage = page; page = Page.NODES },
                onNavigateToVaultDetail = { vaultId ->
                    detailVaultId = vaultId
                    prevPage = page
                    page = Page.VAULT_DETAIL
                },
            )
            Page.VAULT_DETAIL -> {
                val vaultId = detailVaultId
                if (vaultId != null) {
                    VaultDetailScreen(
                        vaultId        = vaultId,
                        onNavigateBack = { val dest = prevPage; prevPage = Page.CHAT; page = dest },
                    )
                }
            }
        }
    }
}
```

**Step 3:** In `ConversationScreen` composable (line 149), replace the `onOpenNodes` parameter with `onOpenSettings`:

```kotlin
// OLD:
@Composable
fun ConversationScreen(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
    onOpenSessions: () -> Unit = {},
    onOpenNodes:    () -> Unit = {},
) {

// NEW:
@Composable
fun ConversationScreen(
    speechTranscriber: SpeechTranscriber? = null,
    viewModel: ConversationViewModel = hiltViewModel(),
    onOpenSessions: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
```

**Step 4:** In `ConversationScreen`'s `CenterAlignedTopAppBar` actions block (lines 190–193), replace the Hub icon with a Settings gear icon:

```kotlin
// OLD:
actions = {
    IconButton(onClick = onOpenNodes) { Icon(Icons.Default.Hub, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    IconButton(onClick = { viewModel.newSession() }) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }
},

// NEW:
actions = {
    IconButton(onClick = onOpenSettings) { Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    IconButton(onClick = { viewModel.newSession() }) { Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary) }
},
```

**Step 5:** Add `Icons.Default.Settings` to the import block at the top of `ConversationScreen.kt`. The existing imports already have `import androidx.compose.material.icons.filled.Add` etc. — add alongside them:

```kotlin
import androidx.compose.material.icons.filled.Settings
```

**Step 6:** Add `SettingsScreen` and `VaultDetailScreen` imports (these will exist after Tasks 3–7):

```kotlin
import com.vela.app.ui.settings.SettingsScreen
import com.vela.app.ui.settings.VaultDetailScreen
```

> **Note:** The `SettingsScreen` and `VaultDetailScreen` composables don't exist yet — the build will fail until Tasks 3–7 are complete. That's expected. Complete Tasks 1–7 in order before running a build.

---

**PROOF**

After completing all tasks through Task 7: Build + run. Tap the gear icon in the conversation top bar → `SettingsScreen` opens with slide-in animation. Tap back arrow → returns to Chat. Navigate Settings → Nodes → tap back → returns to Settings (not Chat). Tap back again → returns to Chat.

**Commit:** `feat(nav): add SETTINGS and VAULT_DETAIL pages, replace Hub icon with Settings gear`

---

## Task 3: SettingsScreen skeleton with section headers

**Files:**
- Create: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt`

---

**WHAT**

`SettingsScreen` exists as a composable with a `TopAppBar` with back navigation and a `LazyColumn` with three section headers: "AI", "Connections", "Vaults".

---

**HOW**

Create `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt`:

```kotlin
package com.vela.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.vela.app.ui.nodes.NodesViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToNodes: () -> Unit,
    onNavigateToVaultDetail: (vaultId: String) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text("Settings", style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad),
        ) {
            item { SectionHeader("AI") }
            // AI section items inserted in Task 3 step
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            item { SectionHeader("Connections") }
            // Connections section items inserted in Task 4
            item { HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp)) }

            item { SectionHeader("Vaults") }
            // Vaults section items inserted in Task 5
        }
    }
}

@Composable
internal fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}
```

---

**PROOF**

Build + run. Tap gear icon → SettingsScreen opens showing "AI", "Connections", "Vaults" section headers with dividers. Tap back arrow → returns to conversation.

**Commit:** `feat(settings): add SettingsScreen skeleton with section headers`

---

## Task 4: Settings — AI section (API key + model picker)

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt`

---

**WHAT**

The AI section shows the current API key (masked to last 4 chars) and current model. Tapping "Edit" on the API key opens a dialog. Tapping the model row opens a radio-button picker dialog. Both selections persist across restarts.

---

**HOW**

Add the AI section items to `SettingsScreen.kt`. Insert them as `item { }` blocks inside the `LazyColumn`, immediately after the `SectionHeader("AI")` item. Replace the `// AI section items inserted in Task 3 step` comment:

```kotlin
            item { AiSection(viewModel) }
```

Add the `AiSection` composable at the bottom of `SettingsScreen.kt`:

```kotlin
@Composable
private fun AiSection(viewModel: SettingsViewModel) {
    val apiKey        by viewModel.apiKey.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()

    var showApiKeyDialog  by remember { mutableStateOf(false) }
    var showModelPicker   by remember { mutableStateOf(false) }

    val maskedKey = when {
        apiKey.isBlank()   -> "(not set)"
        apiKey.length > 4  -> "sk-ant-…${apiKey.takeLast(4)}"
        else               -> "sk-ant-…"
    }

    ListItem(
        headlineContent   = { Text("Anthropic API Key") },
        supportingContent = { Text(maskedKey, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent   = {
            TextButton(onClick = { showApiKeyDialog = true }) { Text("Edit") }
        },
    )
    ListItem(
        headlineContent   = { Text("Model") },
        supportingContent = {
            Text(
                selectedModel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        modifier = Modifier.clickable { showModelPicker = true },
    )

    if (showApiKeyDialog) {
        ApiKeyEditDialog(
            currentKey = apiKey,
            onDismiss  = { showApiKeyDialog = false },
            onConfirm  = { key -> viewModel.setApiKey(key); showApiKeyDialog = false },
        )
    }

    if (showModelPicker) {
        ModelPickerDialog(
            currentModel = selectedModel,
            onDismiss    = { showModelPicker = false },
            onSelect     = { model -> viewModel.setModel(model); showModelPicker = false },
        )
    }
}

@Composable
private fun ApiKeyEditDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var key by remember { mutableStateOf(currentKey) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Anthropic API Key") },
        text  = {
            OutlinedTextField(
                value         = key,
                onValueChange = { key = it },
                label         = { Text("sk-ant-…") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            Button(
                onClick  = { if (key.isNotBlank()) onConfirm(key.trim()) },
                enabled  = key.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun ModelPickerDialog(
    currentModel: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text  = {
            Column {
                SettingsViewModel.AVAILABLE_MODELS.forEach { model ->
                    Row(
                        modifier             = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(model) }
                            .padding(vertical = 4.dp),
                        verticalAlignment    = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected  = model == currentModel,
                            onClick   = { onSelect(model) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(model, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
```

**Additional imports needed** at the top of `SettingsScreen.kt`:

```kotlin
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
```

---

**PROOF**

Build + run. Settings → AI section shows masked key and current model. Tap "Edit" → dialog with text field opens → type a key → Save → masked display updates immediately. Tap model row → picker with radio buttons shows three options → select one → model name updates. Force-close and reopen app → selections are preserved.

**Commit:** `feat(settings): add AI section with API key editor and model picker`

---

## Task 5: Settings — Connections section (SSH nodes inline)

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt`

---

**WHAT**

The Connections section of SettingsScreen shows the SSH nodes list inline, without a nested Scaffold. All existing node functionality (add, delete, copy key) works correctly. The top-bar Hub icon is already gone from Task 2.

---

**HOW**

Replace the `// Connections section items inserted in Task 4` comment with:

```kotlin
            item { ConnectionsSection(onNavigateToNodes = onNavigateToNodes) }
```

Add the `ConnectionsSection` composable at the bottom of `SettingsScreen.kt`. This uses `hiltViewModel<NodesViewModel>()` to get the same ViewModel instance that `NodesScreen` would use (Hilt scopes it to the composable's lifecycle), and renders the nodes list content without a Scaffold wrapper:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionsSection(
    onNavigateToNodes: () -> Unit,
    nodesViewModel: com.vela.app.ui.nodes.NodesViewModel = hiltViewModel(),
) {
    val nodes    by nodesViewModel.nodes.collectAsState()
    val addError by nodesViewModel.addError.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    if (showAddSheet) {
        com.vela.app.ui.nodes.AddNodeSheet(
            onDismiss = { showAddSheet = false; nodesViewModel.clearError() },
            onAdd     = { label, host, port, user ->
                nodesViewModel.addNode(label, host, port, user)
                showAddSheet = false
            },
            error = addError,
        )
    }

    // Device key card
    com.vela.app.ui.nodes.DeviceKeyCard(
        publicKey = nodesViewModel.publicKey,
        context   = context,
    )

    // Section label + add button row
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment     = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(
            "Connected Nodes",
            style  = MaterialTheme.typography.titleSmall,
            color  = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilledTonalIconButton(onClick = { showAddSheet = true }) {
            Icon(Icons.Default.Add, "Add node")
        }
    }

    if (nodes.isEmpty()) {
        Text(
            "No nodes yet.\nAdd one and paste the key above into ~/.ssh/authorized_keys on the remote machine.",
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    } else {
        nodes.forEach { node ->
            com.vela.app.ui.nodes.NodeCard(
                node         = node,
                onDelete     = { nodesViewModel.removeNode(node.id) },
                onAddHost    = { newHost -> nodesViewModel.addHostToNode(node.id, newHost) },
                onRemoveHost = { host -> nodesViewModel.removeHostFromNode(node.id, host) },
            )
        }
    }
}
```

**Important:** `DeviceKeyCard`, `AddNodeSheet`, and `NodeCard` are currently `private` composables in `NodesScreen.kt`. Change them from `private` to `internal` so they can be used in `ConnectionsSection`:

In `NodesScreen.kt`, find and update the visibility of these three composables:
```kotlin
// Change:
private fun DeviceKeyCard(...)
// to:
internal fun DeviceKeyCard(...)

// Change:
private fun AddNodeSheet(...)
// to:
internal fun AddNodeSheet(...)

// Change:
private fun NodeCard(...)
// to:
internal fun NodeCard(...)
```

**Additional imports** needed in `SettingsScreen.kt`:

```kotlin
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
```

---

**PROOF**

Build + run. Top bar no longer has a Hub/Connections icon. Settings → Connections section shows the device key card and nodes list. Tap "+" → AddNodeSheet opens → fill in details → node appears in list. All existing SSH node functionality (copy key, delete node, add host) still works.

**Commit:** `feat(settings): embed SSH nodes in Connections section, remove top-bar Hub icon`

---

## Task 6: Settings — Vaults section (list + enable toggle)

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt`

---

**WHAT**

The Vaults section shows the list of configured vaults. Each vault row has its name, a sync-status label ("Synced" / "Local only"), an enable/disable Switch, and is tappable to navigate to `VaultDetailScreen`. An "Add Vault" button triggers `AddVaultSheet`. Empty state shows "No vaults yet."

---

**HOW**

Replace the `// Vaults section items inserted in Task 5` comment with:

```kotlin
            item {
                VaultsSection(
                    viewModel               = viewModel,
                    onNavigateToVaultDetail = onNavigateToVaultDetail,
                )
            }
```

Add the `VaultsSection` composable and `AddVaultSheet` at the bottom of `SettingsScreen.kt`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultsSection(
    viewModel: SettingsViewModel,
    onNavigateToVaultDetail: (vaultId: String) -> Unit,
) {
    val vaults by viewModel.vaults.collectAsState()
    var showAddSheet by remember { mutableStateOf(false) }

    if (showAddSheet) {
        AddVaultSheet(
            onDismiss = { showAddSheet = false },
            onConfirm = { name, remoteUrl, pat ->
                viewModel.addVault(name, remoteUrl, pat)
                showAddSheet = false
            },
        )
    }

    if (vaults.isEmpty()) {
        Text(
            "No vaults yet.",
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    } else {
        vaults.forEach { vault ->
            ListItem(
                headlineContent   = { Text(vault.name) },
                supportingContent = {
                    Text(
                        if (viewModel.isVaultSyncConfigured(vault.id)) "Synced" else "Local only",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingContent   = {
                    Switch(
                        checked         = vault.isEnabled,
                        onCheckedChange = { viewModel.setVaultEnabled(vault.id, it) },
                    )
                },
                modifier = Modifier.clickable { onNavigateToVaultDetail(vault.id) },
            )
        }
    }

    OutlinedButton(
        onClick  = { showAddSheet = true },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Add Vault")
    }
}
```

**Additional imports** needed:

```kotlin
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
```

---

**PROOF**

Build + run. Settings → Vaults section shows "No vaults yet." and "Add Vault" button. After Task 7's `AddVaultSheet`, the sheet opens and vault creation works. Toggle switch persists across restarts.

**Commit:** `feat(settings): add Vaults section with list, enable toggle, and Add Vault button`

---

## Task 7: AddVaultSheet and VaultDetailScreen

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt` (add `AddVaultSheet`)
- Create: `app/src/main/kotlin/com/vela/app/ui/settings/VaultDetailScreen.kt`

---

**WHAT**

`AddVaultSheet` is a `ModalBottomSheet` with a required name field and optional GitHub sync fields. `VaultDetailScreen` is a full-screen composable for editing vault config, triggering sync, and deleting vaults.

---

**HOW — AddVaultSheet**

Add `AddVaultSheet` at the bottom of `SettingsScreen.kt`:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddVaultSheet(
    onDismiss: () -> Unit,
    onConfirm: (name: String, remoteUrl: String, pat: String) -> Unit,
) {
    var name       by remember { mutableStateOf("") }
    var remoteUrl  by remember { mutableStateOf("") }
    var pat        by remember { mutableStateOf("") }
    var showGitHub by remember { mutableStateOf(false) }
    var nameError  by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            Text("Add Vault", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value           = name,
                onValueChange   = { name = it; nameError = false },
                label           = { Text("Vault name") },
                isError         = nameError,
                supportingText  = if (nameError) {{ Text("Name is required") }} else null,
                singleLine      = true,
                modifier        = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            TextButton(onClick = { showGitHub = !showGitHub }) {
                Text(if (showGitHub) "Hide GitHub sync" else "Connect GitHub (optional)")
            }

            if (showGitHub) {
                OutlinedTextField(
                    value         = remoteUrl,
                    onValueChange = { remoteUrl = it },
                    label         = { Text("GitHub repo URL") },
                    placeholder   = { Text("https://github.com/user/vault.git") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value                  = pat,
                    onValueChange          = { pat = it },
                    label                  = { Text("Personal Access Token") },
                    visualTransformation   = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine             = true,
                    modifier               = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                Spacer(Modifier.width(8.dp))
                Button(onClick = {
                    if (name.isBlank()) { nameError = true; return@Button }
                    onConfirm(name.trim(), remoteUrl.trim(), pat.trim())
                }) { Text("Create Vault") }
            }
        }
    }
}
```

**Additional imports** needed in `SettingsScreen.kt`:

```kotlin
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.material3.ModalBottomSheet
```

---

**HOW — VaultDetailScreen**

Create `app/src/main/kotlin/com/vela/app/ui/settings/VaultDetailScreen.kt`:

```kotlin
package com.vela.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultDetailScreen(
    vaultId: String,
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val vaults     by viewModel.vaults.collectAsState()
    val syncMessage by viewModel.syncMessage.collectAsState()
    val vault = vaults.firstOrNull { it.id == vaultId }

    // Vault deleted while screen is open — pop back
    if (vault == null) {
        LaunchedEffect(Unit) { onNavigateBack() }
        return
    }

    var remoteUrl          by remember(vaultId) { mutableStateOf(viewModel.getVaultRemoteUrl(vaultId)) }
    var pat                by remember { mutableStateOf("") }   // never pre-filled for security
    var showDeleteConfirm  by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = { Text(vault.name, style = MaterialTheme.typography.titleLarge) },
            )
        },
    ) { pad ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value         = remoteUrl,
                onValueChange = { remoteUrl = it },
                label         = { Text("GitHub remote URL") },
                placeholder   = { Text("https://github.com/user/vault.git") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value                  = pat,
                onValueChange          = { pat = it },
                label                  = { Text("Personal Access Token (leave blank to keep existing)") },
                visualTransformation   = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                singleLine             = true,
                modifier               = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(8.dp))

            Button(
                onClick  = { viewModel.setVaultRemote(vaultId, remoteUrl.trim(), pat.trim()) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save Changes") }

            OutlinedButton(
                onClick  = { viewModel.syncVault(vaultId) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Sync Now") }

            syncMessage?.let { msg ->
                Text(
                    text     = msg,
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.weight(1f))

            OutlinedButton(
                onClick  = { showDeleteConfirm = true },
                colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Delete Vault") }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title   = { Text("Delete vault?") },
            text    = { Text("This permanently deletes the local vault files and all contents. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.deleteVault(vaultId); onNavigateBack() },
                    colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
```

---

**PROOF**

Build + run. Full flow:
1. Settings → "Add Vault" → type a name → "Create Vault" → sheet closes → vault appears in list
2. Tap vault row → `VaultDetailScreen` opens showing vault name in title
3. "Sync Now" → check Logcat for `VaultGitSync` pull/push calls; sync message appears below button
4. "Delete" → confirmation dialog appears → confirm → returns to Settings → vault removed from list
5. Empty name in AddVaultSheet → "Name is required" error shown, sheet stays open

**Commit:** `feat(settings): add AddVaultSheet and VaultDetailScreen`

---

## Task 8: ConversationViewModel — per-session vault state

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt`

---

**WHAT**

`ConversationViewModel` tracks which vaults are toggled active for the current session (ephemeral — not persisted). `newVaultSession()` creates a vault-mode conversation. `allVaults` exposes enabled vaults. When the active conversation changes, `sessionActiveVaultIds` resets to all currently-enabled vault IDs.

---

**HOW**

**Step 1:** Add `VaultRegistry` to the constructor and new state fields. The current constructor is at line 22–27. Replace it:

```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ConversationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val inferenceEngine: InferenceEngine,
    private val conversationDao: ConversationDao,
    private val turnDao: TurnDao,
    private val vaultRegistry: VaultRegistry,
) : ViewModel() {
```

**Step 2:** Add import at the top of the file:

```kotlin
import com.vela.app.data.db.VaultEntity
import com.vela.app.vault.VaultRegistry
```

**Step 3:** Add the vault-related StateFlows after the existing `isConfigured` property (after line 68):

```kotlin
    // ── Vault state ──────────────────────────────────────────────────────────

    /** All enabled vaults — shown as chips in ConversationScreen. */
    val allVaults: StateFlow<List<VaultEntity>> = vaultRegistry.observeAll()
        .map { it.filter { v -> v.isEnabled } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _sessionActiveVaultIds = MutableStateFlow<Set<String>>(emptySet())
    val sessionActiveVaultIds: StateFlow<Set<String>> = _sessionActiveVaultIds.asStateFlow()
```

**Step 4:** In the `init` block (after line 81), add a new `viewModelScope.launch` after the existing two launches:

```kotlin
        // Reset session vault selection when active conversation changes.
        // Also fires on init once _activeConvId gets its first value.
        viewModelScope.launch {
            _activeConvId.filterNotNull().collect {
                // Default: all currently-enabled vaults are selected for the new session.
                _sessionActiveVaultIds.value = allVaults.value.map { v -> v.id }.toSet()
            }
        }
```

**Step 5:** Add `toggleVaultForSession` and `newVaultSession` after the existing `deleteSession` function (after line 113):

```kotlin
    fun toggleVaultForSession(vaultId: String) {
        _sessionActiveVaultIds.update { current ->
            if (vaultId in current) current - vaultId else current + vaultId
        }
    }

    fun newVaultSession() {
        viewModelScope.launch {
            val conv = ConversationEntity(
                id        = UUID.randomUUID().toString(),
                title     = "Vault Session",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                mode      = "vault",
            )
            conversationDao.insert(conv)
            _activeConvId.value = conv.id
            prefs.edit().putString(KEY_ACTIVE_ID, conv.id).apply()
        }
    }
```

**Step 6:** Add the missing import for `update` (it's an extension on `MutableStateFlow`):

```kotlin
import kotlinx.coroutines.flow.update
```

---

**PROOF**

Run: `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL. Then manually:
1. Add a vault in Settings → enable it
2. Open any conversation → vault chips row (from Task 9) will show the vault chip selected
3. Tap chip → deselects (set changes to empty)
4. Navigate to a different conversation → chip resets to selected state

**Commit:** `feat(conversation): add vault session state and newVaultSession() to ConversationViewModel`

---

## Task 9: ConversationScreen — vault chips row

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

---

**WHAT**

A horizontally-scrollable `LazyRow` of `FilterChip`s appears below the top bar in `ConversationScreen`. Each chip represents an enabled vault. Selected chips (active for this session) are filled; unselected are outlined. The row is hidden when no vaults are configured.

---

**HOW**

**Step 1:** Add vault state observation at the top of `ConversationScreen`'s body, alongside the existing state collections (after line 159):

```kotlin
    val allVaults           by viewModel.allVaults.collectAsState()
    val sessionActiveVaultIds by viewModel.sessionActiveVaultIds.collectAsState()
```

**Step 2:** The current Scaffold content (lines 203–226) uses `{ pad ->` and puts the content directly. Wrap the content in a `Column` to make room for the chips row above the messages. Replace the current content lambda:

```kotlin
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // Vault chips row — only shown when enabled vaults exist
            if (allVaults.isNotEmpty()) {
                LazyRow(
                    modifier              = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(allVaults, key = { it.id }) { vault ->
                        FilterChip(
                            selected = vault.id in sessionActiveVaultIds,
                            onClick  = { viewModel.toggleVaultForSession(vault.id) },
                            label    = {
                                Text(
                                    vault.name,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                        )
                    }
                }
            }

            // Existing content — moved inside Column, pad is now on Column not here
            if (turnsWithEvents.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Start a conversation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state               = listState,
                    modifier            = Modifier.fillMaxSize(),
                    contentPadding      = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    items(turnsWithEvents, key = { it.turn.id }) { twe ->
                        TurnRow(
                            twe           = twe,
                            streamingText = streamingTextMap[twe.turn.id],
                            isLive        = twe.turn.id == activeTurnId,
                        )
                    }
                }
            }
        }
    }
```

**Step 3:** Add required imports to `ConversationScreen.kt`:

```kotlin
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.FilterChip
import com.vela.app.data.db.VaultEntity
```

---

**PROOF**

Build + run.
1. No vaults configured: chips row is invisible — conversation looks identical to before
2. Add a vault in Settings → enable it → return to any conversation → chips row appears with the vault's name chip, selected (filled)
3. Tap chip → chip becomes outlined (deselected)
4. Tap again → chip becomes filled (selected)
5. Navigate to a different conversation (via SESSIONS page) → chip state resets to selected

**Commit:** `feat(conversation): add vault chips row below top bar in ConversationScreen`

---

## Task 10: "New Vault Session" entry point

**Files:**
- Modify: `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt`

---

**WHAT**

Users can start a vault-mode conversation. A long-press (dropdown menu) on the existing "+" button in `ConversationScreen`'s top bar offers "New Chat" (existing behavior) and "New Vault Session" as options.

---

**HOW**

The existing actions block in `CenterAlignedTopAppBar` has `IconButton(onClick = { viewModel.newSession() }) { Icon(Icons.Default.Add, ...) }`. Replace it with a dropdown menu version:

```kotlin
// Replace the existing Add IconButton in the topBar actions block:
actions = {
    IconButton(onClick = onOpenSettings) {
        Icon(Icons.Default.Settings, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // New: expandable add menu
    Box {
        var showMenu by remember { mutableStateOf(false) }
        IconButton(onClick = { showMenu = true }) {
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
        }
        DropdownMenu(
            expanded         = showMenu,
            onDismissRequest = { showMenu = false },
        ) {
            DropdownMenuItem(
                text    = { Text("New Chat") },
                onClick = { viewModel.newSession(); showMenu = false },
            )
            DropdownMenuItem(
                text    = { Text("New Vault Session") },
                onClick = { viewModel.newVaultSession(); showMenu = false },
            )
        }
    }
},
```

**Step 2:** Add imports:

```kotlin
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
```

---

**PROOF**

Build + run. Tap "+" button → dropdown menu appears with "New Chat" and "New Vault Session" options. Tap "New Chat" → new conversation created (same as before). Tap "New Vault Session" → new conversation titled "Vault Session" created and focused. Check Logcat for harness initialization (Phase 2) on first message in the vault session.

**Commit:** `feat(conversation): add New Vault Session entry point via Add button dropdown`

---

## Task 11: Full build + smoke verification

**Files:** None (verification only)

---

**WHAT**

The complete app (Phases 1 + 2 + 3) builds cleanly, unit tests pass, and all Settings + vault chip UI flows work end-to-end.

---

**HOW**

**Step 1:** Full build + unit tests:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

**Step 2:** If Hilt DI errors appear at runtime (app crashes on Settings open), check that `AppModule.kt` has providers for `VaultSettings` and `VaultGitSync`. From Phase 1 these should be:

```kotlin
@Provides @Singleton
fun provideVaultSettings(@ApplicationContext ctx: Context): VaultSettings =
    SharedPrefsVaultSettings(ctx)

@Provides @Singleton
fun provideVaultGitSync(vaultSettings: VaultSettings): VaultGitSync =
    VaultGitSync(vaultSettings)
```

If they're missing, add them.

**Step 3:** Manual smoke checklist — run through each item in order on a physical device or emulator:

| # | Action | Expected |
|---|--------|----------|
| 1 | Launch app | Conversation screen loads; top bar shows ChatBubble, title, Settings gear, "+" |
| 2 | Top bar: Hub/Connections icon | **Not visible** (was removed in Task 2) |
| 3 | Tap gear icon | Settings screen slides in |
| 4 | Settings: scroll down | Three sections visible: AI, Connections, Vaults |
| 5 | Settings → AI: tap "Edit" on API key | Dialog opens; type a key; Save; masked display updates |
| 6 | Settings → AI: tap model row | Picker with 3 radio options; select one; model name updates |
| 7 | Force-close and reopen | API key and model still show the values just set |
| 8 | Settings → Connections | Device key card and SSH nodes list visible; "+" adds a node; all node actions work |
| 9 | Settings → tap back | Returns to Chat |
| 10 | Settings → Connections → tap "+" → add node | Node appears inline (no full-screen navigation) |
| 11 | Settings → Vaults | "No vaults yet." and "Add Vault" button visible |
| 12 | Tap "Add Vault" | ModalBottomSheet appears |
| 13 | Submit blank name | "Name is required" error; sheet stays open |
| 14 | Fill name → "Create Vault" | Sheet closes; vault appears in Vaults list |
| 15 | Toggle vault enable switch | Switch state changes; persists after restart |
| 16 | Tap vault row | VaultDetailScreen opens |
| 17 | VaultDetailScreen: tap "Sync Now" | Check Logcat for VaultGitSync pull/push; sync message appears |
| 18 | VaultDetailScreen: tap "Delete" | Confirmation dialog; confirm → back to Settings; vault removed |
| 19 | Return to Chat | Vault chips row visible below top bar with vault chip (filled/selected) |
| 20 | Tap vault chip | Chip becomes outlined (deselected) |
| 21 | Navigate to different conversation | Chip resets to selected state |
| 22 | Tap "+" button | Dropdown with "New Chat" and "New Vault Session" |
| 23 | Tap "New Vault Session" | New conversation titled "Vault Session" created and focused |

---

**PROOF**

```
./gradlew :app:assembleDebug   → BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest  → X tests, 0 failures
```

Manual checklist: all 23 items pass.

**Commit:** `chore: Phase 3 complete — Settings, vault chips, and vault session entry point`

---

## Appendix: File inventory

| Status | File |
|--------|------|
| **Create** | `app/src/main/kotlin/com/vela/app/ui/settings/SettingsViewModel.kt` |
| **Create** | `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt` |
| **Create** | `app/src/main/kotlin/com/vela/app/ui/settings/VaultDetailScreen.kt` |
| **Modify** | `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationScreen.kt` |
| **Modify** | `app/src/main/kotlin/com/vela/app/ui/conversation/ConversationViewModel.kt` |
| **Modify** | `app/src/main/kotlin/com/vela/app/ui/nodes/NodesScreen.kt` (visibility: `private` → `internal` on 3 composables) |

No new Room migrations. No new AppModule entries (all Phase 3 Hilt bindings come from Phase 1 providers).
