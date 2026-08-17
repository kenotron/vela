# Spike S-1 — spike-event-tee

**Outcome:** A conclusive, evidence-backed answer to whether a second consumer can read
`amplifier-agent`'s internal event queue (tee) without perturbing the chat-completions (C1)
path, with fork points documented.

**Working directory / branch / base SHA:** worktree only, branch `lane/s1-spike-event-tee`,
base SHA `688a1834`. This lane operates against a **throwaway branch of `amplifier-agent`**
(a separate repo/checkout) plus its own findings directory in this repo. Work ONLY in this
worktree and the throwaway `amplifier-agent` branch; never push the throwaway branch anywhere
persistent.

**File ownership:** `spikes/s1-event-tee/` in this repo (findings, scripts, evidence). The
throwaway `amplifier-agent` branch is scratch space, not owned by any other lane.

**Complete when** either every item below reaches a terminal state, **or** it is conclusively
demonstrated the remainder cannot, naming the blocker for each. Terminal states:
PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

Items:
1. Identify the exact internal event queue location in `amplifier-agent`'s HTTP face
   (principally `_session_runner.py`) where `tool/started`/`tool/completed`/`progress`/
   `thinking/*`/`usage`/`error` events are currently discarded before the wire.
2. Implement a tee: a second consumer fans out from that queue alongside the existing
   (discarding) one, non-blocking, with a bounded buffer that drops tee'd events under
   pressure rather than blocking the primary consumer.
3. Run 100 consecutive tool-calling turns with the tee attached and 100 without it; diff the
   C1 output — must be byte-identical.
4. Confirm the tee'd stream includes `tool/started` and `tool/completed` with a populated
   `agentName` field for at least one delegated sub-agent turn.
5. Write findings to `docs/spikes/s1-findings.md` (or `spikes/s1-event-tee/findings.md` if
   `docs/spikes/` doesn't exist in this worktree) including: exact fork point file/line
   locations, the tee mechanism used, the 100-turn diff result, and a pass/fail verdict on the
   central question (can a second consumer tee this queue without perturbing C1?).

**Host capability limits:** this is a headless Linux machine — all verification here is via
CLI/HTTP, no GUI needed. If running `amplifier-agent` locally requires resources unavailable
in this environment (e.g. no LLM provider credentials), name that as BLOCKED for the specific
item and use a stub/mock provider to still exercise the queue mechanics, documenting the
substitution explicitly in the findings.

**Commit early, push always** (to this repo's branch — the throwaway `amplifier-agent` branch
is never pushed persistently). Never merge to main yourself.

**Time bound:** 5 hours wall-clock. Exceeding it is a terminal BUDGET state — write up
whatever was found, including partial evidence, and stop.

**DONE.json:** write to the worktree root as your final act (add to `.gitignore` first).
Fields: `lane, session_id, verdict (COMPLETE|BLOCKED|PARTIAL), branch, head, pushed, items[],
residuals[], pending_human[], suite`.

## SCOPE-OUTS
- No implementation of the real approval gate (F2), C2 route (F3), or C3 routes (F4) — this
  spike only proves the tee mechanism (F1). Those are lane 3.1's scope, gated on this spike.
- No Android-side work.
- No requirement to make the tee production-ready or rebaseable yet — that refinement happens
  in lane 3.1 if this spike passes.

## KNOWN
- This is Assumption A3 in the design doc and is explicitly load-bearing: if this spike fails,
  Stage 3 (the `vela-agentd` fork) may need to become a two-surface design (candidate B)
  instead of a thin fork.
- The design doc source comment on the discarding queue reads roughly "internal activity stays
  internal" — that is the exact line this spike needs to locate and tee.
