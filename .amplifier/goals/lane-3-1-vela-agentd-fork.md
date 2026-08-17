# Lane 3.1 — vela-agentd-fork

**Outcome:** `amplifier-agent`'s HTTP face is forked into `vela-agentd`, implementing the event
tee (F1, already spiked and proven non-perturbing in S-1), a real approval gate replacing
unconditional auto-approve (F2), a new C2 event/control route (F3), and C3 ledger route
wiring/proxy to lane 2.1's ledger service (F4) — deployable as a drop-in replacement for lane
1.4's stock deployment.

**Working directory / branch / base SHA:** worktree only, branch `lane/3.1-vela-agentd-fork`,
base SHA `3883d8b9`. Work ONLY in this worktree.

**File ownership:** `services/vela-agentd/` (vendored fork), `ops/vela-agentd/`,
`docs/FORK_POINTS.md`. Read-only reference to `spikes/s1-event-tee/` (the proven tee
implementation and diff, already merged) and `services/ledger/` (lane 2.1's C3 API, already
merged) — do not modify either; wire against them.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. Fork `amplifier-agent`'s HTTP face into `services/vela-agentd/`, applying the F1 event tee
   using the exact mechanism proven in `spikes/s1-event-tee/tee-implementation.diff` (non-
   blocking, bounded buffer, drops under pressure) — adapt as needed for the real second
   consumer (C2), but do not redesign the tee mechanism itself; it is already proven.
2. C2 route (F3): a new SSE (or WebSocket) route exposing tee'd events
   (`tool/started`, `tool/completed`, `progress`, `thinking/delta`, `thinking/final`, `usage`,
   `error`) with the `agentName` field populated for delegated sub-agents, matching the payload
   shapes documented in the design doc §4.2.
3. Real approval gate (F2): replace unconditional auto-approve with a gate that suspends a
   privileged tool call, emits an approval request on C2, and resolves via a C2 client decision
   or times out to a configurable default (deny). Adversarial test: attempt a privileged tool
   call with no C2 client attached — must timeout and deny, never hang the server (F-4).
4. C3 route wiring (F4): proxy or wire `vela-agentd`'s ledger-related routes to lane 2.1's
   `services/ledger/` C3 REST API (do not reimplement the ledger; wire to the existing
   service).
5. C1 (chat-completions) output is byte-identical to stock `amplifier-agent serve` for the same
   inputs — verify with the same 100-turn comparison methodology used in S-1.
6. `docs/FORK_POINTS.md` written and maintained as a living document: each fork point, its
   upstream location, and its rationale. **If the count exceeds 4 localized points, stop and
   name that explicitly as a residual** rather than silently exceeding it (design doc §7.4
   signal 1) — do not try to force it under 4 by hiding scope.
7. Deploys as a drop-in replacement for lane 1.4's stock `agent-serve-ops` deployment: same
   port/health-check contract, verified by running `ops/agent-serve/health-check.sh` (or an
   adapted equivalent) against the forked service.

**Host capability limits:** headless Linux machine — this is server-side work, fully testable
via HTTP/CLI. If `amplifier-agent` requires live LLM provider credentials unavailable in this
environment, use lane 1.4's existing provider credential setup (already configured and
documented in `ops/README.md`) or a stub/mock provider, documenting whichever is used.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 10 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act. **Never commit DONE.json** —
it must remain a worktree-local, gitignored artifact (this was a real regression in an earlier
lane in this batch; do not repeat it — verify `git status` shows it untracked before finishing).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No mid-turn steering (§8.5) — that is Stage 4 (lane 4.1), explicitly deferred and not part
  of this rebuild effort right now.
- No Android C2 client — that is lane 3.2, sequenced strictly after this lane.
- No changes to lane 2.1's ledger service internals — wire to its existing API only.

## KNOWN
- S-1 (already merged, `spikes/s1-event-tee/`) proved the tee mechanism is non-perturbing to
  C1 with 100-turn byte-identical comparison and `agentName` populated for sub-agents. This
  lane productionizes that proven mechanism — it does not need to re-derive it.
- B2 in the design doc: target ≤4 localized fork points. This is the property that makes the
  fork acceptable and reversible (§7.2 reversibility rating).
- Lane 1.3 and s2 both found the server requires `stream: true` for `tool_calls` to appear
  correctly on C1 — preserve that behavior; C1 must remain byte-identical to stock.
- muxterm is available in this environment for isolated session/process management.
