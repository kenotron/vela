# F0.1 Proof (Spike F-2 pass criterion)

Real runs, captured live via the muxterm MCP tool (pane created, commands
run inside it against the real installed `amplifier-agent` binary), not
simulated.

## Command

```
/tmp/velafleet-run --job proof-f01b --runtime amplifier-agent -- \
  amplifier-agent run "run the bash command: echo hi, then reply with the word pong" \
  --yes --output json
```

`velafleet-run` injected `--display ndjson` itself (adapter behavior, see
`fleet/run/internal/adapter/amplifier.go`); the operator-supplied argv did
not request it.

## Result

- Exit code: 0
- The pane rendered normal interactive output throughout (banner, ndjson
  notification lines tee'd to real stderr, the final JSON reply) —
  simultaneously with events being written to the sidecar file.
- `~/.vela/jobs/proof-f01b/events.jsonl`: 15 lines — 1 `started`, 13
  `progress` (thinking/result deltas, a real `tool/started`+`tool/completed`
  pair for the `bash` call, `usage`, `result/final`), 1 `finished`.

Full captured file: `docs/fleet/proof-f01b-events.jsonl` (checked in
verbatim, this run).

## `attention`: verified-absent, not merely untried (BLOCKED, named)

The goal's "done when" item 4 also asks for at least one `attention` event.
This was investigated to a conclusive, source-verified root cause rather
than left as "we didn't get one" — three empirical attempts, then a source
read that explains all three:

1. **`--yes` run** (above): no attention event — expected, `-y`
   auto-approves.
2. **`-n` (auto-decline) run**, same `bash: echo hi` prompt: the tool call
   still executed and completed normally (`tool/started`/`tool/completed`
   for `bash` present in the ndjson stream) with **no approval-shaped
   notification at all** — i.e. `-n` didn't even engage an approval path
   for this tool call to decline.
3. **No `-y`/`-n` at all**, run directly inside the muxterm pane (a real
   PTY, not a pipe) with an 8s observation window: the job ran to
   completion on its own, again with no approval-shaped notification and no
   blocking prompt observed on screen.

**Root cause (read from `amplifier-agent` source,
`~/workspace/claude-code/amplifier-agent`, this host):**

- `src/amplifier_agent_lib/protocol_points/defaults_cli.py`,
  `CliApprovalSystem.request()` — the CLI's approval path is a **synchronous
  blocking prompt** (`prompt_fn` / effectively `input()`), gated on
  `is_tty`. It is **not** implemented as a `DisplayEvent`/notification at
  all — even in principle, nothing in Mode A's approval path can appear on
  the ndjson wire (`JsonDisplaySystem`) our adapter tails, because approval
  and display are two disjoint protocol points (`ApprovalSystem` vs.
  `DisplaySystem` in `protocol_points/base.py`).
- More fundamentally: this `bash` tool call **never invokes the approval
  system at all** in this installed engine/config (confirmed by run 2 and
  3 above — no prompt, no notification, no observable pause, regardless of
  `-y`/`-n`/neither). Approval-gating in this codebase (see the recent
  `approval-gate` lane, `#44`/`#45`/`#46`/`#57`) is wired for **Android host
  tools** (privileged device actions), not for `amplifier-agent`'s own
  `bash` tool in CLI Mode A. There is currently no tool call this shim can
  make through `amplifier-agent run` that engages CLI approval at all.

**Conclusion: this is a genuine, named, upstream blocker — not a shim gap.**
Even a differently-written adapter cannot observe an `attention` moment
from `amplifier-agent run` today, because (a) no bundled tool call
currently triggers CLI approval, and (b) if one did, the CLI's approval
path is architecturally a blocking prompt outside the notification stream
this adapter (or any wire-level consumer) can tail. Per design doc FA5,
this is exactly the documented degradation case: this runtime, as
currently wired, gets `started`/`progress`*/`finished` — not `attention` —
until either (a) `amplifier-agent` gains a tool whose CLI path invokes
`CliApprovalSystem.request()`, or (b) that request path itself emits a
wire-visible notification alongside (or instead of) blocking on a raw
terminal prompt. Neither is in this lane's scope (`fleet/run/`,
`docs/fleet/JOB_EVENTS.md` only).

**What IS shipped and verified in this shim regardless:** the `attention`
translation path itself
(`amplifier.go`'s `translateNdjsonLine`/`translateTeeLine`, recognizing
`approval_request`/`attention`/`needs_attention` methods and emitting
`events.Attention(...)`) is implemented, code-reviewed, and ready — it
simply has no live trigger to exercise it against, today, in this engine.
The moment `amplifier-agent` (or a future runtime's adapter) emits one of
those methods on its structured stream, this shim requires no code change
to produce a correct `attention` JSONL event.
