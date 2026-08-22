# Lane MVP-A — app-real-config

**Outcome:** The Android app (`android/app/`) is wired to talk to a real, running
`vela-agentd`/ledger stack instead of mock data — via its composition root only. Card
deck, chat, and host-tool calls all hit the real server.

**Working directory / branch / base SHA:** worktree only, branch `lane/mvp-a-app-real-config`,
base SHA `c1e3d918`. Work ONLY in this worktree.

**File ownership:** `android/app/` (composition-root / DI wiring, config source, and the
Queue/Chat screens' data source only). Do NOT modify `android/core-domain/`,
`android/events/`, `android/ledger/`, `android/host-tools/`, or `android/voice/` internals —
if a real gap is found in one of those modules, record it as a residual, do not edit it.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. A real config source (e.g. `BuildConfig` fields populated from `local.properties`, or a
   simple settings screen — your call, document which) supplies: server base URL, bearer
   auth token, and whichever of these `LedgerRepository`, the C1 tool-loop client
   (`AmplifierToolLoopClient`), and the C2 event client (`OkHttpC2EventClient`) need to
   connect for real. No hardcoded `localhost`/placeholder values in the shipped composition
   root.
2. `MainActivity`'s composition root constructs and wires: a real `LedgerRepository`
   implementation pointed at the config's base URL (server-backed, not the mock/in-memory
   one used in earlier scaffolding), a real `AmplifierToolLoopClient`, and a real
   `OkHttpC2EventClient` — replacing whatever mock/demo wiring exists today.
3. The Queue tab renders from the real `LedgerRepository`'s attention query, not mock data.
   Verify with a real ledger record (create one against the running server, e.g. via curl or
   the ledger's REST API) and confirm it appears in the app's Queue tab via `ui_dump` on a
   real device or emulator.
4. The Chat tab sends a real message through the C1 tool-loop client to the server and
   displays a real model response — verify end-to-end against the actual running server
   (lane MVP-B's deliverable; if MVP-B hasn't landed yet, verify against whatever
   `vela-agent-serve`/`vela-agentd` instance is currently reachable and documented in
   `ops/README.md`).
5. Remove or clearly gate behind a debug-only flag any remaining mock-data fallback paths
   in the Queue/Chat screens, so a real user cannot accidentally end up looking at fake data
   in a release-configured build.

**Host capability limits:** this is a headless Linux machine, but `/dev/kvm` and a real
booted emulator (`vela-test-avd`) ARE now available, plus a paired physical device
(`10.0.0.106:43369`, wireless adb) — use either for on-device verification of items 3-4;
do not mark them BLOCKED-no-kvm, that blocker no longer applies on this host. Delegate
device interaction to the `android-tester:android-operator` agent rather than driving adb
directly.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 3 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act. Never commit DONE.json — verify
`git status` shows it untracked before finishing (this was a real regression in an earlier
batch in this repo; do not repeat it). Fields: `lane, session_id, verdict
(COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[], residuals[], pending_human[],
suite`.

## SCOPE-OUTS
- No voice wiring — LiveKit real SDK integration stays out of scope for this MVP lane.
- No real fleet-plane wiring — `dispatch_to_fleet` stays against its existing stub.
- No production deployment decisions (which host runs vela-agentd long-term) — that is
  lane MVP-B's and a separate roadmap item (#42), not this lane's concern. This lane only
  needs a config mechanism that CAN point at whatever host is given to it.

## KNOWN
- Config/credential values for the currently-reachable dev instance are documented in
  `ops/README.md` (Tailscale address `100.84.25.57:9099`, bearer key in
  `~/.amplifier/vela-agent-serve/env` on that host — do not hardcode the literal key value
  into the repo; read it into local dev config only).
- Lane MVP-B is running concurrently and will document the vela-agentd-specific
  base-URL/token this app should ultimately point at — if it lands first, prefer its
  documented values over the stock agent-serve ones.
- The android-tester bundle (`android_inspector` tool, `android-tester:android-operator`
  agent) is installed and working on this host — use it for all device interaction.
