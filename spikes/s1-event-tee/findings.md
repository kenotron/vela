# Spike S-1 Findings: Event Queue Tee

**Verdict: PASS** — a second consumer CAN read `amplifier-agent`'s internal
event stream without perturbing the C1 (chat-completions) path.

## 1. Fork point (exact location)

Repo: `amplifier-agent` (path on this machine:
`~/workspace/claude-code/amplifier-agent`), throwaway branch
`spike/s1-event-tee-throwaway` off `main` @ `6da7617d65e3bd2c4438d6d21b5b51c02afac314`.

The internal event queue is **not** a single named queue object that gets
discarded wholesale — it's a filtering *consumer* that decides, per event
type, whether to translate an event onto the OpenAI SSE wire or drop it.
The discarding happens in two cooperating places:

1. **`src/amplifier_agent_lib/protocol_points/defaults_http.py`**,
   class `HttpQueueDisplaySystem` (the kernel-facing `DisplaySystem`
   implementation). `emit()` pushes every kernel display event into a
   per-request `asyncio.Queue` (`event_queue`). This queue itself drops
   nothing — it is the single, unfiltered, internal event stream we
   needed to fork from.

2. **`src/amplifier_agent_http/routes/chat_completions.py`**, function
   `_stream_chat_completion` (drain loop, originally lines 341–376,
   now ~389–424 after the tee edit). This loop reads `event_queue` and
   calls `amplifier_agent_http._event_translator.translate_event()` for
   each event. That is where discarding actually happens:

   `src/amplifier_agent_http/_event_translator.py`, lines 39–55 —
   the `_DROPPED_EVENT_TYPES` frozenset (`tool/started`, `tool/completed`,
   `result/final`, `thinking/final`, `progress`, `usage`) and the
   catch-all "unknown event type -> drop" fallback at the bottom of
   `translate_event()`.

   The module docstring at the top of `_event_translator.py` (lines 1–22)
   states the design rule explicitly and is the source of the KNOWN
   clue's "internal activity stays internal" line — it appears verbatim
   as `POC rule -- "internal stays internal"` (line 7) with the specific
   type-by-type rationale ("`tool/started` and `tool/completed` are
   DROPPED -- opencode sees no internal tool activity ... matches Ollama
   / llama.cpp pattern").

   `_stream_chat_completion` itself echoes the rule at line 357 (pre-edit):
   `# Capture usage events for the final chunk; they don't produce their
   own SSE chunks per the "internal stays internal" rule.`

**Conclusion on the fork point:** the queue that is safe to tee is
`event_queue` (fed by `HttpQueueDisplaySystem.emit()`) — it is the
unfiltered pre-translation stream. Forking downstream of `translate_event()`
would be useless (everything interesting is already gone by then).

## 2. Tee mechanism implemented

Modified `HttpQueueDisplaySystem` to accept an optional second queue,
`tee_queue`, and fan out a copy of every event to it inside `emit()`,
**after** the primary `await self._queue.put(event)` succeeds, using
`tee_queue.put_nowait(event)` (never `await put`):

```python
async def emit(self, event: DisplayEvent) -> None:
    if self._closed:
        return
    try:
        await self._queue.put(event)          # primary (C1) path, unchanged
    except Exception:
        logger.debug(...)

    if self._tee_queue is not None:            # tee (second consumer)
        try:
            self._tee_queue.put_nowait(event)  # non-blocking, bounded
        except asyncio.QueueFull:
            self._tee_dropped += 1             # drop under pressure
            logger.debug(...)
        except Exception:
            logger.debug(...)
```

Properties that make this provably non-perturbing to C1:

- The tee write happens strictly *after* the primary queue.put, so it
  cannot delay or reorder anything on the C1 path.
- `put_nowait` on a bounded queue (`maxsize=256` in the wiring below)
  means a slow tee consumer can never apply backpressure to the primary
  path — under pressure, tee'd events are silently dropped (counted via
  `self._tee_dropped` for future observability) rather than blocking.
