# Vault, Skills, Hooks & Settings Design

## Goal

Add a Vault system, Skills layer, Hooks layer, and Settings UI to Vela so the app can run the lifeos personal life management system natively on Android.

## Background

The lifeos personal life management system runs on desktop Amplifier today. It depends on a well-defined tool surface (`read_file`, `write_file`, `edit_file`, `glob`, `grep`, `bash`, `load_skill`, `todo`) and a set of lifecycle behaviours (session init, vault sync, personalization injection) that the Amplifier harness provides. Vela currently has none of these. To run lifeos on Android, Vela must implement the same tool surface and harness mechanics — natively in Kotlin, without the AI or the protocol noticing any difference.

## Approach

Implement each lifeos tool natively in Kotlin, using Java standard library and JGit for everything the AI would normally delegate to a shell. Keep the exact same tool names (`bash`, `read_file`, etc.) so the lifeos SYSTEM.md prompts work without modification. Build a harness layer that fires lifecycle hooks, loads vault system prompts, and injects personalization before the first turn. Implement the Agent Skills specification so lifeos skill invocations work identically to how they work on desktop.

Multi-vault file routing is left to lifeos SYSTEM.md — the AI's domain judgment handles which vault to write to. File tools validate only that a requested path falls within a known active vault root. Local-first: vaults start on device, GitHub sync is optional. Tools are always on — modularity is a code/DI concern, not a user-facing toggle.

## Architecture

Five layers stack from bottom to top. Nothing above depends on implementation details of what is below — just interfaces.

```
┌─────────────────────────────────────────────────────────┐
│  5 — Harness      SessionHarness                        │
├─────────────────────────────────────────────────────────┤
│  4 — Hooks        HookRegistry, built-in hooks          │
├─────────────────────────────────────────────────────────┤
│  3 — Skills       SkillsEngine, LoadSkillTool           │
├─────────────────────────────────────────────────────────┤
│  2 — Vault        VaultRegistry, VaultManager,          │
│                   VaultGitSync, VaultSettings           │
├─────────────────────────────────────────────────────────┤
│  1 — Tools        ReadFileTool, WriteFileTool,          │
│                   EditFileTool, GlobTool, GrepTool,     │
│                   BashTool, LoadSkillTool, TodoTool     │
└─────────────────────────────────────────────────────────┘
```

## Components

### Layer 1 — Tool Layer (`com.vela.app.ai.tools`)

Kotlin `Tool` implementations, registered in `ToolRegistry`. Always on — the AI sees them all. No user-facing toggle.

`ToolParameter` gains a `required: Boolean = true` field. `AmplifierSession.buildToolsJson()` is updated to only include required params in the Anthropic `required` array, allowing optional params (`offset`, `limit`, etc.) to be omitted by the AI without validation errors.

| Tool | Signature | Behaviour |
|------|-----------|-----------|
| `ReadFileTool` | `read_file(file_path, offset?, limit?)` | cat -n formatted output; directory path → listing; honours offset/limit |
| `WriteFileTool` | `write_file(file_path, content)` | Create/overwrite; `parentFile.mkdirs()` automatically |
| `EditFileTool` | `edit_file(file_path, old_string, new_string, replace_all?)` | Exact string match; error if not found or not unique; `replace_all` renames every occurrence |
| `GlobTool` | `glob(pattern, path?, type?)` | `nio.PathMatcher`; excludes `.git`; limit 500 results |
| `GrepTool` | `grep(pattern, path?, output_mode?, -i?, -n?, -A?, -B?, -C?)` | `java.util.regex.Pattern` over file tree; three output modes: `files_with_matches`, `content`, `count` |
| `BashTool` | `bash(command)` | Command router (see BashTool routing table below) |
| `LoadSkillTool` | `load_skill(list?, search?, info?, skill_name?)` | Agent Skills spec implementation; delegates to `SkillsEngine` |
| `TodoTool` | `todo(action, todos?)` | In-memory create/update/list; parses `todos` as JSON array |

#### BashTool Command Routing Table

`bash` keeps its name for lifeos system prompt compatibility. Internally it parses the command string and routes to JGit or Java File API. Unsupported commands return a friendly message.

| Pattern | Route | Implementation |
|---------|-------|----------------|
| `git add .` / `git add -A` | `VaultGitSync.addAll()` | JGit |
| `git commit -m "msg"` | `VaultGitSync.commit(msg)` | JGit |
| `git commit -am "msg"` | `VaultGitSync.commit(msg, addAll=true)` | JGit |
| `git push origin <branch>` | `VaultGitSync.push()` | JGit |
| `git pull --rebase origin <branch>` | `VaultGitSync.pull()` | JGit |
| `git status` | `VaultGitSync.status()` | JGit |
| `git log --oneline -N` | `VaultGitSync.log(N)` | JGit |
| `date` | `LocalDateTime.now().toString()` | stdlib |
| `ls <path>` / `ls -la <path>` | `File.listFiles()` formatted | stdlib |
| `mkdir -p <path>` | `File.mkdirs()` | stdlib |
| anything else | `"Command not supported on mobile: ..."` | — |

