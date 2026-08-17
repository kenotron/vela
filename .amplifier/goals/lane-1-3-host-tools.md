# Lane 1.3 — host-tools

**Outcome:** Android-side host-tool executors (calendar, notes, reminders, `dispatch_to_fleet`)
exist, each with a JSON-schema declaration and the client-side OpenCode-pattern tool loop
(receive `delta.tool_calls` → execute → re-POST `{role:"tool"}`), completing turns correctly
against stock `amplifier-agent serve`.

**Working directory / branch / base SHA:** worktree only, branch `lane/1.3-host-tools`, base
SHA `b4380a2e`. Work ONLY in this worktree.

**File ownership:** `android/host-tools/`. You may READ but not modify
`android/core-domain/.../HostTool.kt` (owned by lane 1.1, already merged) — if it needs a
change, record that as a residual rather than editing it.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. `calendar_*` (read/create/modify) executor implemented against the Android Calendar
   Provider. Verified by writing a UUID-stamped test event and querying the real,
   out-of-process provider for it via `adb shell content query --uri
   content://com.android.calendar/events` — not an in-app assertion.
2. `notes_*` executor implemented against a local store.
3. `reminders_*` executor implemented against Android alarm/notification APIs.
4. `dispatch_to_fleet` executor implemented against a **stub** fleet plane: writes a ledger
   record (to whatever ledger implementation exists at merge time — coordinate via a narrow
   interface, do not hardcode phone-local SQLite specifics) and returns a handle. Instrument an
   assertion in the executor base class enforcing the <1s / handle-returning contract so
   violations fail loudly; measure p99 < 1s over at least 10 calls against the stub.
5. Each tool declares a valid JSON schema.
6. The client-side tool loop (receive `delta.tool_calls` → execute locally → re-POST
   `{role:"tool", content:...}`) completes turns correctly against stock `amplifier-agent
   serve` (deployed by lane 1.4, already merged — use its documented reachability path).

**Host capability limits:** headless Linux machine. Runtime permissions (`RECORD_AUDIO`,
`READ/WRITE_CALENDAR`, `POST_NOTIFICATIONS`) are automatable via `pm grant`. If `/dev/kvm` is
unavailable, name that as BLOCKED for every item requiring a live emulator boot (all of them,
since these are on-device provider writes) rather than skipping silently — CI's
instrumented-tests job (added in lane 1.1, GitHub-hosted, KVM-backed) is the fallback
verification path; note that explicitly per item.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 8 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act (`.gitignore` already covers it).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No real fleet plane wiring — `dispatch_to_fleet` targets a stub only in this lane. Real
  fleet wiring is a separate design/stage, out of scope entirely for this repo.
- No voice integration (lane 1.2) or ledger implementation details beyond the narrow interface
  needed to write a stub job record (lane 1.5 owns the real ledger).
- No approval-gate UI (that is Stage 3, lane 3.2).

## KNOWN
- Any host-tool call ends the SSE stream immediately per the design doc (A5) — any tool that
  could take longer than ~2s must be handle-returning; `dispatch_to_fleet` is the canonical
  case and this is enforced as a binary gate (G3) in the full design.
- Lane 1.4's stock `amplifier-agent serve` deployment and lane 1.1's domain interfaces are
  already merged to main at this base SHA.
- muxterm is available in this environment for isolated session/process management.
