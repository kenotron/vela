# Lane 1.5 — phone-ledger

**Outcome:** A local SQLite ledger implementing the `LedgerRepository` interface exists,
schema-matched to the C3 job resource (design doc §4.2), providing the D+ milestone's
durability story and later serving as the offline mirror of the server ledger.

**Working directory / branch / base SHA:** worktree only, branch `lane/1.5-phone-ledger`, base
SHA `b4380a2e`. Work ONLY in this worktree.

**File ownership:** `android/ledger/`, `android/ledger/src/main/.../db/`. You may READ but not
modify `android/core-domain/.../LedgerRepository.kt` (owned by lane 1.1, already merged) — if
it needs a change, record that as a residual rather than editing it.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. SQLite schema modeled against the design doc's C3 `job` resource exactly: `job_id,
   created_at, updated_at, origin{session_id,turn_id,tool_call_id}, spec, status
   (accepted|running|needs_attention|blocked|done|failed|cancelled), attention{required,
   reason, options, deadline}, progress[], result, cost{usd,tokens}`.
2. Jobs persist across app kill and simulated device reboot. Verified by writing a job,
   killing the app process (`adb shell am force-stop`), relaunching, and confirming the job
   is still present via the repository's query API.
3. The attention query (jobs where `attention.required == true`) is implemented and would
   drive a card deck (a card-deck consumer is lane 1.1's concern; this lane only needs to
   prove the query returns correct results against seeded test data).
4. Decisions are recorded (a decision write updates `status` and clears `attention.required`
   as appropriate) — verified with a round-trip test.
5. Zero lost events (G1) passes for the local-only case: inject a known-cardinality sequence
   of job creates/updates, force-kill mid-sequence, restart, and confirm the final state
   matches exactly what should have survived — no silent drops.
6. Schema is documented in `android/ledger/README.md` (or equivalent) and explicitly
   cross-referenced against the C3 job resource in the design doc, including a note on the
   row-level `updated_at` / server-authoritative conflict-resolution fields needed for the
   future sync/mirror mode (design for it now even though nothing syncs yet — do not
   implement sync in this lane).

**Host capability limits:** headless Linux machine. Items 2 and 5 require `adb
shell am force-stop` and app relaunch — if `/dev/kvm` is unavailable (as found in lane 1.1),
name that as BLOCKED for those two items specifically, and complete items 1, 3, 4, 6 via
local/instrumented unit tests that don't require a booted emulator where possible (e.g. Robolectric
or a plain JVM unit test against the SQLite layer), noting the substitution explicitly.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 6 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act (`.gitignore` already covers it).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No server-side ledger service — that is Stage 2, lane 2.1, a separate standalone service.
- No actual sync/mirror logic between phone-local and server ledger — only the schema fields
  needed to support it later.
- No card-deck UI — that is lane 1.1's concern; this lane proves the query, not the rendering.

## KNOWN
- This schema becomes the reference lane 2.1's server-side C3 REST API must not diverge from.
- Lane 1.1 (domain interfaces) is already merged to main at this base SHA.
- muxterm is available in this environment for isolated session/process management.
