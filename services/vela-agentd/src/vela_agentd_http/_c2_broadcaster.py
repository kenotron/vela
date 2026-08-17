"""C2 event broadcaster -- fan-out of tee'd kernel display events to SSE clients.

Fork point (F3): mirrors the ``EventBroadcaster`` pattern proven in
``services/ledger/ledger_service/events.py`` (lane 2.1, read-only reference).
Multiple ``GET /v1/events`` (or ``/v2/events``) SSE clients can each subscribe
via their own bounded ``asyncio.Queue``; publishing is non-blocking and drops
for a slow subscriber rather than blocking the publisher (same
drop-under-pressure posture as the F1 tee itself).

This module is intentionally decoupled from ``HttpQueueDisplaySystem``'s own
tee queue: ``chat_completions.py`` drains the raw tee queue and calls
``broadcaster.publish(...)`` with a payload already shaped per design doc
§4.2 (see ``_c2_shapes.py``). The broadcaster itself carries no knowledge of
the wire-shape translation -- single responsibility, same as the ledger's.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

logger = logging.getLogger(__name__)

_SUBSCRIBER_QUEUE_MAXSIZE = 256


class EventBroadcaster:
    """Fan-out publisher for C2 events to N connected SSE subscribers.

    Each subscriber gets its own bounded queue. ``publish`` is synchronous
    and non-blocking (``put_nowait``): a slow/disconnected subscriber's full
    queue causes the event to be dropped for that subscriber only -- it can
    never block or delay delivery to other subscribers or back-pressure the
    publisher (the chat-completions turn loop).
    """

    def __init__(self) -> None:
        self._subscribers: set[asyncio.Queue[dict[str, Any] | None]] = set()
        self._dropped = 0

    def subscribe(self) -> asyncio.Queue[dict[str, Any] | None]:
        """Register a new subscriber queue. Caller must call ``unsubscribe``."""
        q: asyncio.Queue[dict[str, Any] | None] = asyncio.Queue(maxsize=_SUBSCRIBER_QUEUE_MAXSIZE)
        self._subscribers.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue[dict[str, Any] | None]) -> None:
        self._subscribers.discard(q)

    def publish(self, event: dict[str, Any]) -> None:
        """Fan out ``event`` to all current subscribers, best-effort."""
        for q in list(self._subscribers):
            try:
                q.put_nowait(event)
            except asyncio.QueueFull:
                self._dropped += 1
                logger.debug("EventBroadcaster: subscriber queue full, dropping event")
            except Exception:
                logger.debug("EventBroadcaster: publish failed for a subscriber; dropping")

    @property
    def subscriber_count(self) -> int:
        return len(self._subscribers)

    @property
    def dropped_count(self) -> int:
        return self._dropped
