"""SSE subscriber that relays ledger ``job.decided`` events to the owning
worker session (design docs: ledger doc 8.2-8.3, fleet plane doc 5.4).

This closes the gap issue #32 names: posting a decision to
``POST /ledger/jobs/{id}/decision`` durably records it and fans it out over
the ledger's ``/ledger/events`` SSE stream, but nothing previously consumed
that stream on the broker side. A caller had to separately know to also call
the broker's ``POST /fleet/jobs/{id}/decision`` relay endpoint, and the two
would silently drift apart -- a decision could be recorded in the ledger
while the running fleet job never heard about it and stayed blocked forever.

This module is the missing subscriber: it holds open ``GET /ledger/events``,
and for every ``job.decided`` event checks whether this broker owns that job
(via the job-id -> machine-id binding in ``SessionTable``). If it does, it
relays the decision down the worker's live session exactly the way the
existing ``POST /fleet/jobs/{id}/decision`` endpoint does -- reusing
``SessionTable.relay_decision`` so there is only one relay code path.
"""

from __future__ import annotations

import asyncio
import json
import logging
from typing import TYPE_CHECKING, Any

from fleetd_broker.sessions import WorkerUnavailableError

if TYPE_CHECKING:
    from fleetd_broker.ledger_client import LedgerClient
    from fleetd_broker.sessions import SessionTable

logger = logging.getLogger("fleetd_broker.ledger_events")


class LedgerEventSubscriber:
    """Consumes ``GET /ledger/events`` and relays ``job.decided`` events for
    jobs this broker owns to the bound worker session.

    Takes the ``LedgerClient`` itself (rather than a bare ``httpx.AsyncClient``
    snapshot) and resolves ``events_client()`` lazily at ``start()`` time --
    tests that swap in an in-process ``ASGITransport``-backed client onto the
    ``LedgerClient`` *after* ``create_app()`` returns (as ``test_app.py``'s
    ``clients`` fixture does) still get relayed through the replaced client,
    exactly as every other ledger call already does.
    """

    def __init__(self, *, ledger: LedgerClient, sessions: SessionTable) -> None:
        self._ledger = ledger
        self._sessions = sessions
        self._task: asyncio.Task[None] | None = None

    def start(self) -> None:
        """Start the background SSE consumption loop, if not already running."""
        if self._task is None:
            self._task = asyncio.ensure_future(self._run())

    async def stop(self) -> None:
        """Cancel the background loop and wait for it to unwind."""
        task = self._task
        self._task = None
        if task is not None:
            task.cancel()
            try:
                await task
            except asyncio.CancelledError:
                pass

    async def _run(self) -> None:
        client = self._ledger.events_client()
        try:
            async with client.stream("GET", "/ledger/events") as resp:
                event_type: str | None = None
                async for line in resp.aiter_lines():
                    if line == "":
                        # blank line terminates one SSE record
                        event_type = None
                        continue
                    if line.startswith("event:"):
                        event_type = line[len("event:") :].strip()
                    elif line.startswith("data:"):
                        data = line[len("data:") :].strip()
                        await self._handle_raw(event_type, data)
        except asyncio.CancelledError:
            raise
        except Exception:  # pragma: no cover - defensive: keep broker alive
            logger.exception("ledger event subscriber loop crashed")

    async def _handle_raw(self, event_type: str | None, data: str) -> None:
        try:
            payload = json.loads(data)
        except json.JSONDecodeError:
            logger.warning("ledger event subscriber: malformed SSE payload")
            return
        event = payload.get("event", event_type)
        job = payload.get("job")
        if job is None:
            return
        await self.handle_event(event, job)

    async def handle_event(self, event: str | None, job: dict[str, Any]) -> None:
        """Handle a single decoded ledger event.

        Exposed directly (not just via the SSE loop) so tests -- and any
        future alternate transport -- can drive the relay deterministically
        without depending on SSE stream timing.
        """
        if event != "job.decided":
            return
        job_id = job.get("job_id")
        if not job_id:
            return
        machine_id = self._sessions.machine_for_job(job_id)
        if machine_id is None:
            # Not a fleet job this broker owns (or no worker ever bound to
            # it) -- nothing to relay.
            return
        text = job.get("status", "")
        try:
            await self._sessions.relay_decision(job_id, text)
        except WorkerUnavailableError:
            logger.warning(
                "job %s decided but worker %s is not connected to relay to",
                job_id,
                machine_id,
            )
