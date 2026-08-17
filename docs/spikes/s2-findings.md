# Spike S-2: Host-Declared, Client-Executed Tool Roundtrip — Findings

**Verdict: CONCLUSIVE PASS.** Assumption A4 (a host-declared, client-executed tool
transfers cleanly to an Android client over the stock chat-completions wire) holds
against lane 1.4's stock `amplifier-agent serve chat-completions` deployment —
**provided the client uses `stream: true`**. A real bug was found in
**non-streaming mode**, documented below as a residual for lane 1.3/1.4.

## Harness substitution

Per the goal's host-capability-limits note (headless Linux, no emulator/KVM), this
spike used a plain Python 3 stdlib HTTP client (`spikes/s2-host-tool/client_harness.py`)
instead of a full Android app. This is a substitution of transport client only — the
harness speaks the exact same wire protocol (OpenAI-style `chat.completions` with a
`tools:` array, SSE streaming) that any Android HTTP client (e.g. OkHttp, Retrofit, or
lane 1.3's own client) would speak. No Android-specific behavior was exercised or is
claimed to be exercised by this spike; it is a wire-protocol proof only.

Target server: the already-running `vela-agent-serve.service` (lane 1.4), reachable at
`http://127.0.0.1:9099` with the shared-secret bearer token from
`~/.amplifier/vela-agent-serve/env`. Model used: `claude-haiku-4-5-20251001` (Anthropic,
`tools` capability confirmed via `GET /v1/models`).

## Items

| # | Item | Result |
|---|------|--------|
| 1 | Client declares exactly one tool in `tools:` field | **PASS** |
| 2 | Model selects tool; client receives `tool_calls` delta(s), stream ends `finish_reason: "tool_calls"` | **PASS** (streaming only — see bug below) |
| 3 | Client executes tool locally (stub) and re-POSTs `{role: "tool", content: ...}` | **PASS** |
| 4 | Turn completes correctly after re-POST (final assistant message returned) | **PASS** |
| 5 | Findings written up with wire captures | **PASS** (this document + `spikes/s2-host-tool/wire_capture.json`) |

## Wire capture — Step 1 (initial request, streaming)

Request (`POST /v1/chat/completions`, abbreviated):

```json
{
  "model": "claude-haiku-4-5-20251001",
  "stream": true,
  "messages": [
    {"role": "system", "content": "You are a test harness. When asked for the time, you MUST call the get_current_time tool. Do not answer without calling it."},
    {"role": "user", "content": "What time is it in UTC? Use the tool to find out."}
  ],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_current_time",
        "description": "Get the current time in a given timezone.",
        "parameters": {
          "type": "object",
          "properties": {"timezone": {"type": "string", "description": "IANA timezone name, e.g. 'UTC'"}},
          "required": ["timezone"]
        }
      }
    }
  ]
}
```

Reassembled SSE response (see `wire_capture.json` for every raw chunk):

- `delta: {"role": "assistant"}` (finish_reason: null)
- `delta: {"reasoning_content": "The user is"}` ... (reasoning stream chunks, finish_reason: null)
- `delta: {"tool_calls": [{"index": 0, "id": "toolu_01BkY2V9JyDutWcecLFrXExe", "type": "function", "function": {"name": "get_current_time", "arguments": "{\"timezone\":\"UTC\"}"}}]}` (finish_reason: null)
- `delta: {}` **`finish_reason: "tool_calls"`** — terminal chunk of this turn, includes `usage`
- `data: [DONE]`

This exactly matches the goal's expected shape: "the client receives a `delta.tool_calls`
chunk and the stream ends with `finish_reason: "tool_calls"`."

## Wire capture — Step 2 (re-POST with tool result, streaming)

Request appends the assistant's `tool_calls` message and a `{role: "tool", tool_call_id:
..., content: ...}` message (stub tool result: `{"timezone": "UTC", "time": "12:00:00
(stub)"}`), then re-POSTs with `stream: true` and the same `tools:` array.

Reassembled response:

- Streamed `delta.content` chunks reassembling to:
  `"The current time in UTC is **12:00:00** (according to the tool)."`
- Terminal chunk: `finish_reason: "stop"`

The turn completes correctly — the model incorporates the stub tool's result into its
final natural-language answer, proving the full roundtrip (declare → select → execute →
report → complete) works end-to-end over the stock wire.

## Bug found: non-streaming (`stream: false`) mode silently drops tool_calls

Before settling on the streaming harness, item 2 was first attempted with `stream: false`
(the simpler request shape). That request returned HTTP 200 with:

```json
{
  "choices": [
    {
      "index": 0,
      "message": {"role": "assistant", "content": ""},
      "finish_reason": "tool_calls"
    }
  ]
}
```

**The top-level `finish_reason` correctly reports `"tool_calls"`, but the `message`
object carries no `tool_calls` array at all, and `content` is empty.** A client relying
on non-streaming mode would see `finish_reason: "tool_calls"` with no way to discover
which tool was called or with what arguments — effectively a stuck turn.

Switching only `stream: false` → `stream: true` (identical request otherwise) fixed
this immediately; the same underlying model turn reassembles correctly in streaming mode.

**Recommendation for lane 1.3 (Android host-tools client) and lane 1.4 (agent-serve
ops):** the Android client MUST use `stream: true` for any request that declares tools.
This should be called out explicitly in lane 1.3's client implementation notes and/or
filed as an upstream bug against `amplifier-agent serve`'s non-streaming
chat-completions translation layer (the `tool_calls` reassembly path is either skipped
or broken for `stream: false`). This spike did not investigate the server's internals
to find the root cause — only confirmed the symptom and the streaming workaround,
which is sufficient to unblock Stage 2/3 client work.

## Conclusion

Assumption A4 holds: the OpenCode-proven client-declared-tool pattern (as used by
`amplifier-app-opencode`) transfers cleanly to a plain HTTP client acting as a stand-in
for an Android client, against the stock, unmodified `amplifier-agent serve
chat-completions` wire — **as long as the client uses streaming requests**. This is a
one-line client-side requirement, not a blocker: lane 1.3's Android implementation
should default to `stream: true` for all tool-enabled requests.

No further investigation needed to unblock downstream lanes; the non-streaming bug is
recorded as a residual, not a blocker for this spike's outcome.
