#!/usr/bin/env python3
"""
Spike S-2: minimal HTTP client harness proving the client-declared-tool
roundtrip against a stock `amplifier-agent serve chat-completions` instance.

This is NOT an Android app. Per the goal's host-capability-limits note, a
pure HTTP client test reaches the same evidence as a full Android app would,
since we're testing the wire protocol (OpenAI-style chat-completions with
`tools:` field over SSE streaming), not the Android UI layer. Any real
Android client (e.g. lane 1.3's OkHttp-based host-tools client) would issue
the same requests and parse the same SSE stream.

NOTE: this harness uses `stream: true`. An earlier non-streaming
(`stream: false`) attempt against this server surfaced a real server-side
bug: the top-level `chat.completion` response reports
`finish_reason: "tool_calls"` but the `message` object carries no
`tool_calls` array and empty `content` — the tool call is silently dropped.
Streaming mode (`stream: true`) correctly emits `delta.tool_calls` chunks
per the goal's own expected wire shape, so this harness uses streaming
throughout. See findings.md for the full non-streaming repro.

Usage:
    AMPLIFIER_AGENT_HTTP_API_KEY=<key> python3 client_harness.py

Writes a full wire capture (every request + reassembled response, including
raw SSE chunks) to wire_capture.json in this directory.
"""

import json
import os
import sys
import urllib.error
import urllib.request

BASE_URL = os.environ.get("AMPLIFIER_AGENT_URL", "http://127.0.0.1:9099")
API_KEY = os.environ.get("AMPLIFIER_AGENT_HTTP_API_KEY")
MODEL = os.environ.get("AMPLIFIER_AGENT_MODEL", "claude-haiku-4-5-20251001")

if not API_KEY:
    print("ERROR: AMPLIFIER_AGENT_HTTP_API_KEY not set", file=sys.stderr)
    sys.exit(2)

WIRE_CAPTURE = []


def post_chat_completions_streaming(messages, tools=None):
    """POST to /v1/chat/completions with stream=true, parse SSE chunks,
    and reassemble a final message + finish_reason. Returns
    (reassembled_message, finish_reason, raw_chunks)."""
    body = {
        "model": MODEL,
        "messages": messages,
        "stream": True,
    }
    if tools:
        body["tools"] = tools

    req = urllib.request.Request(
        f"{BASE_URL}/v1/chat/completions",
        data=json.dumps(body).encode("utf-8"),
        headers={
            "Authorization": f"Bearer {API_KEY}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream",
        },
        method="POST",
    )

    raw_chunks = []
    reassembled_content = ""
    reassembled_tool_calls = {}  # index -> {id, type, function: {name, arguments}}
    finish_reason = None

    capture_entry = {"request": body, "raw_sse_chunks": raw_chunks}

    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            capture_entry["response_status"] = resp.status
            for line in resp:
                line = line.decode("utf-8").rstrip("\n")
                if not line or not line.startswith("data: "):
                    continue
                payload = line[len("data: ") :]
                if payload.strip() == "[DONE]":
                    raw_chunks.append({"event": "DONE"})
                    break
                chunk = json.loads(payload)
                raw_chunks.append(chunk)

                choice = chunk["choices"][0]
                delta = choice.get("delta", {})
                if delta.get("content"):
                    reassembled_content += delta["content"]
                if "tool_calls" in delta:
                    for tc_delta in delta["tool_calls"]:
                        idx = tc_delta["index"]
                        if idx not in reassembled_tool_calls:
                            reassembled_tool_calls[idx] = {
                                "id": tc_delta.get("id"),
                                "type": tc_delta.get("type", "function"),
                                "function": {"name": "", "arguments": ""},
                            }
                        fn_delta = tc_delta.get("function", {})
                        if fn_delta.get("name"):
                            reassembled_tool_calls[idx]["function"]["name"] += fn_delta[
                                "name"
                            ]
                        if fn_delta.get("arguments"):
                            reassembled_tool_calls[idx]["function"]["arguments"] += (
                                fn_delta["arguments"]
                            )
                if choice.get("finish_reason"):
                    finish_reason = choice["finish_reason"]

    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8")
        capture_entry["response_status"] = e.code
        capture_entry["error_body"] = raw
        WIRE_CAPTURE.append(capture_entry)
        raise

    tool_calls_list = [
        reassembled_tool_calls[i] for i in sorted(reassembled_tool_calls)
    ]
    capture_entry["reassembled"] = {
        "content": reassembled_content,
        "tool_calls": tool_calls_list,
        "finish_reason": finish_reason,
    }
    WIRE_CAPTURE.append(capture_entry)

    message = {"role": "assistant", "content": reassembled_content or None}
    if tool_calls_list:
        message["tool_calls"] = tool_calls_list
    return message, finish_reason


