# Goal: Attention-rule tuning from real usage examples (#31 + #49)

Working directory: THIS worktree only. Do not touch the main checkout or sibling worktrees.
Branch: goal-batch/attention-rule-examples-tuning
Base SHA: bcb98531

## Outcome

Issues #31 and #49 are the same unresolved design-doc question from two angles
(#31 general, #49 notification-specific) — treat as ONE lane, do not split.

The design doc names "needs a decision vs just progress" examples as the
highest-leverage unresolved question for attention-rule quality (epics #14,
#19). Concretely:

1. Produce at least 3 concrete real (or realistically-synthesized from actual
   code paths in this repo, e.g. real AttentionCandidate-producing call sites)
   examples of "needs a decision" and 3 of "just progress" — written down as a
   durable artifact (e.g. `docs/examples/attention-rule-examples.md` or similar
   — your call), not just asserted in a commit message.
2. Encode the rule: inspect the existing attention-classification logic (grep
   for `AttentionCandidate`, `requiresAttention` in `android/core-domain`) and
   either confirm the current rule already correctly classifies all 6 examples
   (with a new unit test proving each), or tighten/tune the rule where an
   example proves it wrong, with a unit test for the fix.
3. This is evidence-based tuning, not a rewrite — do not introduce a new
   classification framework. Prefer the smallest correct change.

## File ownership

Own:
- `android/core-domain/src/main/java/com/vela/core/domain/notification/**`
- `android/core-domain/src/test/java/com/vela/core/domain/notification/**`
- `docs/examples/**` (new directory, your naming)

Do NOT touch: `android/core-ui/**`, `android/app/**`, anything under
`android/` outside `core-domain/notification`. If a needed change falls
outside your owned files, record it as a named residual in DONE.json instead.

## Host capability limits

No physical Android device available. Verify via JVM unit tests
(`./gradlew :core-domain:testDebugUnitTest` run from `android/`). Do not claim
device verification you did not perform.

## Terminal states

Complete when **either** both items above reach a terminal state, **or** it is
conclusively demonstrated the remainder cannot, naming the blocker for each.
Items ending FAIL or BLOCKED are residuals, not failures of the goal.
Terminal states: PASS / FAIL-named / BLOCKED-named / PENDING-HUMAN.

## Process

- Commit early, push always (`git push -u origin goal-batch/attention-rule-examples-tuning`).
- Never merge to main — the orchestrator merges.
- Add `DONE.json` to `.gitignore` if not already present, then write
  `DONE.json` in the worktree root as your FINAL act:
  `{lane, session_id, verdict(COMPLETE|BLOCKED|PARTIAL), branch, head, pushed,
  items[], residuals[], pending_human[], suite}`.
- Time bound: 3 hours wall-clock. Exceeding it is a terminal BUDGET state.
