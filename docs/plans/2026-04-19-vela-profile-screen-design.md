# Vela Profile Screen Design

## Goal

Transform the Profile tab from a settings list into a living, vault-native portrait of who the user is — what Vela knows about them, what they have been doing, and what matters to them.

## Codebase Context

- **Platform:** Kotlin Android, Jetpack Compose, Material 3
- **Source root:** `app/src/main/kotlin/com/vela/app/`
- **Navigation:** `ui/NavigationScaffold.kt` — `AppDestination.PROFILE` currently routes to `SettingsScreen`
- **Existing settings:** `ui/settings/SettingsScreen.kt` — 5 nav rows: AI, Connections, Vaults, Recording, GitHub
- **Vault infrastructure:** `VaultManager.resolve(path)` for safe file access; `VaultRegistry.enabledVaults: StateFlow<List<VaultEntity>>`; each vault has `id`, `name`, `localPath`, `isEnabled`, `createdAt`
- **Session data:** `TurnDao` in Room DB; conversations as `Conversation` + `Turn` + `TurnEvent` entities
- **Background jobs:** `WorkManager` available in Gradle dependencies
- **No existing user or profile model** — `GitHubIdentityEntity` (username, avatarUrl) is the richest identity data today

---

## Chosen Approach: Vault-Native

Profile is stored as `.vela/profile.md` inside the primary vault (the oldest/first enabled vault). It travels with git sync, is readable as plain markdown in the vault browser, and requires zero additional infrastructure beyond what already exists.

**Rejected — Room-native:** Storing the profile as a `ProfileEntity` in Room would be fast but would not sync across devices and would be lost on app reinstall.

**Rejected — Hybrid:** Storing in both vault and Room adds two sources of truth that can drift and doubles the maintenance burden.

---

## Architecture

Three components work together:

```
All enabled vaults  ──┐
Recent sessions (DB) ──┼──▶  ProfileWorker  ──▶  .vela/profile.md  ──▶  ProfileScreen
Current profile       ──┘
```

### 1. ProfileDocument — `.vela/profile.md`

A markdown file with two layers:
- **YAML frontmatter** — machine-readable structured identity fields
- **XML-tagged blocks** — narrative sections that the LLM updates independently

#### Format

```markdown
---
name: Ken
role: Software engineer · AI tooling
location: Seattle
key_projects:
  - Amplifier
  - Vela
interests:
  - Developer tools
  - LLMs
  - Android
  - Productivity systems
key_people:
  - Salil
last_updated: 2026-04-19T12:00:00Z
profile_version: 4
source_vaults: [lifeos, work-notes]
---

<vela:knows vault="lifeos" updated="2026-04-19">
Building the Vela mini app renderer system. Focused on context-aware UX and
personal AI. Exploring the intersection of productivity tools and personal OS.
</vela:knows>

<vela:knows vault="work-notes" updated="2026-04-18">
Current sprint: Profile redesign, mini app build screen, ConnectorsScreen.
Collaborating with the Microsoft Amplifier team.
</vela:knows>

<vela:pulse>
- 2026-04-19: Session — designed Profile screen with brainstorm companion
- 2026-04-18: lifeos vault synced — 3 tasks completed, 2 new journal entries
- 2026-04-18: Session — Vela navigation refactor, 4-tab shell
</vela:pulse>
```

#### Parsing in ProfileScreen

1. Split on `---` delimiters → extract YAML frontmatter → identity card fields (SnakeYAML or manual parse)
2. Regex `<vela:knows[^>]*vault="([^"]+)"[^>]*>([\s\S]*?)</vela:knows>` → labeled knowledge cards, one per vault
3. Regex `<vela:pulse>([\s\S]*?)</vela:pulse>` → timeline lines

The `vela:` namespace is distinctive and will not clash with content in user vault files.

---

### 2. ProfileWorker — Android CoroutineWorker

#### Scheduling

- Class: `ProfileWorker : CoroutineWorker`
- Registered as `PeriodicWorkRequest`: `repeatInterval = 24h`, `flexTimeInterval = 4h`
- Constraints: `setRequiresBatteryNotLow(true)` + `setRequiresDeviceIdle(true)` — fires lazily when the device is idle overnight
- Worker tag: `"profile_worker"` for observation and cancellation

#### Manual refresh

The refresh FAB enqueues a `OneTimeWorkRequest` for `ProfileWorker` with `ExistingWorkPolicy.REPLACE`. `ProfileScreen` observes `WorkManager.getWorkInfosByTagLiveData("profile_worker")` and shows a `LinearProgressIndicator` while `State == RUNNING`.

#### What the worker reads

1. Current `.vela/profile.md` — reads `last_updated` from YAML frontmatter
2. All enabled vault files with `File.lastModified() > last_updated` (incremental delta)
3. Last 30 conversation turns from Room DB `TurnDao`, ordered by creation time descending
4. **First run** (no profile file exists): reads ALL vault files, no delta

#### LLM prompt structure

```
Current profile:
[full .vela/profile.md content]

New vault content since [last_updated]:
=== [vault name] ===
[changed file contents]

Recent sessions:
- [turn summary]
...

Update the profile document:
1. YAML frontmatter — add new facts conservatively; never remove existing facts without clear evidence
2. <vela:knows vault="X"> blocks — update per vault; add new blocks for new vaults; stamp with vault name and today's date
3. <vela:pulse> — prepend new entries; keep only the 20 most recent
Return ONLY the complete updated profile.md. No explanation.
```

