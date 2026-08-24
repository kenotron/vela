"""JSONL event parsing + ledger-patch mapping.

Wire schema per `docs/fleet/JOB_EVENTS.md` (produced by `velafleet-run`,
lane F0.1). This module does not redefine that protocol -- it parses exactly
what F0.1 writes and maps each event kind onto a ledger PATCH body.

`attention` events must never be dropped or coalesced (JOB_EVENTS.md
"Invariants"); callers of `ledger_patch_for_event` are expected to PATCH
immediately for every event this module returns a patch for.
"""

from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Any


class MalformedEventError(ValueError):
    """Raised when a JSONL line cannot be parsed as a valid job event."""


@dataclass(frozen=True)
class JobEvent:
    """A single parsed line from `events.jsonl`."""

    ts: int
    kind: str
    job_id: str | None
    fields: dict[str, Any]

    @property
    def is_terminal(self) -> bool:
        """True for `finished`/`failed` -- the tailer should stop after these."""
        return self.kind in ("finished", "failed")


def parse_event_line(line: str) -> JobEvent:
    """Parse one JSONL line into a `JobEvent`.

    Raises `MalformedEventError` on invalid JSON or a missing/invalid `kind`.
    Per JOB_EVENTS.md's append-only/fsync invariant, a well-behaved tailer
    should never see a torn line -- but a crash mid-write or a stray blank
    line is handled by raising here so the caller can log-and-skip rather
    than crash the whole tail loop.
    """
    stripped = line.strip()
    if not stripped:
        raise MalformedEventError("empty line")
    try:
        obj = json.loads(stripped)
    except json.JSONDecodeError as exc:
        raise MalformedEventError(f"invalid JSON: {exc}") from exc
    if not isinstance(obj, dict):
        raise MalformedEventError("event is not a JSON object")
    kind = obj.get("kind")
    if not isinstance(kind, str) or not kind:
        raise MalformedEventError("event missing string 'kind'")
    ts = obj.get("ts")
    if not isinstance(ts, int):
        raise MalformedEventError("event missing integer 'ts'")
    job_id = obj.get("job_id")
    fields = {k: v for k, v in obj.items() if k not in ("ts", "kind", "job_id")}
    return JobEvent(ts=ts, kind=kind, job_id=job_id, fields=fields)


def ledger_patch_for_event(event: JobEvent) -> dict[str, Any] | None:
    """Map a parsed `JobEvent` onto a `PATCH /ledger/jobs/{id}` body.

    Returns `None` for event kinds that carry no ledger-relevant state on
    their own (currently none -- every defined kind produces a patch, but the
    signature stays `Optional` so a future/unknown kind can be safely
    ignored rather than sending a garbage patch).
    """
    if event.kind == "started":
        return {
            "status": "running",
            "progress": {
                "message": f"started (runtime={event.fields.get('runtime')}, pid={event.fields.get('pid')})",
            },
        }
    if event.kind == "progress":
        return {
            "status": "running",
            "progress": {
                "message": event.fields.get("message"),
                "percent": event.fields.get("percent"),
            },
        }
    if event.kind == "attention":
        return {
            "status": "needs_attention",
            "attention": {
                "required": True,
                "reason": event.fields.get("reason"),
                "options": event.fields.get("options"),
            },
        }
    if event.kind == "cost":
        return {
            "cost": {
                "usd": event.fields.get("usd"),
                "tokens": event.fields.get("tokens"),
            },
        }
    if event.kind == "finished":
        return {
            "status": "done",
            "result": {
                "exit_code": event.fields.get("exit_code", 0),
                "result": event.fields.get("result"),
            },
        }
    if event.kind == "failed":
        return {
            "status": "failed",
            "result": {
                "exit_code": event.fields.get("exit_code"),
                "error": event.fields.get("error"),
            },
        }
    return None
