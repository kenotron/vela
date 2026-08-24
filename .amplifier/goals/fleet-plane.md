# Goal: Fleet execution plane contract conformance (#43, #40, #41)

## Working directory / branch / base
Work ONLY in this worktree. Do not touch the main checkout or sibling worktrees.
- Branch: `lane/fleet-plane`
- Base SHA: `4eecab4b88091755dba0ae618cfe798b06fdab01`

## File ownership
You own:
- `android/host-tools/src/main/java/com/vela/hosttools/DispatchToFleetTool.kt`
- `android/host-tools/src/test/java/com/vela/hosttools/DispatchToFleetToolTest.kt`
- Any FleetPlane/StubFleetPlane implementation files under `android/host-tools/src/**`
- `android/host-tools/src/main/java/com/vela/hosttools/InMemoryLedgerRepository.kt` (ledger cost-accounting fields, #39, if time allows — lower priority than #43/#40/#41)

Do NOT touch anything under `android/events/**` — that's the sibling `approval-gate` lane. If dispatch needs to call an approval gate, add a clearly-marked TODO/interface seam and record it as a residual naming the exact integration point (e.g. "call ApprovalClient.requestApproval(...) here once #44 lands") — do not implement the approval logic yourself.

## Context — read first
- `docs/designs/2026-08-24-vela-fleet-execution-plane.md` — THE spec for this lane. Follow it directly: dial-in/heartbeat architecture (not on-demand probing), D1-D5 contract, muxterm as observability-only, single-ledger-writer credential collapse.
- `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md` §1.3, §D1-D5 — the original contract this design satisfies.

## Issues in scope
- **#43 Fleet plane contract conformance (D1-D5)**: implement/verify the actual conformance to the D1-D5 contract per the new design doc. This is the umbrella item — the design doc names concrete components; build them.
- **#40 dispatch_to_fleet: handle-returning contract, <1s, always**: `dispatch_to_fleet` must return a handle immediately (<1s p99) and never block on the actual remote work — write a ledger record synchronously, dispatch async, return. There's an existing `DispatchToFleetToolTest.kt` with a p99 latency test — extend/complete it with a REAL wall-clock measurement (not a mocked instant-return that proves nothing).
- **#41 Synchronous reachability check at dispatch time**: per the design's dial-in/heartbeat model, dispatch time should check the target's last-heartbeat freshness synchronously (not a live probe) to decide reachable/unreachable before accepting the job.

## Host capability limits
No live multi-machine fleet is reachable from this worktree — you do not have real remote hosts to dial into. Build against the `FleetPlane`/`StubFleetPlane` abstraction with a real, deterministic fake that simulates heartbeat freshness and async completion (e.g. a fake clock + in-memory heartbeat store), and write real tests against that fake. Do not claim live multi-machine verification you didn't perform — name it as untested/BLOCKED if the design doc requires something this environment genuinely cannot provide (e.g. real muxterm cross-machine driving), and say exactly what would be needed to verify it for real.

## Complete when
Complete when **either** every item (#43, #40, #41) reaches a terminal state, **or** it is conclusively demonstrated the remainder cannot, naming the blocker for each. Items ending FAIL or BLOCKED are residuals, not failures of the goal.

Terminal states per item: PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Process discipline
- Commit early, push often (`git push -u origin lane/fleet-plane` on first commit, then push every commit).
- Never merge to main yourself — the orchestrator (Ditto) does that.
- Time bound: 90 minutes wall-clock. Exceeding it is a `BUDGET` terminal state — commit and push what you have, write DONE.json, stop.
- `DONE.json` is gitignored already (repo-level). Write it at the worktree root as your FINAL act, fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[], residuals[], pending_human[], suite`.
- Run the `android/host-tools` gradle test suite before writing DONE.json; report actual pass/fail counts, not "BUILD SUCCESSFUL".
