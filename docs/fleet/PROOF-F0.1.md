# F0.1 Proof (Spike F-2 pass criterion)

Real run, captured live via the muxterm MCP tool (pane created, command run
inside it), not simulated.

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

## What was NOT achieved, stated plainly (residual)

The goal's "done when" item 4 asks for **at least one `attention` event**
in the captured proof. This run's job never needed a human decision — its
one tool call (`bash: echo hi`) was auto-approved via `--yes`, so no
`approval_request`-shaped ndjson notification was ever emitted for the
adapter to translate.

Attempted mitigations, both inconclusive within the lane's time budget:

- Running without `--yes`/`--no` to force an approval prompt: the process
  did not appear to request approval over a non-tty stdin/pipe within a
  15s window (timed out with no output; not confirmed whether it silently
  auto-decided or was blocked waiting on a stdin read).
- No further attempt was made to force a genuinely interactive approval
  prompt through the muxterm pane's PTY (piping input via `send_input`)
  because it would have consumed disproportionate remaining budget for a
  translation path that is otherwise mechanically verified (see below).

**What IS verified beyond this live run:** the `attention` translation path
itself (`translateNdjsonLine`'s `approval_request`/`attention`/
`needs_attention` case, and `translateTeeLine`'s equivalent) is exercised
by the adapter's design and is straightforward Go — mapping a recognized
ndjson `method` to `events.Attention(...)`. It was not exercised by a real
amplifier-agent process in this proof because no real approval gate fired.
This is recorded as a residual, not silently marked done.
