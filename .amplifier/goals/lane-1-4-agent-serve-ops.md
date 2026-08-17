# Lane 1.4 — agent-serve-ops

**Outcome:** A stock, unmodified `amplifier-agent serve chat-completions` instance runs under
process supervision, is reachable from a phone over Tailscale, enforces auth, and has
documented health checks and a one-command redeploy.

**Working directory / branch / base SHA:** worktree only, branch `lane/1.4-agent-serve-ops`,
base SHA `688a1834`. Work ONLY in this worktree.

**File ownership:** `ops/`, `ops/agent-serve/`, `ops/README.md`. If you need to touch any file
outside this list, stop, record the needed edit as a residual, do not make the edit.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. `amplifier-agent serve chat-completions` deployed on this host, run under a supervisor
   (systemd unit or equivalent), survives a simulated host reboot (service restart) with no
   manual intervention.
2. Reachable from a simulated remote client over Tailscale (or documented as BLOCKED with the
   named reason if this environment has no Tailscale access — do not fabricate reachability).
3. Auth enforced on the endpoint (API key, mTLS, or documented equivalent) — verify with an
   unauthenticated request that is rejected and an authenticated one that succeeds.
4. Health check endpoint/script documented and working; log tailing documented.
5. A documented one-command redeploy script exists and has been exercised at least once.
6. Reachability model documented in `ops/README.md`, including a multi-path fallback strategy
   (e.g. Tailscale → LAN → direct) analogous to a multi-URL connectivity approach.

**Host capability limits:** this is a headless Linux machine with no phone hardware. Simulate
the "phone" side with a plain HTTP/curl client from a separate network namespace or host if
Tailscale is available; if genuinely unavailable, name that as BLOCKED for item 2 specifically
and complete the rest.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 4 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act (add to `.gitignore` first).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No fork of `amplifier-agent` — this lane deploys it stock, unmodified. The fork is Stage 3
  (lane 3.1), separate.
- No Android client work.
- No ledger service (that is lane 2.1).
- This service must remain a drop-in-replaceable target: do not couple ops tooling to
  anything that would prevent Stage 3 from swapping in `vela-agentd` later.

## KNOWN
- This deployment is what the D+ milestone and Spikes S-2/S-3 run against — it must be
  reachable before those spikes can proceed.
- muxterm is available in this environment for session/process management if useful.
