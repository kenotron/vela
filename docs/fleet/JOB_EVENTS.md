# Fleet Job Events (JSONL protocol)

This document is the wire schema for the JSONL event stream written by
`velafleet-run` (`fleet/run/`), per
`docs/designs/2026-08-24-vela-fleet-execution-plane.md` §4.3 and §4.4. It is
consumed by:

- Lane F0.2 — the `velafleet-worker` tailer (worker tails the file and
  forwards to the broker).
- Lane F1.1/F1.2 — the broker's coalescing/attention-relay logic.

## Where the file lives

By convention (and `velafleet-run`'s default):

```
~/.vela/jobs/<job_id>/events.jsonl
```

`velafleet-run --events-dir <dir>` overrides the directory for testing or
non-default layouts; the file within it is always named `events.jsonl`.

## Invariants

- **One JSON object per line.** Every write is a single `json.Encoder.Encode`
  call followed by an `fsync`, so a reader tailing the file never observes a
  torn/partial line, and a crash of the shim mid-job leaves a valid prefix.
- **Append-only, across process restarts.** The file is opened
  `O_CREATE|O_APPEND`; if `velafleet-run` (or its parent) restarts, resuming
  from the file's current length picks up exactly where the last consumer
  left off (design doc §4.2 point 7 / §4.3 "survives worker restart").
- **Job state is derived from this file, never from the terminal.** The job's
  real stdio is inherited straight through to the hosting pane so a human can
  attach and watch/interact normally (design doc FB4). Nothing here scrapes
  that stream for state.
- **`attention` events are never coalesced or dropped.** A consumer (worker,
  broker) MUST forward every `attention` event immediately. `progress` events
  may be coalesced/rate-limited by a downstream consumer; `attention` may
  not (design doc §5.2).

## Event shape

```jsonc
{
  "ts": 1756000012,        // unix seconds, required
  "kind": "progress",      // required, one of the kinds below
  "job_id": "job-123",     // present on every event this shim writes
  // ...kind-specific fields below
}
```

### `started`

Emitted exactly once, immediately after the job's process is spawned.

| Field     | Type   | Meaning                                  |
|-----------|--------|-------------------------------------------|
| `runtime` | string | adapter/runtime name (e.g. `shell`, `amplifier-agent`) |
| `pid`     | int    | the spawned process's PID                |

```json
{"ts":1756000000,"kind":"started","job_id":"job-123","runtime":"amplifier-agent","pid":41233}
```

### `progress`

Emitted zero or more times while the job runs. Downstream consumers MAY
coalesce these (design doc §5.2).

| Field     | Type   | Meaning                                   |
|-----------|--------|--------------------------------------------|
| `message` | string | human-readable progress note                |
| `percent` | int    | 0-100 estimate, best-effort, may be absent semantically (0 if unknown) |

```json
{"ts":1756000012,"kind":"progress","job_id":"job-123","message":"cloned repo, running tests","percent":20}
```

### `attention`

Emitted when the job is blocked on a human decision. MUST be delivered
immediately downstream, never coalesced (design doc §5.2).

| Field     | Type     | Meaning                              |
|-----------|----------|----------------------------------------|
| `reason`  | string   | what decision is needed                |
| `options` | []string | optional, enumerated choices if known  |

```json
{"ts":1756000180,"kind":"attention","job_id":"job-123","reason":"Two migration strategies are viable; which?","options":["in-place","blue-green"]}
```

### `cost`

Emitted zero or more times to report incremental spend, if the runtime
reports it. Not currently enforced against `limits.usd` by this shim — that
is the broker's concern (design doc §4.1, §8.7).

| Field    | Type    | Meaning         |
|----------|---------|-----------------|
| `usd`    | float64 | cumulative cost |
| `tokens` | int     | cumulative tokens |

```json
{"ts":1756000420,"kind":"cost","job_id":"job-123","usd":0.41,"tokens":88300}
```

### `finished`

Emitted exactly once, when the job's process exits 0.

| Field       | Type              | Meaning                          |
|-------------|-------------------|-----------------------------------|
| `exit_code` | int               | always `0` for this kind          |
| `result`    | object (optional) | free-form structured result, if the runtime produced one |

```json
{"ts":1756000900,"kind":"finished","job_id":"job-123","exit_code":0,"result":{"pr_url":"https://…"}}
```

### `failed`

Emitted exactly once, when the job's process exits non-zero, or when the
shim itself could not launch it.

| Field       | Type   | Meaning                            |
|-------------|--------|--------------------------------------|
| `exit_code` | int    | the process's exit code, or `-1`/`2` for shim-level failures |
| `error`     | string | human-readable error detail, if any  |

```json
{"ts":1756000900,"kind":"failed","job_id":"job-123","exit_code":1,"error":"exit status 1"}
```

Exactly one of `finished` or `failed` is emitted per job, always last.

## Degraded mode (FA5)

Not every runtime can emit `progress`/`attention`/`cost` — that requires
per-runtime integration (design doc FA5). A runtime with no adapter-specific
integration still gets a fully valid event stream; it is simply narrower:

```
started -> finished    (or -> failed)
```

This is honest degradation, not a missing feature: the `shell` adapter in
this shim never fabricates a `progress` or `attention` event it can't
actually observe. The `amplifier-agent` adapter attempts to tail
`amplifier-agent`'s own tee'd event stream (`AMPLIFIER_AGENT_EVENT_TEE_PATH`,
verified in `spikes/s1-event-tee/`) for the richer stream; if that file never
appears (e.g. an invocation mode that doesn't support the tee hook), it
degrades to the same `started`/`finished` pair with no error.

## Runtimes / adapters implemented in this lane (F0.1)

| Runtime name     | Behavior                                                             |
|------------------|-----------------------------------------------------------------------|
| `shell`          | Runs an arbitrary command; `started`/`finished`/`failed` only.       |
| `amplifier-agent`| Runs `amplifier-agent`; tails its tee'd event stream for `progress`/`attention`/`cost` when available, else degrades per above. |

Later lanes may add `claude`, `opencode`, etc. — new runtimes are added to
`fleet/run/internal/adapter`'s registry; the JSONL shape above does not
change per-runtime beyond which of the optional kinds actually appear.