#### Writing back

Worker writes the LLM response to `.vela/profile.md` via `VaultManager.resolve(".vela/profile.md")`. Only writes after a successful, non-empty LLM response — the existing file is never overwritten on error.

---

### 3. ProfileScreen — Compose UI

#### Three zones

**Zone 1 — Identity card** (gradient card using primary colour scheme):
- Avatar: `GitHubIdentityEntity.avatarUrl` if a GitHub identity exists; otherwise initials placeholder
- Name and role from YAML frontmatter `name` and `role` fields
- Horizontally scrolling row of tag chips: `key_projects` + `interests` values

**Zone 2 — Vela knows** (section label: "Vela knows · updated [relative time]"):
- One `Card` per `<vela:knows>` block, ordered by `updated` attribute descending
- Each card: small coloured vault-name label (colour derived from vault ID hash) + narrative body text
- All enabled vaults shown if they have a `<vela:knows>` block; new vaults appear after the first worker run

**Zone 3 — Life pulse** (section label: "Life pulse"):
- Timeline feed from `<vela:pulse>` entries
- Each entry: icon (💬 for sessions, 📄 for vault events), text, relative timestamp
- Capped at 20 entries — enforced at write time in `ProfileWorker`

#### Other UI elements

- `FloatingActionButton` (↻ refresh icon, bottom-right) — enqueues `OneTimeWorkRequest`
- `LinearProgressIndicator` below top app bar — visible only while `WorkInfo.State == RUNNING`
- `IconButton` (⚙) in top app bar trailing slot — navigates to slim Settings screen
- "updated [relative time]" shown in Zone 2 section header from `last_updated` in YAML frontmatter
- **Empty state** (no vault configured): centred message "Add a vault to see your profile" with button navigating to Vaults settings

---

## New Files

| File | Purpose |
|---|---|
| `app/src/main/kotlin/com/vela/app/ui/profile/ProfileScreen.kt` | Three-zone Compose UI composable |
| `app/src/main/kotlin/com/vela/app/ui/profile/ProfileViewModel.kt` | Reads `.vela/profile.md`, parses frontmatter + XML blocks, observes WorkInfo |
| `app/src/main/kotlin/com/vela/app/workers/ProfileWorker.kt` | CoroutineWorker: reads vaults + sessions, calls LLM, writes updated profile |
| `app/src/main/kotlin/com/vela/app/workers/ProfileWorkerScheduler.kt` | Registers PeriodicWorkRequest on app start; exposes `triggerRefresh()` for the FAB |

---

## Modified Files

| File | Change |
|---|---|
| `app/src/main/kotlin/com/vela/app/ui/NavigationScaffold.kt` | `AppDestination.PROFILE` destination → `ProfileScreen(modifier)` instead of `SettingsScreen(...)` |
| `app/src/main/kotlin/com/vela/app/ui/settings/SettingsScreen.kt` | Remove Connections nav row; remove GitHub nav row |
| `app/src/main/kotlin/com/vela/app/di/AppModule.kt` | Hilt `@Provides` bindings for `ProfileWorkerScheduler`; `ProfileViewModel` uses `@HiltViewModel @Inject constructor` and is auto-bound |

---

## Settings Restructure

The Settings screen behind ⚙ slims from 5 rows to 3:

| Row | Status after this design |
|---|---|
| **AI** — API keys, model picker | Stays |
| **Vaults** — add / remove / configure git sync | Stays |
| **Recording** — transcription provider preference | Stays |
| **Connections** — SSH and Amplifier nodes | Removed — already accessible in the Connectors tab |
| **GitHub** — OAuth accounts | Removed — pending Connectors tab integration (separate effort) |

The word "Settings" does not appear anywhere in the main navigation bar — only the ⚙ icon within the Profile tab header.

---

## Error Handling

| Failure | Response |
|---|---|
| No vault configured | `ProfileScreen` shows empty state; `ProfileWorker` is not scheduled |
| `.vela/profile.md` missing | Empty profile UI shown; refresh FAB triggers first-time generation |
| LLM call fails in `ProfileWorker` | Log error, return `Result.failure()` — existing `.vela/profile.md` is untouched |
| Vault file read fails | Worker skips that vault, continues with remaining vaults |
| YAML frontmatter malformed | Fall back to empty identity card; Zone 2 shows "Profile needs updating" placeholder |
| Worker times out | WorkManager handles retry with exponential backoff; user can trigger manual refresh at any time |

---

## Deferred (Out of Scope)

- **Recordings as virtual vault** — surfacing `filesDir/recordings/` as a special vault entry in the Vault tab (follow-on design)
- **GitHub in Connectors** — requires OAuth integration work (separate planning effort)
- **Profile vault selection** — primary vault is currently the oldest enabled vault; a future setting could let the user designate a specific vault as their profile home

---

## Overall Theory of Success

With at least one vault enabled, opening the Profile tab shows: an identity card with name, role, and key project tags; a "Vela knows" section with one labeled block per source vault; and a Life pulse feed of recent sessions and vault activity. Tapping ⚙ opens a Settings screen with only AI, Vaults, and Recording rows — Connections and GitHub rows are gone. Tapping the refresh FAB shows a `LinearProgressIndicator` and updates the profile content within 60 seconds. The profile persists across app restarts — `.vela/profile.md` exists in the primary vault and is readable as plain markdown. Without any vault configured, Profile shows a clear empty state directing the user to add a vault.
