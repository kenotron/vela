# Goal: Real approval gate for privileged tools (#44, #45, #46, #57)

## Working directory / branch / base
Work ONLY in this worktree. Do not touch the main checkout or sibling worktrees.
- Branch: `lane/approval-gate`
- Base SHA: `4eecab4b88091755dba0ae618cfe798b06fdab01`

## File ownership
You own:
- `android/events/src/main/java/com/vela/events/ApprovalClient.kt`
- `android/events/src/main/java/com/vela/events/ApprovalVoiceBridge.kt`
- `android/events/src/main/java/com/vela/events/C2Event.kt` (ApprovalRequested-related only)
- Any NEW file under `android/events/src/**` or `android/host-tools/src/**` needed for privileged-tool classification and the approval gate itself.
- Server-side agentd approval-gate code if it exists in this repo (search for it — do not assume a path).

If you need to touch a file outside this list (e.g. `DispatchToFleetTool.kt`, owned by the sibling `fleet-plane` lane), do NOT edit it. Record the exact edit needed as a residual in DONE.json and stop.

## Context — read first
- `docs/designs/2026-08-16-vela-chief-of-staff-rebuild.md` — the approval/trust model (§ on approvals), D1-D5 fleet contract.
- `docs/designs/2026-08-24-vela-fleet-execution-plane.md` — the newly-hired fleet plane design; this is what surfaced #57.
- GitHub issues (read via `gh issue view <n>` if `gh` is available in this worktree; otherwise use the text below):
  - **#45 Privileged-tool classification**: define which tools require approval before executing (calendar writes, fleet dispatch with real credentials, anything mutating outside a sandbox). Produce a real classification (code + a doc table), not just a comment.
  - **#44 Real approval gate (F2), timeout-to-deny**: a privileged tool call blocks until a human approves/denies via the existing `ApprovalClient`/`ApprovalVoiceBridge` duplex channel, or times out — and a timeout MUST deny (fail closed), never silently proceed.
  - **#46 Adversarial verification: zero privileged tools reachable unapproved**: write a real test (unit or integration) that attempts to reach every tool classified as privileged in #45 without an approval, and asserts it is blocked. This is the proof, not a self-report.
  - **#57 Fleet jobs run with real credentials and NO approval gate — larger authority than F2 protects**: the specific gap that motivated this whole lane. `dispatch_to_fleet` (in `DispatchToFleetTool.kt`, NOT yours to edit) currently has no approval gate despite running with real credentials. Since you don't own that file, your job is to build the classification + gate + verification machinery generically enough that wiring `dispatch_to_fleet` into it is a one-line residual for the fleet-plane lane (or a follow-up) — name that residual explicitly in DONE.json with the exact call site.

## Host capability limits
No live device/emulator is guaranteed reachable from this worktree. Use unit/integration tests with fakes/mocks for the approval duplex channel. Do not claim device verification you didn't actually perform.

## Complete when
Complete when **either** every item (#44, #45, #46, #57-as-scoped-above) reaches a terminal state, **or** it is conclusively demonstrated the remainder cannot, naming the blocker for each. Items ending FAIL or BLOCKED are residuals, not failures of the goal.

Terminal states per item: PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Process discipline
- Commit early, push often (`git push -u origin lane/approval-gate` on first commit, then push every commit).
- Never merge to main yourself — the orchestrator (Ditto) does that.
- Time bound: 90 minutes wall-clock. Exceeding it is a `BUDGET` terminal state — commit and push what you have, write DONE.json, stop. Do not rush the last item to beat the clock.
- `DONE.json` is gitignored already (repo-level). Write it at the worktree root as your FINAL act, fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[], residuals[], pending_human[], suite`.
- Run whatever test suite exists for `android/events` and `android/host-tools` (gradle) before writing DONE.json; report actual pass/fail counts, not "BUILD SUCCESSFUL".
