# Optimistic UI & State Management Design

**Date:** 2026-05-03  
**Status:** Approved, ready for implementation planning  
**Scope:** Remote amplifierd entities (Projects, Sessions) + Node reachability + Room cascade fixes

---

## Problem

The app has two persistence worlds. The Room-backed entities (Conversations, Nodes, Vaults, GitHub Identities) are reactive — Room emits a new `Flow` value on every write and list updates are automatic. The remote amplifierd entities (Projects, Sessions) are not — each ViewModel holds its own private `MutableStateFlow<List<…>>` with no shared source of truth. This causes three user-visible bugs:

1. **Delete doesn't update the parent list.** Delete a project from `SessionListScreen`, navigate back — the project still appears in `NodeDetailScreen` until the user leaves and re-enters the node.
2. **Edit leaves the title frozen.** Update a project name from the gear menu — the title in `SessionListScreen` stays stale for the rest of the navigation session (it's bound to a `SavedStateHandle` URL arg, never updated).
3. **Node status shows green when the node is unreachable.** `BootstrapStatus.RUNNING` in Room persists across sessions. If the node goes offline, the UI stays green until something explicitly fails.

Additionally, `SessionStreamingManagerImpl` is documented to preserve session state after `stopStreaming()` — meaning deleted sessions leave ghost `SessionState` entries alive in `getAllSessionFlows()`.

---

## Decisions

| Question | Decision |
|---|---|
| Scope | Remote layer (Projects + Sessions) as primary; Room cascade fixes as a separate small PR |
| Architecture | A-lite: in-memory repository singletons, no Room persistence for remote entities |
| Error handling | React Query pattern: optimistic patch → silent rollback on error → refetch on settled |
| Node reachability | Dedicated `NodeReachabilityMonitor` singleton, probe on screen appear + mark unreachable on any API failure |
| Offline | Out of scope. A-lite does not survive process death. If offline becomes a real complaint, Room persistence for remote entities can be layered in later without redesigning this. |

---

## Architecture Overview

```
BEFORE

NodeDetailVM ──owns──▶ MutableStateFlow<List<Project>>    (private)
SessionListVM ──owns──▶ MutableStateFlow<List<Session>>   (private)
SessionDetailVM                                            (separate, no coordination)
StreamingManager                                           (leaks ghost states after delete)
SshNodeRegistry                                            (no live reachability signal)

AFTER

ProjectRepository (@Singleton) ──StateFlow──▶ NodeDetailVM
                               └──StateFlow──▶ SessionListVM

SessionRepository (@Singleton) ──StateFlow──▶ SessionListVM
                               └──StateFlow──▶ SessionDetailVM

StreamingManager + evict(sessionId)

NodeReachabilityMonitor (@Singleton) ──StateFlow──▶ NodeDetailVM
                                     └──StateFlow──▶ SessionListScreen
                                     └──StateFlow──▶ NodesViewModel
```

---

## Optimistic Write Pattern

Applied identically to every mutating operation (create, update, delete) in `ProjectRepository` and `SessionRepository`.

```
1. Snapshot current list
2. Patch repository StateFlow immediately          ← UI updates instantly
3. Fire API call async
   onError  → restore snapshot in StateFlow       ← silent rollback, item reappears
   onSettled → reload from server into StateFlow  ← server truth always wins
```

No snackbars. No undo affordance. No dialogs on failure. Item disappears; if it fails, it silently reappears. This is the React Query optimistic mutation contract.

---

## CRUD Sequence Diagrams

### Project Delete

```
User ──delete──▶ ProjectRepository
                    │
                    ├─ snapshot current list
                    ├─ remove from StateFlow ◀── NodeDetailVM observes → item gone instantly
                    └─ client.deleteProject() ──async──▶ server
                                                              │
                                              error ◀─────────┤
                              restore snapshot │               │ success
                    item reappears silently ◀──┘               │
                                                    reload from server
                                                    StreamingManager.evict(all sessions for project)
```

### Session Delete

```
User ──delete──▶ SessionRepository
                    │
                    ├─ snapshot current list
                    ├─ remove from StateFlow ◀── SessionListVM observes → row gone
                    └─ client.deleteSession() ──async──▶ server
                                                              │
                    SessionDetailVM: _sessionDeleted=true only on confirmed success, navigate back
                                                              │
                                              error ◀─────────┤
                              restore snapshot │               │ success
                    row reappears in list ◀────┘               │
                                                    reload from server
                                                    StreamingManager.evict(sessionId)
```

### Project Update

```
User saves edit ──▶ ProjectRepository.update(id, name, workingDir)
                         │
                         ├─ patch project in StateFlow immediately
                         │      ◀── SessionListScreen title updates (reads from repo, not SavedStateHandle)
                         │      ◀── NodeDetailScreen list row also updates (same StateFlow)
                         └─ client.updateProject() ──async──▶ server
                                                                   │
                                                   error ◀──────────┤
                                    restore snapshot │               │ success
                         old name reappears ◀────────┘               │
                                                         reload from server
```

### Session Create (not optimistic — server ID required for navigation)

```
User taps New Session ──▶ SessionRepository.create(projectId, ...)
                              │
                              ├─ set isCreating=true (spinner)
                              └─ client.createSession() ──▶ server ──▶ returns sessionId
                                                                │
                                                   add to StateFlow
                                                   navigate to SessionDetailScreen(sessionId)
                                                   error → isCreating=false, nothing else changes
```

### Node Reachability — Probe

```
NodeDetailScreen appears
    │
    ▼
NodeReachabilityMonitor.probe(nodeId)
    │
    ├─ StateFlow: UNKNOWN → PROBING
    └─ client.getCapabilities(nodeId) ──▶ server
                                              │
                        success ◀─────────────┤
        PROBING → REACHABLE │                 │ failure / timeout
        green dot ◀─────────┘                 │
                                    PROBING → UNREACHABLE
                                    red dot, create/delete actions disabled
```

### Node Reachability — Failure Propagation

Any API call anywhere can mark a node unreachable immediately — no probe needed:

```
ProjectRepository.delete() throws IOException
    │
    ├─ restore snapshot (project reappears)
    └─ NodeReachabilityMonitor.markUnreachable(nodeId)
              │
              └─ StateFlow updates → red dot appears everywhere immediately
```

---

## Screen State Machines

### NodeDetailScreen

```
LOADING
  └─[repo emits]──▶ LOADED
                      ├─[probe on appear]──▶ PROBING (spinner on dot)
                      │     ├─[success]──▶ LOADED + green dot
                      │     └─[failure]──▶ LOADED + red dot + actions disabled
                      ├─[any API failure]──▶ LOADED + red dot (instant)
                      ├─[delete optimistic]──▶ LOADED (list shrinks)
                      └─[delete rollback]──▶ LOADED (list restores silently)
```

### SessionListScreen

```
LOADING
  └─[repo emits]──▶ LOADED
                      ├─[session deleted optimistic]──▶ LOADED (row gone)
                      │     └─[rollback]──▶ LOADED (row back)
                      ├─[project name updated]──▶ LOADED (title updates immediately)
                      ├─[new session tapped]──▶ CREATING
                      │     ├─[server returns ID]──▶ navigate to SessionDetail
                      │     └─[error]──▶ LOADED (spinner gone, nothing else changes)
                      └─[node unreachable]──▶ LOADED + red dot + actions disabled
```

### SessionDetailScreen

```
LOADING
  └─[streaming state arrives]──▶ IDLE
                                    ├─[user sends message]──▶ STREAMING
                                    │     ├─[stream completes]──▶ IDLE
                                    │     └─[thinking block arrives]──▶ STREAMING (block visible)
                                    ├─[approval needed]──▶ AWAITING_APPROVAL
                                    │     └─[approved]──▶ STREAMING
                                    ├─[user deletes]──▶ DELETED ──▶ pop backstack
                                    │     (SessionListScreen: row already gone via repo)
                                    └─[node unreachable]──▶ ERROR
```

### NodeReachability (global)

```
UNKNOWN ──probe──▶ PROBING ──success──▶ REACHABLE ──probe──▶ PROBING
                        └──failure──▶ UNREACHABLE ──screen appears──▶ PROBING
REACHABLE ──any API IOException──▶ UNREACHABLE
```

---

## Component Inventory

### New (3 classes)

| Class | Package | Responsibility |
|---|---|---|
| `ProjectRepository` | `com.vela.app.amplifierd` | `@Singleton`. `MutableStateFlow<Map<nodeId, List<AmplifierdProject>>>`. Exposes `projects(nodeId): StateFlow<List<AmplifierdProject>>`. All project CRUD with optimistic patch + rollback + refetch. On confirmed project delete, calls `SessionRepository.evictProject(projectId)` to clear cached sessions and streaming state for all child sessions. Calls `NodeReachabilityMonitor.markUnreachable()` on `IOException`. |
| `SessionRepository` | `com.vela.app.amplifierd` | `@Singleton`. `MutableStateFlow<Map<projectId, List<AmplifierdSession>>>`. Exposes `sessions(projectId): StateFlow<List<AmplifierdSession>>`. All session CRUD. Exposes `evictProject(projectId)` for `ProjectRepository` to call on project delete — clears the session list and calls `StreamingManager.evict()` for each known session. Calls `NodeReachabilityMonitor.markUnreachable()` on `IOException`. |
| `NodeReachabilityMonitor` | `com.vela.app.amplifierd` | `@Singleton`. `MutableStateFlow<Map<nodeId, Reachability>>` where `Reachability = UNKNOWN \| PROBING \| REACHABLE \| UNREACHABLE`. `probe(nodeId)` fetches `/capabilities`. `markUnreachable(nodeId)` sets directly. Auto-retries when a screen calls `probe()` on an `UNREACHABLE` node. |

### Modified (6 classes)

| Class | Changes |
|---|---|
| `SessionStreamingManagerImpl` | Add `evict(sessionId: String)` — removes entry from `sessionFlows`. Called by `SessionRepository` after confirmed session delete. |
| `NodeDetailViewModel` | Observe `ProjectRepository.projects(nodeId)` instead of `_projects`. Call `NodeReachabilityMonitor.probe(nodeId)` on init. Expose `reachability: StateFlow<Reachability>` to UI. Remove local `_projects` field. |
| `SessionListViewModel` | Observe `SessionRepository.sessions(projectId)`. Observe `ProjectRepository.projects(nodeId)` for live project name — replaces frozen `SavedStateHandle` arg. Remove `previewCache` (dead memory). Expose `reachability` to UI. |
| `SessionDetailViewModel` | Route delete through `SessionRepository` instead of direct client call. Set `_sessionDeleted = true` only when `SessionRepository.delete()` returns success. Call `StreamingManager.evict(sessionId)` on success. |
| `NodeDetailScreen` | Bind green/red status dot to `NodeReachabilityMonitor` state, not `BootstrapStatus`. Disable create/delete actions when `UNREACHABLE`. |
| `SessionListScreen` | Bind screen title to repo-derived project name, not `SavedStateHandle`. Show reachability indicator. Disable create/delete when `UNREACHABLE`. |

### Hilt / DI

All three new classes need `@Singleton` Hilt bindings. Dependency graph:

```
NodeReachabilityMonitor  ←  AmplifierdRepository, SshNodeRegistry
ProjectRepository        ←  AmplifierdRepository, SshNodeRegistry, NodeReachabilityMonitor, SessionRepository
SessionRepository        ←  AmplifierdRepository, SshNodeRegistry, NodeReachabilityMonitor, SessionStreamingManagerImpl
```

`ProjectRepository` depends on `SessionRepository` (not the reverse) to call `evictProject()` on confirmed project delete. No circular dependency.

---

## Separate PR — Room Cascade Fixes

Independent of the above. Two changes, one migration.

| Change | Details |
|---|---|
| `TurnEventEntity` | Add `ForeignKey(entity = TurnEntity::class, parentColumns = ["id"], childColumns = ["turnId"], onDelete = ForeignKey.CASCADE)`. Update `VelaDatabase` to schema v17 with migration. |
| `SettingsViewModel.deleteVault` | Add `vaultEmbeddingDao.deleteForVault(vaultId)` call before `vaultRegistry.delete(vaultId)`. `VaultEmbeddingDao.deleteForVault()` already exists — it's just not being called. |

No other entities have cascade gaps.

---

## Out of Scope

- **Offline / persistence for remote entities.** Projects and Sessions are in-memory only. Process death = reload from server. If this becomes a pain point, Room persistence can be added without changing the repository interfaces.
- **Undo affordance.** No "deleted · undo" snackbar. Rollback happens silently on error only.
- **Conflict resolution.** No multi-device sync. Server is the single writer.
- **`ConversationViewModel` / Room-backed local chat.** Already reactive via `Flow`. Not touched.
- **`previewCache` replacement.** Removing it is cleanup; no replacement needed — session previews come from the server-loaded `SessionSummary` already.