- Any exception on the tee side is swallowed exactly like the existing
  defensive swallow around the primary put — it cannot propagate into
  the kernel's hook chain.
- `close()` also posts the sentinel (`None`) to the tee queue so a tee
  consumer terminates cleanly alongside the primary drain loop.

Wired into `chat_completions.py` (`chat_completions()` handler) behind an
opt-in env var, `AMPLIFIER_AGENT_EVENT_TEE_PATH` — when unset, `tee_queue`
is `None` and behavior is byte-for-byte identical to before this spike
(verified — see §3). When set, a bounded `asyncio.Queue(maxsize=256)` is
created and a background task (`_drain_tee_to_jsonl`) drains it to a
JSONL file, standing in for "a second consumer."

Full diff: `spikes/s1-event-tee/tee-implementation.diff` (207 lines,
touches `defaults_http.py` and `chat_completions.py` only).

## 3. 100-turn comparison result

Built `tests/http/test_s1_event_tee_spike.py` in the throwaway branch —
a FastAPI `TestClient` harness with a fake `run_chat_turn` that emits a
realistic mixed event stream per turn (`thinking/delta`, `tool/started` +
`tool/completed` with `agentName` set — simulating a delegated sub-agent
turn — `result/delta` x2, and a `usage` event). No real LLM provider
credentials were needed or used; this substitution is explicit here per
the host capability limits section of the goal.

Ran 100 consecutive turns with `AMPLIFIER_AGENT_EVENT_TEE_PATH` unset,
then 100 consecutive turns with it set to a temp JSONL path (same process,
same `TestClient`, same fake turn). Compared the two 100-response batches
(normalizing only the per-request-random `id`/`created` fields, which are
expected non-determinism unrelated to the tee).

**Result: byte-identical (after normalization) across all 100 turns in
both conditions.** See `uv run pytest tests/http/test_s1_event_tee_spike.py
-q -s` → `1 passed`.

The full existing HTTP test suite (`tests/http/`, 31 pre-existing tests)
also still passes with the tee code paths present but inactive
(`AMPLIFIER_AGENT_EVENT_TEE_PATH` unset by default) — 32 passed total
including the new spike test.

## 4. Delegated sub-agent event capture (item 4)

The tee'd JSONL file was asserted to contain exactly 100 `tool/started`
and 100 `tool/completed` events (one pair per turn), each with
`"agentName": "foundation:explorer"` populated — confirming the tee
mechanism does carry the sub-agent delegation event shape that is
completely invisible on the C1 wire (those event types are unconditionally
dropped by `_DROPPED_EVENT_TYPES`, verified by asserting `"tool/started"`
and the tool name never appear in the C1 SSE output).

## 5. Central question — answered

**Yes.** A second consumer can read the internal event queue
(`event_queue`, fed by `HttpQueueDisplaySystem.emit()`) without perturbing
the C1 chat-completions path, given:

- The tee attaches at the `emit()` fan-out point (pre-filter), not by
  modifying the existing filtering drain loop.
- The tee uses a bounded, `put_nowait`-only secondary queue that always
  drops under pressure rather than blocking.
- The tee write is ordered strictly after the primary write.

This validates Assumption A3. Stage 3 (the `vela-agentd` fork) can proceed
as a thin fork with a tee, rather than requiring the two-surface design
(candidate B).

## Residual items / not yet done (correctly out of scope here)

- No production rebase-ability review of this diff against upstream
  `amplifier-agent` changes (deferred to lane 3.1 per SCOPE-OUTS).
- No real-provider end-to-end run (explicitly substituted with a fake
  `run_chat_turn` per the host capability limits note — no LLM credentials
  available in this environment). The substitution is documented here.
- The tee currently writes to a JSONL file as its "second consumer" stand-in;
  lane 3.1's real approval-gate/C2/C3 consumers would replace this file
  writer with their own actual consumer logic. The `HttpQueueDisplaySystem`
  tee mechanism itself is consumer-agnostic (any code holding the
  `tee_queue` reference can drain it).
