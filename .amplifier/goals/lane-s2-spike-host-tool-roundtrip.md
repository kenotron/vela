# Spike S-2 — spike-host-tool-roundtrip

**Outcome:** A conclusive, evidence-backed answer to whether the OpenCode client-declared-tool
pattern works from an Android client against stock `amplifier-agent serve`.

**Working directory / branch / base SHA:** worktree only, branch
`lane/s2-spike-host-tool-roundtrip`, base SHA `b4380a2e`. Work ONLY in this worktree.

**File ownership:** `spikes/s2-host-tool/`. Read-only access to `android/host-tools/` (lane
1.3's territory) is fine for reference; do not modify it — this spike may run standalone or
against a minimal harness inside `spikes/s2-host-tool/` rather than the real lane 1.3 code, to
avoid a file-ownership collision. If lane 1.3 hasn't merged yet, build a minimal standalone
Android/HTTP client harness for this spike rather than waiting.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. A minimal Android (or equivalent HTTP) client declares exactly one tool in the `tools:`
   field of a chat-completions request to lane 1.4's deployed stock `amplifier-agent serve`
   instance.
2. The model selects the tool; the client receives a `delta.tool_calls` chunk and the stream
   ends with `finish_reason: "tool_calls"`.
3. The client executes the tool locally (a trivial stub implementation is fine — this spike
   tests the wire protocol, not tool logic) and re-POSTs the result as `{role: "tool",
   content: ...}`.
4. The turn completes correctly after the re-POST (a final assistant message is returned).
5. Findings written up to `docs/spikes/s2-findings.md` (or `spikes/s2-host-tool/findings.md`
   fallback) including exact wire captures (request/response JSON) for each step above.

**Host capability limits:** headless Linux machine — this spike can run as a pure HTTP client
test (no emulator, no UI needed) since it is testing the wire protocol, not the Android UI
layer. Prefer a plain JVM/Python HTTP client harness over a full Android app if that reaches
the same evidence faster; note the substitution in findings if used.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 3 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act (`.gitignore` already covers it).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No real host-tool implementations (calendar/notes/reminders/dispatch_to_fleet) — lane 1.3
  owns those; this spike proves only the wire mechanics with a stub tool.
- No voice or ledger integration.

## KNOWN
- This is Assumption A4 in the design doc: a host-declared, client-executed tool transfers
  cleanly to an Android client over the stock chat-completions wire, following the pattern
  already proven by `amplifier-app-opencode`.
- Lane 1.4's stock `amplifier-agent serve` deployment is already merged to main and documented
  in `ops/README.md` at this base SHA — use its documented reachability path.
