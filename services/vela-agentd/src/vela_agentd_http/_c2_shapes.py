"""Translate raw kernel DisplayEvents into C2 wire-shape payloads.

Per design doc §4.2, C2 exposes: tool/started, tool/completed, progress,
thinking/delta, thinking/final, usage, error -- each carrying sessionId,
turnId, and (for delegated sub-agents) agentName.

The raw kernel event (as tee'd by ``HttpQueueDisplaySystem``, pre-filter,
pre C1-translation) already carries these fields via
``vela_agentd_lib.bundle.hook_streaming``'s ``agentName`` seeding. This
module is a thin, best-effort mapper -- unknown event types are passed
through unchanged so no event is silently lost on the C2 side; only the
known types are reshaped to the exact §4.2 keys.
"""

from __future__ import annotations

from typing import Any

# Event kinds forwarded to C2 verbatim-ish, matching design doc §4.2.
_C2_EVENT_TYPES = frozenset(
    {
        "tool/started",
        "tool/completed",
        "progress",
        "thinking/delta",
        "thinking/final",
        "usage",
        "error",
    }
)


def to_c2_payload(event: dict[str, Any]) -> dict[str, Any] | None:
    """Map a raw kernel DisplayEvent to a §4.2 C2 payload, or None to skip.

    Returns None for event kinds not part of the C2 contract (e.g. internal
    kinds that only C1's translator cares about) so the SSE stream stays a
    clean, documented surface rather than a raw firehose.
    """
    kind = event.get("kind") or event.get("type")
    if kind not in _C2_EVENT_TYPES:
        return None

    payload: dict[str, Any] = {
        "type": kind,
        "sessionId": event.get("sessionId"),
        "turnId": event.get("turnId"),
    }
    agent_name = event.get("agentName")
    if agent_name:
        payload["agentName"] = agent_name

    _raw_payload = event.get("payload")
    data: dict[str, Any] = _raw_payload if isinstance(_raw_payload, dict) else {}

    if kind == "tool/started":
        payload["toolCallId"] = data.get("toolCallId") or event.get("toolCallId")
        payload["name"] = data.get("name") or data.get("toolName") or event.get("name")
        payload["args"] = data.get("args") or data.get("arguments") or {}
    elif kind == "tool/completed":
        payload["toolCallId"] = data.get("toolCallId") or event.get("toolCallId")
        payload["name"] = data.get("name") or data.get("toolName") or event.get("name")
        payload["result"] = data.get("result")
        payload["durationMs"] = data.get("durationMs")
    elif kind == "progress":
        payload["message"] = data.get("message") or event.get("message")
        if "percent" in data:
            payload["percent"] = data.get("percent")
    elif kind in ("thinking/delta", "thinking/final"):
        payload["text"] = data.get("text") or event.get("text") or ""
    elif kind == "usage":
        payload["inputTokens"] = data.get("inputTokens")
        payload["outputTokens"] = data.get("outputTokens")
        if "cost" in data:
            payload["cost"] = data.get("cost")
        if "model" in data:
            payload["model"] = data.get("model")
        if "provider" in data:
            payload["provider"] = data.get("provider")
    elif kind == "error":
        payload["code"] = data.get("code") or event.get("code")
        payload["message"] = data.get("message") or event.get("message")
        payload["recoverable"] = bool(data.get("recoverable", False))

    return payload