---

### Layer 2 — Vault Layer (`com.vela.app.vault`)

**`VaultRegistry`** — Room-backed list of configured vaults.

Schema: `id: String`, `name: String`, `localPath: String`, `remoteUrl: String?`, `githubPat: String?`, `isEnabled: Boolean`, `createdAt: Long`.

`isEnabled` is the global toggle (show/hide vault from sessions). Per-session active state is ephemeral — held in memory by `SessionHarness`, never persisted.

`resolveVaultPath(path)` — given an absolute path string, returns the `VaultConfig` whose `localPath` is a canonical prefix of the given path. File tools use this to confirm a requested path belongs to a known active vault; they error on no match.

**`VaultManager`** — path resolution + traversal attack protection.

`resolve(vaultRoot, relativePath): File?` — canonicalizes via `File.canonicalPath`, checks `startsWith(vaultRoot.canonicalPath)`. Returns `null` on traversal attempt. All file tools call this before touching disk.

Local path convention: `filesDir/vaults/<vault-id>/` — fixed by the app, not user-configurable. Keeps everything in app-private storage with no permission requirements.

**`VaultGitSync`** — JGit operations.

Methods: `cloneIfNeeded()`, `addAll()`, `commit(message, addAll)`, `push()`, `pull()`, `status()`, `log(count)`.

HTTPS authentication via `UsernamePasswordCredentialsProvider("token", pat)`. All operations run on `Dispatchers.IO`. Returns result strings; never throws (errors returned as `Result.failure` with message).

**`VaultSettings`** — SharedPrefs wrapper for per-vault remote URL and PAT. Follows the same pattern as existing `AmplifierSession` key storage.

---

### Layer 3 — Skills Layer (`com.vela.app.skills`)

