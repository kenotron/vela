# Lane MVP-B — agentd-production-up

**Outcome:** `vela-agentd` (the fork from lane 3.1 — event tee, real approval gate, C2/C3
routes) is running for real on `vela0` with a real LLM provider credential, reachable over
Tailscale, health-checked, and its connection details (base URL, auth token location) are
documented for the Android app to consume.

**Working directory / branch / base SHA:** worktree only, branch
`lane/mvp-b-agentd-production-up`, base SHA `c1e3d918`. Work ONLY in this worktree.

**File ownership:** `ops/vela-agentd/`, `services/vela-agentd/` (configuration only — the
fork's code from lane 3.1 is already correct and should not need edits; if it does, record
that as a residual rather than modifying `services/vela-agentd/src/` freely). Read-only
reference to `ops/agent-serve/` (lane 1.4, already running) for the reachability/health-check
pattern to mirror.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. `vela-agentd` installed and running under systemd (per `ops/vela-agentd/vela-agentd.service`
   from lane 3.1) as a drop-in replacement for lane 1.4's stock `vela-agent-serve` — either
   alongside it on a different port, or replacing it (your call; document which and why).
2. A real LLM provider credential is configured for `vela-agentd` (reuse the same credential
   already working for `vela-agent-serve` if that's simplest — documented in
   `~/.amplifier/vela-agent-serve/env` on this host).
3. `ops/vela-agentd/health-check.sh` passes against the real running instance, run both
   locally (127.0.0.1) and against the Tailscale address (`100.84.25.57` or whatever the
   current tailnet IP is — verify it hasn't changed) — mirroring the verification already done
   for stock `agent-serve` in `ops/README.md`.
4. C2 route smoke test: connect a minimal SSE client to `/v1/events` on the running instance
   and confirm it receives a real event during a real chat-completions turn (reuse or adapt
   the approach from `spikes/s1-event-tee/` or lane 3.1's own `test_c1_identity.py`/
   `test_ledger_proxy.py` test harnesses rather than writing a new one from scratch).
5. C3 ledger proxy smoke test: confirm `vela-agentd`'s ledger routes correctly proxy to lane
   2.1's `services/ledger/` instance (also needs to be running — start it if not already, per
   `ops/ledger/install.sh`).
6. Document the exact base URL, port, and auth-token location the Android app (lane MVP-A)
   needs, in `ops/README.md` under a new "vela-agentd (production)" section — this is the
   deliverable lane MVP-A's config lane depends on.

**Host capability limits:** this is server-side work with no emulator/device dependency —
fully doable on this headless host. If Tailscale connectivity has changed since it was last
verified, re-verify rather than assume; do not skip item 3's live network check.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 3 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act. Never commit DONE.json — verify
`git status` shows it untracked before finishing. Fields: `lane, session_id, verdict
(COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[], residuals[], pending_human[],
suite`.

## SCOPE-OUTS
- No changes to `services/vela-agentd/src/` fork code itself — it is already built and
  verified (lane 3.1, byte-identical C1, 4/4 fork points at budget). This lane is deployment
  and configuration only.
- No decision about long-term production hosting (multiple hosts, failover) — that's roadmap
  item #42, a separate future decision. This lane just needs ONE real running instance,
  reachable, documented.
- No Android-side work — that's lane MVP-A, running concurrently.

## KNOWN
- Lane 3.1's fork already passed: C1 byte-identical to stock over 100 turns, approval gate's
  adversarial no-client-attached test (timeout-to-deny), and a live ledger-proxy test against
  a real ledger instance on port 9198 — the fork itself is trustworthy; this lane is purely
  "turn it on for real and prove it's reachable."
- Item 7 from lane 3.1's own DONE.json was left `PARTIAL` for exactly this reason: "could not
  run a live competing systemd service on 9099 because lane 1.4's stock service is currently
  active on that port." This lane is where that gets resolved for real.
- `ops/README.md` already documents the Tailscale reachability pattern for stock
  `agent-serve` — mirror it, don't reinvent it.
