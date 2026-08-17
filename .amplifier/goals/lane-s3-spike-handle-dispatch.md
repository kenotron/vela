# Spike S-3 — spike-handle-dispatch

**Outcome:** A conclusive, evidence-backed answer to whether a handle-returning host tool
completes a chat-completions turn normally, and whether the resulting ledger record survives
`amplifier-agent`'s transcript reconciliation into a subsequent turn in the same session.

**Working directory / branch / base SHA:** worktree only, branch
`lane/s3-spike-handle-dispatch`, base SHA `dccf2daf`. Work ONLY in this worktree.

**File ownership:** `spikes/s3-dispatch/`. Read-only reference to `android/host-tools/` (lane
1.3, already merged, contains a working `dispatch_to_fleet` executor and stub ledger) and
`services/ledger/` (lane 2.1, if merged by the time this runs) — do not modify either; if
lane 2.1 hasn't merged yet, use lane 1.3's stub `InMemoryLedgerRepository` pattern or a minimal
standalone ledger stub inside `spikes/s3-dispatch/` instead of waiting.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. A handle-returning tool call (`dispatch_to_fleet`-shaped: writes a ledger record, returns
   `{job_id, status: "accepted"}`) is invoked against lane 1.4's stock `amplifier-agent serve`
   deployment; measure the tool returns in p99 < 1s over at least 10 calls.
2. The turn completes normally after the `{role: "tool", content: {job_id, ...}}` re-POST — no
   hang, no error, a final assistant message is produced.
3. A **subsequent** turn in the **same session** successfully retrieves the ledger record via
   a ledger-query tool (or a direct query against wherever the job was written) — this is the
   test of whether `amplifier-agent`'s transcript reconciliation silently deletes the orphaned
   `tool_use` block associated with the first turn's dispatch call.
4. Document the reconciliation behavior (Assumption A6) with direct evidence either way: does
   reconciliation delete/break anything relevant, or does it leave the ledger-external record
   untouched because the record lives outside the transcript entirely?

**Host capability limits:** headless Linux machine — this spike is pure HTTP/CLI, no emulator
needed. If it requires provider credentials unavailable in this environment, use a stub/mock
provider to still exercise the reconciliation mechanics, documenting the substitution.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 4 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act (`.gitignore` already covers it).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No fork of `amplifier-agent` — this spike tests stock behavior only.
- No real fleet-plane wiring.
- No Android UI verification — this is a server/API-level spike.

## KNOWN
- This spike tests Assumptions A5, A6, A9 together — it is called out in the design doc as
  "the sneaky-important one" because it is the only check that tests the *interaction*
  between the ledger and transcript reconciliation (failure mode F-6: job dispatched with no
  ledger record, or ledger record silently lost — invisible until discovered late).
- Lane 1.3 already found (via S-2/1.3's own testing) that the server requires `stream: true`
  for `tool_calls` to appear correctly — use streaming requests here too.
- Lane 1.4's stock `amplifier-agent serve` deployment is already merged and documented in
  `ops/README.md`.
