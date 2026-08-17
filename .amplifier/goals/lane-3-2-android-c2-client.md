# Lane 3.2 — android-c2-client

**Outcome:** An Android C2 client exists that consumes the tee'd events exposed by lane 3.1's
`vela-agentd` C2 route, drives a live activity UI with correct sub-agent attribution, surfaces
approval prompts (visual + spoken), and feeds voice narration (V5).

**Working directory / branch / base SHA:** worktree only, branch `lane/3.2-android-c2-client`,
base SHA `beb500f4`. Work ONLY in this worktree.

**File ownership:** `android/events/`, `android/app/src/main/.../ui/activity/`,
`android/voice/.../Narrator.kt`. You may READ but not modify lane 1.2's `android/voice/` module
beyond adding `Narrator.kt` in its own namespace, and may READ but not modify lane 3.1's
`services/vela-agentd/` C2 route implementation — if the C2 wire protocol needs a change,
record that as a residual rather than editing the server.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. Android C2 client connects to `vela-agentd`'s C2 SSE/WebSocket route (lane 3.1, already
   merged, documented in `docs/FORK_POINTS.md` and `services/vela-agentd/src/vela_agentd_http/
   routes/events.py`) and consumes tee'd events (`tool/started`, `tool/completed`, `progress`,
   `thinking/delta`, `thinking/final`, `usage`, `error`).
2. Live activity UI renders tool activity with correct sub-agent attribution during parallel
   delegation. Apply the `eb207e74` lesson (preserved in `docs/PRESERVED_LESSONS.md` if present,
   otherwise re-derive from the design doc §10 Stage 0 table): correlate streaming chunks to
   the right tool call using claim-first-unclaimed semantics or an equivalent explicit
   correlation strategy — never assume single-delegate ordering.
3. Approval prompts (from lane 3.1's F2 gate) appear in the UI within 2s of the gate firing on
   the server side, and can be answered both by touch (visual prompt) and by voice (spoken
   confirmation via the existing `VoiceTransport`/PTT surface from lane 1.2).
4. `Narrator.kt` feeds real C2 events into voice narration (V5) — narrating real tool/delegation
   activity, never synthetic reassurance loops ("still thinking…" style filler is explicitly
   disallowed per V4).
5. A 5-way parallel delegation scenario (simulated via a test harness against the C2 event
   stream, not necessarily a live 5-way LLM delegation) renders all five correctly attributed
   with zero cross-assignment — this is the correlation-strategy stress test for item 2.
6. Narration inter-event gap p99 < 5s when real C2 events are flowing at a realistic cadence
   (measured against a simulated or replayed event stream if a live long-running agent turn
   isn't available in this environment).

**Host capability limits:** headless Linux machine. If `/dev/kvm` is unavailable (as found
throughout Stage 1), name that as BLOCKED for any item requiring a live emulator boot, and
verify the C2 client's parsing/correlation/attribution logic via JVM/Robolectric unit tests
against replayed or synthetic C2 event streams instead — this is the same substitution pattern
lane 1.5 used successfully. Approval-prompt touch/voice integration (item 3) may need to defer
its on-device portion to CI's KVM-backed runner while the logic itself is unit-verified here.

**Commit early, push always.** Never merge to main yourself.

**Time bound:** 8 hours wall-clock. Exceeding it is a terminal BUDGET state.

**DONE.json:** write to the worktree root as your final act. **Never commit DONE.json** — it
must remain untracked (verify `git status` before finishing; this was a real regression earlier
in this batch).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No mid-turn steering — narration-only remains the accepted, documented degradation (§8.5);
  do not attempt to build steering in this lane.
- No changes to the C2 wire protocol itself (owned by lane 3.1) — consume it as-is; if a gap is
  found, record it as a residual for a future protocol-revision lane.
- No fleet-plane integration.

## KNOWN
- Lane 3.1's C2 route and event/approval shapes are already merged and documented in
  `docs/FORK_POINTS.md` and `services/vela-agentd/src/vela_agentd_http/_c2_shapes.py` — use
  those as the wire contract, do not guess at shapes.
- Lane 1.2's `VoiceTransport`/PTT/earcon surface (already merged) is what this lane's approval
  prompts and narration hook into.
- This is the last lane in this rebuild effort's current scope — mid-turn steering (lane 4.1)
  is explicitly deferred until the rest of the system is proven out.
- muxterm is available in this environment for isolated session/process management.