Implements the [Agent Skills specification](https://agentskills.io) in full.

**`SkillsEngine`** — discovers `SKILL.md` files from:
1. Active vault-local `skills/` directories (first match wins across vaults in priority order)
2. App-bundled `assets/skills/`

`SkillMeta` data class: `name`, `description`, `isFork`, `isUserInvocable`, `directory` (absolute path to skill folder).

`SkillLoadResult` sealed class: `Content(body: String, skillDirectory: String)`, `ForkResult(response: String)`, `Error(message: String)`.

**SKILL.md parsing rules:**
- Frontmatter `name` + `description` fields are required.
- `name` must equal the parent directory name (spec requirement).
- Unknown frontmatter fields are silently ignored (forward compatibility).
- `context: fork` → `isFork = true`.
- `user-invocable: true` → `isUserInvocable = true`.

**Fork execution:** for `context: fork` skills, `load()` spawns a sub-session via `InferenceEngine.runForkSession(skill.body, arguments)` — empty history, skill body as system prompt. Returns `ForkResult` with the sub-session's response.

`LoadSkillTool` delegates to `SkillsEngine` for all four operations (`list`, `search`, `info`, `skill_name`). The `skill_directory` path returned by `info` and `skill_name` operations lets the AI request companion files via `ReadFileTool`.

---

### Layer 4 — Hooks Layer (`com.vela.app.hooks`)

Lifecycle observers. Code-decided — they fire at defined points regardless of what the AI does. Not configurable at runtime.

```kotlin
interface Hook {
    val event: HookEvent
    val priority: Int get() = 0
    suspend fun execute(ctx: HookContext): HookResult
}

enum class HookEvent { SESSION_START, AFTER_WRITE_FILE, SESSION_END, VAULT_TOGGLED }

data class HookContext(
    val conversationId: String,
    val activeVaults: List<VaultConfig>,
    val event: HookEvent,
    val metadata: Map<String, Any> = emptyMap(),
)

sealed class HookResult {
    object Continue : HookResult()
    data class SystemPromptAddendum(val text: String) : HookResult()
    data class Error(val message: String) : HookResult()
}
```

**Built-in hooks** (registered via Hilt, run in priority order):

| Hook | Event | Behaviour |
|------|-------|-----------|
| `VaultSyncHook` | `SESSION_START` | JGit pull on each active vault with a configured remote + PAT |
| `PersonalizationHook` | `SESSION_START` | Reads `_personalization/*.md` from each active vault; returns `SystemPromptAddendum` with concatenated content |
| `VaultConfigHook` | `SESSION_START` | Builds a `<lifeos-config>` XML block with vault names, types, and local paths; returns `SystemPromptAddendum` |

`HookRegistry` — injected into `SessionHarness`. Holds hooks sorted by priority. `SESSION_START` hooks fire lazily on the first turn of a vault-mode conversation.

---

### Layer 5 — Harness Layer (`com.vela.app.harness`)

`SessionHarness.initialize(conversationId, activeVaults)` — runs once on first turn of vault-mode conversations.

**Initialization sequence:**
1. Run all `SESSION_START` hooks in priority order; collect `SystemPromptAddendum` results.
2. Load `SYSTEM.md`: first active vault that contains one → else `assets/lifeos/SYSTEM.md` (bundled fallback).
3. Build composite system prompt:

```
{SYSTEM.md content}

{personalization addenda from PersonalizationHook}

<lifeos-config>
vaults:
  - name: personal
    type: personal
    location: /data/data/com.vela.app/files/vaults/abc123
</lifeos-config>
```

4. Pass to `InferenceEngine` → `AmplifierSession` → Rust bridge via new `systemPrompt` parameter.

Default conversations (`mode = "default"`) skip `SessionHarness.initialize()` entirely and pass an empty system prompt. Zero overhead for non-vault sessions.

---

### Rust Bridge Change

`nativeRun` gains a new `systemPrompt: String` parameter. When non-empty, the Anthropic API request includes a `"system"` field. `InferenceSession.runTurn()` gets `systemPrompt = ""` as default. `AmplifierSession` passes the value through from `SessionHarness`.

---

### Room Migration

`ConversationEntity` gains `mode: String` column (`DEFAULT 'default'`). Valid values: `"default"`, `"vault"`.

Migration `MIGRATION_6_7`:
```sql
ALTER TABLE conversations ADD COLUMN mode TEXT NOT NULL DEFAULT 'default'
```

---

### Settings Screen (`com.vela.app.ui.settings`)

Single gear icon in the conversation list top bar. Replaces both the current API key setup and the Connections top-bar button.

**Three sections:**

**1. AI**
- Anthropic API key — masked text field; edit/save
- Model picker — dropdown (claude-sonnet-4-6, claude-opus-4, claude-haiku-4)

**2. Connections**
- SSH nodes list, moved from the top-bar button into Settings
- Same functionality as the current `NodesScreen`, now nested inside Settings
- Top bar loses its Connections button entirely

**3. Vaults**
- List of configured vaults: name + sync status indicator (✓ synced / ⟳ pending / ✗ no remote) + global enable toggle
- Tap a vault → `VaultDetailScreen` (edit name, remote URL, PAT; trigger manual sync; delete)
- "Add Vault" FAB

**Add Vault flow** — bottom sheet: name field (required) + "Connect GitHub (optional)" collapsible section (remote URL + PAT). On confirm: create `filesDir/vaults/<id>/`, JGit `init`, optionally set remote and pull.

**`VaultsScreen` is NOT a separate top-level screen.** It is the Vaults section inside `SettingsScreen`. `VaultDetailScreen` is pushed from there.

---

### Vault Chips Row (`ConversationScreen`)

Below the top bar when vaults are configured. One chip per globally-enabled vault. Tapping a chip toggles it for the current session only (ephemeral — not persisted). Row is hidden entirely when no vaults are configured — zero visual noise for users not using vaults.

---

### New Vault Conversation

Long-press on the new conversation button (or a dedicated option in the conversation creation flow) creates a vault-mode conversation. `SessionHarness` kicks in on the first message.

## Data Flow

**Vault-mode session, first turn:**
```
User sends message
  → ConversationScreen checks conversation.mode
  → SessionHarness.initialize() fires (once, lazy)
      → HookRegistry runs SESSION_START hooks
          → VaultSyncHook: JGit pull each active vault
          → PersonalizationHook: read _personalization/*.md → addendum
          → VaultConfigHook: build <lifeos-config> block → addendum
      → Load SYSTEM.md (vault or bundled fallback)
      → Compose final system prompt
  → InferenceEngine.runTurn(systemPrompt, userMessage, tools)
      → AmplifierSession builds Anthropic API request with "system" field
      → Rust bridge sends to Anthropic
  → AI responds; may call tools
      → Tool call → ToolRegistry.dispatch(name, args)
          → e.g. ReadFileTool: VaultManager.resolve() → File.readText() → cat-n format
          → e.g. BashTool("git commit -m '...'") → VaultGitSync.commit()
      → Tool result returned to AI
  → Turn complete
```

**Default session, first turn:**
```
User sends message
  → SessionHarness skipped (mode = "default")
  → InferenceEngine.runTurn(systemPrompt = "", userMessage, tools)
  → Normal Anthropic call, same as today
```

**Multi-vault file routing:**
```
AI calls write_file(path="/data/data/com.vela.app/files/vaults/abc123/notes/foo.md", ...)
  → WriteFileTool: VaultRegistry.resolveVaultPath(path) → finds "personal" vault
  → VaultManager.resolve(vaultRoot, path) → canonicalize, traversal check
  → File.writeText(content)
  → AFTER_WRITE_FILE hooks fire (if any registered)
```

## Error Handling

- **Path traversal attempts** — `VaultManager.resolve()` returns `null`; tool returns an error string to the AI. No exception thrown.
- **JGit failures** — `VaultGitSync` catches all exceptions; returns `Result.failure(message)`. `BashTool` converts to a string like `"git push failed: authentication error"` returned as tool result. Session continues.
- **Skill not found** — `LoadSkillTool` returns a `SkillLoadResult.Error` with a clear message. AI can retry with a different name.
- **SKILL.md parse error** — skill is silently excluded from discovery results; a warning is logged. Spec requires unknown fields to be ignored; malformed YAML fails the whole file.
- **Hook failure** — `HookResult.Error` is logged; harness continues with remaining hooks. A failed sync hook does not block the session.
- **SYSTEM.md not found** — `SessionHarness` falls back to bundled `assets/lifeos/SYSTEM.md`. If that is also absent (misconfigured build), harness uses an empty string and logs a warning.
- **File tool on path outside any active vault** — tool returns `"Path does not resolve to any active vault"`. Prevents accidental writes to arbitrary app-private storage.

## Testing Strategy

| Layer | What | Approach |
|-------|------|----------|
| `VaultManager.resolve()` | Path traversal blocked; relative/absolute resolution | Unit test (JVM, temp `File` root) |
| `EditFileTool` | Exact match; multi-match error; `replace_all` | Unit test with temp files |
| `BashTool` routing | All command patterns parsed correctly | Unit test (mock `VaultGitSync`) |
| `GlobTool` | Pattern matching; `.git` exclusion; result limit | Unit test with temp directory tree |
| `GrepTool` | Regex; case sensitivity; context lines; all output modes | Unit test with temp files |
| `VaultGitSync` | Clone, commit, push, pull | Integration test (local bare repo via JGit) |
| `SkillsEngine` | Discovery priority; SKILL.md parsing; fork dispatch | Unit test (mock `InferenceEngine`) |
| Hook chain | `SESSION_START` hooks fire in priority order; addenda collected | Unit test (mock `VaultGitSync`) |
| `SessionHarness` | Vault-mode gets system prompt; default mode gets empty string | Integration test (mock `InferenceSession`) |

## Open Questions

1. **Fork skill `$ARGUMENTS`** — how does the user pass arguments to a `user-invocable` skill from the conversation UI? Future: slash command + argument string. Not blocking.
2. **Vault marketplace** — future "Browse Vaults" screen fetching a JSON registry from a configurable URL. No implementation in this design.
3. **EncryptedSharedPreferences** — PAT and API key currently in plain SharedPrefs (same as existing API key storage). Migration to `EncryptedSharedPreferences` is a separate security hardening task.
4. **`max_tokens` bump** — lifeos sessions are heavy context. Current Rust hardcode of 4096 may be too low. Separate task.

## New Packages

```
com.vela.app.vault/
├── VaultRegistry.kt        (Room DAO + entity + config data class)
├── VaultManager.kt         (path resolution + traversal protection)
├── VaultGitSync.kt         (JGit operations)
└── VaultSettings.kt        (SharedPrefs wrapper for remote URL + PAT)

com.vela.app.skills/
├── SkillsEngine.kt         (discovery, parsing, fork execution)
├── SkillMeta.kt            (data class)
└── SkillLoadResult.kt      (sealed class)

com.vela.app.hooks/
├── Hook.kt                 (interface + HookEvent + HookContext + HookResult)
├── HookRegistry.kt
├── VaultSyncHook.kt
├── PersonalizationHook.kt
└── VaultConfigHook.kt

com.vela.app.harness/
└── SessionHarness.kt

com.vela.app.ui.settings/
├── SettingsScreen.kt
└── SettingsViewModel.kt
```

**Modified files:**
- `Tool.kt` — add `required: Boolean = true` to `ToolParameter`
- `AmplifierSession.kt` — update `buildToolsJson()` for required-only param list; pass `systemPrompt`
- `InferenceSession.kt` — add `systemPrompt: String = ""` to `runTurn()`
- `InferenceEngine.kt` — add `runForkSession(systemPrompt, arguments)` for skill fork execution
- `AppModule.kt` — Hilt bindings for new components
- `ConversationEntity.kt` — add `mode: String` column
- `VelaDatabase.kt` — register `MIGRATION_6_7`
- Rust bridge (`lib.rs`, `provider.rs`) — add `systemPrompt` parameter to `nativeRun`

**New assets:**
- `assets/lifeos/SYSTEM.md` — bundled fallback system prompt

**New Gradle dependency:**
```kotlin
implementation("org.eclipse.jgit:org.eclipse.jgit:6.8.0.202311291450-r")
```
