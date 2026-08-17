"""In-process pub/sub broadcaster driving the /ledger/events SSE stream.

Not itself the durability mechanism (the SQLite store in db.py is) — this is
purely the notification fan-out for live subscribers. A client that connects
after an event fires will simply not see that specific event over SSE, but the
underlying job state is never lost (it's durably persisted and readable via
GET /ledger/jobs/{id} regardless of SSE delivery).
"""

from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass, field
from typing import Any


@dataclass
class EventBroadcaster:
    _subscribers: set[asyncio.Queue] = field(default_factory=set)
    _lock: asyncio.Lock = field(default_factory=asyncio.Lock)

    async def subscribe(self) -> asyncio.Queue:
        q: asyncio.Queue = asyncio.Queue()
        async with self._lock:
            self._subscribers.add(q)
        return q

    async def unsubscribe(self, q: asyncio.Queue) -> None:
        async with self._lock:
            self._subscribers.discard(q)

    async def publish(self, event_type: str, job: dict[str, Any]) -> None:
        payload = json.dumps({"event": event_type, "job": _strip_internal(job)})
        async with self._lock:
            subs = list(self._subscribers)
        for q in subs:
            await q.put({"event": event_type, "data": payload})


def _strip_internal(job: dict[str, Any]) -> dict[str, Any]:
    return {k: v for k, v in job.items() if not k.startswith("_")}