# The single stub tool this spike declares — a trivial "get_current_time"
# tool. Logic doesn't matter; we're testing the wire mechanics.
STUB_TOOL = {
    "type": "function",
    "function": {
        "name": "get_current_time",
        "description": "Get the current time in a given timezone.",
        "parameters": {
            "type": "object",
            "properties": {
                "timezone": {
                    "type": "string",
                    "description": "IANA timezone name, e.g. 'UTC'",
                }
            },
            "required": ["timezone"],
        },
    },
}


def run_stub_tool(name, arguments):
    """Trivial local stub execution — this spike tests the wire protocol,
    not tool logic, per the goal's SCOPE-OUTS."""
    if name == "get_current_time":
        args = json.loads(arguments) if isinstance(arguments, str) else arguments
        tz = args.get("timezone", "UTC")
        return json.dumps({"timezone": tz, "time": "12:00:00 (stub)"})
    raise ValueError(f"unknown tool: {name}")


def main():
    results = {"items": {}}

    # --- Item 1+2: client declares one tool; model selects it ---
    messages = [
        {
            "role": "system",
            "content": "You are a test harness. When asked for the time, you MUST call the get_current_time tool. Do not answer without calling it.",
        },
        {
            "role": "user",
            "content": "What time is it in UTC? Use the tool to find out.",
        },
    ]

    print("=== Step 1: declaring tool, sending initial streaming request ===")
    message, finish_reason = post_chat_completions_streaming(
        messages, tools=[STUB_TOOL]
    )
    tool_calls = message.get("tool_calls")
    print(f"finish_reason={finish_reason}")
    print(f"tool_calls={json.dumps(tool_calls, indent=2)}")

    results["items"]["item_1_tool_declared"] = "PASS"

    item2_pass = finish_reason == "tool_calls" and bool(tool_calls)
    results["items"]["item_2_model_selects_tool"] = (
        "PASS"
        if item2_pass
        else f"FAIL - finish_reason={finish_reason}, tool_calls={tool_calls}"
    )

    if not item2_pass:
        print(
            f"\nFAIL at item 2: finish_reason={finish_reason}, tool_calls={tool_calls}"
        )
        write_outputs(results)
        sys.exit(1)

    assert tool_calls is not None
    print(
        f"\n=== Step 2 PASS: model selected tool(s): {[tc['function']['name'] for tc in tool_calls]} ==="
    )

    # --- Item 3: client executes tool locally, re-POSTs result ---
    tool_call = tool_calls[0]
    tool_call_id = tool_call["id"]
    fn_name = tool_call["function"]["name"]
    fn_args = tool_call["function"]["arguments"]

    print(
        f"\n=== Step 3: executing stub tool '{fn_name}' locally with args={fn_args} ==="
    )
    tool_result = run_stub_tool(fn_name, fn_args)
    print(f"stub tool result: {tool_result}")

    followup_messages = messages + [
        {
            "role": "assistant",
            "content": message.get("content"),
            "tool_calls": tool_calls,
        },
        {
            "role": "tool",
            "tool_call_id": tool_call_id,
            "content": tool_result,
        },
    ]

    print("\n=== Step 4: re-POSTing tool result, expecting final assistant message ===")
    final_message, final_finish_reason = post_chat_completions_streaming(
        followup_messages, tools=[STUB_TOOL]
    )
    print(f"finish_reason={final_finish_reason}")
    print(f"final message: {json.dumps(final_message, indent=2)}")

    item4_pass = (
        final_finish_reason in ("stop", None)
        and final_message.get("role") == "assistant"
        and bool(final_message.get("content"))
    )

    results["items"]["item_3_client_executes_and_reposts"] = "PASS"
    results["items"]["item_4_turn_completes"] = (
        "PASS"
        if item4_pass
        else f"FAIL - finish_reason={final_finish_reason}, message={final_message}"
    )

    write_outputs(results)

    if item4_pass:
        print("\n=== ALL STEPS PASSED (streaming mode) ===")
        sys.exit(0)
    else:
        print(f"\nFAIL at item 4: finish_reason={final_finish_reason}")
        sys.exit(1)


def write_outputs(results):
    out_dir = os.path.dirname(os.path.abspath(__file__))
    with open(os.path.join(out_dir, "wire_capture.json"), "w") as f:
        json.dump(WIRE_CAPTURE, f, indent=2)
    with open(os.path.join(out_dir, "results.json"), "w") as f:
        json.dump(results, f, indent=2)
    print(f"\nWire capture written to {out_dir}/wire_capture.json")
    print(f"Results written to {out_dir}/results.json")


if __name__ == "__main__":
    main()
