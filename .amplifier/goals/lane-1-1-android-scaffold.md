# Lane 1.1 — android-scaffold

**Outcome:** A new Android application exists under `android/` that builds, installs, and
launches on a headless emulator; renders a mock card-deck (attention queue) supporting
swipe-to-decide and a mock chat/transcript surface; defines and merges the four domain
interfaces (`VoiceTransport`, `HostTool`, `LedgerRepository`, `EventStream`) as its first
commit; and has a green CI workflow.

**Working directory / branch / base SHA:** worktree only, branch `lane/1.1-android-scaffold`,
base SHA `688a1834`. Work ONLY in this worktree. Do not touch the main checkout or sibling
worktrees.

**File ownership:** `android/` (new root), `android/app/`, `android/core-ui/`,
`android/core-domain/`, `settings.gradle.kts`, `.github/workflows/android.yml`. If you need to
touch any file outside this list, stop, record the needed edit as a residual in `DONE.json`,
and do not make the edit.

**Sequencing requirement:** the four domain interfaces
(`android/core-domain/.../VoiceTransport.kt`, `HostTool.kt`, `LedgerRepository.kt`,
`EventStream.kt`) must be your first commit on this branch, pushed immediately, so other
lanes can code against them before this lane finishes.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Items ending FAIL or BLOCKED
are residuals, not failures of the goal. Terminal states: PASS / FAIL-named /
BLOCKED-named / PENDING-HUMAN.

Items:
1. Gradle project scaffolded (Compose, DI, navigation, module structure: `app`, `core-ui`,
   `core-domain`) and builds cleanly (`./gradlew assembleDebug`).
2. Four domain interfaces defined, committed first, pushed.
3. App installs and launches on a headless emulator (per the project's headless Android
   testing conventions: no GUI, no human tap) — verified via `adb install` + `adb shell am
   start` + boot/launch check.
4. Card deck renders a mock attention queue; swipe-to-decide verified via a deterministic
   Compose semantics test (`onNodeWithTag("card_deck").performTouchInput { swipeLeft() }`),
   not an AI-judged UI drive.
5. Chat/transcript surface renders a mock transcript.
6. `.github/workflows/android.yml` CI is green on this branch.
7. The lane emits a structured JSON feedback contract on stdout (checks[], artifacts:
   logcat/screenshots/ui_tree) even on success — every check gets its own PASS/FAIL, not just
   an overall verdict.

**Host capability limits:** this is a headless Linux machine. Use headless emulator tooling
(`emulator -no-window`, ADB) — never assume a GUI or a human tester is available. If hardware
acceleration (`/dev/kvm`) is unavailable in this environment, name that as a BLOCKED item for
the emulator-dependent checks (items 3–5) rather than skipping them silently, and still
complete items 1, 2, 6, 7.

**Commit early, push always.** Never merge to main yourself — the orchestrator merges.

**Time bound:** 6 hours wall-clock. Exceeding it is a terminal BUDGET state — commit and push
whatever is real, do not rush a false completion.

**DONE.json:** write to the worktree root as your final act. Add `DONE.json` to
`.gitignore` before writing it. Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL),
branch, head, pushed, items[], residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No voice, host-tool, or ledger implementation — those are separate lanes (1.2, 1.3, 1.5).
- No production visual polish — mock data and functional UI only.
- No Android Auto surface.
- No nightly device-farm matrix (Firebase Test Lab) — that is coverage breadth, not this
  lane's gate.
- No AI-judged (Journeys) UI assertions as the merge gate — deterministic Compose tests only.

## KNOWN
- This machine is headless; use `emulator -no-window -no-boot-anim -wipe-data`, `adb`, and
  `android layout --pretty` for UI introspection rather than a GUI.
- Tag every card/element with `Modifier.testTag(...)` and a real `contentDescription` from day
  one so the UI tree stays machine-legible.
- muxterm is available in this environment for isolated terminal/session management if useful
  for running long builds.
