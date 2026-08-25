"""Maps relayed worker events onto ledger writes (design doc 5.2, D4/D5).

This is the broker-side half of the job wrapper protocol (design doc 4.3):
`velafleet-run` emits JSONL events, a worker tails them and forwards each
line over its session as `{"op": "event", ...}`; this module turns each
event into the correct ledger call, with the one piece of policy the broker
holds -- progress is coalesced, attention is never coalesced (design doc
5.2's "Coalescing progress but never coalescing attention").
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from fleetd_broker.ledger_client import LedgerClient, ProgressCoalescer
from fleetd_broker.registry import WorkerRegistry
from fleetd_broker.sessions import SessionTable

TERMINAL_KINDS = {"finished", "failed"}


class MalformedEventError(Exception):
    pass


@dataclass
class ParsedEvent:
    ts: int
    kind: str
    job_id: str
    fields: dict[str, Any]

    @property
    def is_terminal(self) -> bool:
        return self.kind in TERMINAL_KINDS


def parse_event(msg: dict[str, Any]) -> ParsedEvent:
    if "kind" not in msg:
        raise MalformedEventError("event missing 'kind'")
    if "job_id" not in msg:
        raise MalformedEventError("event missing 'job_id'")
    fields = {k: v for k, v in msg.items() if k not in ("op", "ts", "kind", "job_id")}
    return ParsedEvent(
        ts=msg.get("ts", 0), kind=msg["kind"], job_id=msg["job_id"], fields=fields
    )


class WorkerEventHandler:
    def __init__(
        self,
        *,
        registry: WorkerRegistry,
        sessions: SessionTable,
        ledger: LedgerClient,
        coalescer: ProgressCoalescer,
    ) -> None:
        self._registry = registry
        self._sessions = sessions
        self._ledger = ledger
        self._coalescer = coalescer

    async def handle(self, msg: dict[str, Any]) -> None:
        event = parse_event(msg)

        if event.kind == "started":
            await self._ledger.patch_job(event.job_id, status="running")
            return

        if event.kind == "progress":
            entry = {
                "ts": event.ts or None,
                "message": event.fields.get("message", ""),
                "percent": event.fields.get("percent"),
                "source": "fleet",
            }
            to_flush = self._coalescer.offer(event.job_id, entry)
            if to_flush is not None:
                await self._ledger.patch_job(
                    event.job_id, status="running", progress_entry=to_flush
                )
            return

        if event.kind == "attention":
            # Never coalesced -- written immediately (design doc 5.2).
            await self._ledger.patch_job(
                event.job_id,
                status="needs_attention",
                attention={
                    "required": True,
                    "reason": event.fields.get("reason"),
                    "options": event.fields.get("options", []),
                },
            )
            return

        if event.kind == "cost":
            await self._ledger.patch_job(
                event.job_id,
                cost={
                    "usd": event.fields.get("usd"),
                    "tokens": event.fields.get("tokens"),
                },
            )
            return

        if event.kind in ("finished", "failed"):
            # Flush any pending coalesced progress so nothing is lost on
            # terminal transition, then write the terminal state.
            pending = self._coalescer.drain(event.job_id)
            if pending is not None:
                await self._ledger.patch_job(
                    event.job_id, status="running", progress_entry=pending
                )

            status = (
                "done"
                if event.kind == "finished" and event.fields.get("exit_code", 0) == 0
                else "failed"
            )
            await self._ledger.patch_job(
                event.job_id,
                status=status,
                result=event.fields.get("result"),
            )

            machine_id = self._sessions.machine_for_job(event.job_id)
            if machine_id is not None:
                self._registry.increment_active_jobs(machine_id, -1)
            self._sessions.unbind_job(event.job_id)
            return

        raise MalformedEventError(f"unknown event kind: {event.kind!r}")
