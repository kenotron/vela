"""HTTP face defaults -- asyncio-queue display, auto-approval.

Per design §6, protocol-point defaults are concrete implementations of the
abstract Protocols in ``base.py``. ``defaults_cli`` provides the Mode A CLI
implementations; this module provides the HTTP-face implementations.

The HTTP face is fundamentally async and multi-event: a single turn produces
many display events that must reach an SSE response stream. The CLI flushes
to stderr synchronously; the HTTP face pushes to an ``asyncio.Queue`` that the
response generator drains concurrently with the running turn task.

Approval over HTTP is intentionally simplified for the POC -- auto-accept and
log. Mid-turn approval round-trips over SSE are in the v2 backlog.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from vela_agentd_lib.protocol_points.base import ApprovalRequest, ApprovalResponse, DisplayEvent

logger = logging.getLogger(__name__)


class HttpQueueDisplaySystem:
    """A DisplaySystem that pushes events into an asyncio.Queue.

    The HTTP response generator drains the queue and translates each event
    into an SSE chunk. A sentinel value (``None``) signals end-of-stream so
    the drain loop knows to terminate.

    This system is per-request: each chat completion gets its own queue and
    its own instance. After the turn completes (or is cancelled), call
    ``close()`` to put the sentinel.

    Robustness rules
    ----------------
    - ``emit()`` swallows exceptions silently. The kernel's hook chain holds
      the event-emission contract; raising from inside ``display.emit`` would
      crash the agent loop. We treat the SSE side as best-effort.
    - After ``close()``, further ``emit()`` calls are no-ops (no exception,
      no queue churn).

    Spike S-1 tee
    -------------
    An optional ``tee_queue`` fans out a *copy* of every event (pre-filter,
    pre-translation -- the raw kernel display event) to a second consumer
    alongside the primary (C1, wire-filtering) consumer. This directly
    answers the spike's central question: can a second consumer read the
    internal event stream without perturbing C1?

    Design choices that make the tee provably non-perturbing to C1:
    - The tee write happens in ``emit()`` AFTER the primary ``queue.put``
      succeeds, so a slow or full tee queue cannot delay or block the
      primary queue.put.
    - ``tee_queue.put_nowait()`` is used (never ``await put``): if the tee
      consumer falls behind and its bounded queue is full, the event is
      DROPPED for the tee side only. This is the "bounded buffer that drops
      tee'd events under pressure rather than blocking the primary consumer"
      requirement from the spike brief.
    - Any exception from the tee side (queue misconfigured, consumer closed)
      is swallowed exactly like the primary queue's own defensive swallow --
      it can never propagate into the kernel's hook chain.
    """

    def __init__(
        self,
        queue: asyncio.Queue[DisplayEvent | None],
        *,
        tee_queue: asyncio.Queue[DisplayEvent | None] | None = None,
    ) -> None:
        self._queue = queue
        self._closed = False
        self._tee_queue = tee_queue
        self._tee_dropped = 0

    async def emit(self, event: DisplayEvent) -> None:
        """Push event to the queue, ignoring any failure.

        DisplaySystem Protocol requires this method. The HTTP face never wants
        a queue-side error to break the kernel's hook chain.
        """
        if self._closed:
            return
        try:
            await self._queue.put(event)
        except Exception:
            # asyncio queues don't typically raise on put unless cancelled or
            # the consumer side is misconfigured; swallow defensively so the
            # agent loop continues.
            logger.debug("HttpQueueDisplaySystem.emit: queue.put failed; dropping event")

        # Tee: best-effort, non-blocking fan-out of the SAME event object to
        # a second consumer. Deliberately placed AFTER the primary put and
        # using put_nowait so it can never add latency or backpressure to
        # the C1 path above. A full tee queue drops the event silently.
        if self._tee_queue is not None:
            try:
                self._tee_queue.put_nowait(event)
            except asyncio.QueueFull:
                self._tee_dropped += 1
                logger.debug(
                    "HttpQueueDisplaySystem tee: queue full, dropping event (total dropped=%d)",
                    self._tee_dropped,
                )
            except Exception:
                logger.debug("HttpQueueDisplaySystem tee: put_nowait failed; dropping event")

    def close(self) -> None:
        """Signal end-of-stream to the drain loop via the sentinel.

        Idempotent. After close, ``emit`` is a no-op.
        """
        if self._closed:
            return
        self._closed = True
        try:
            self._queue.put_nowait(None)
        except asyncio.QueueFull:
            # The drain loop is far behind; in that case end-of-stream is
            # implicit when the turn task completes. Not fatal.
            logger.debug("HttpQueueDisplaySystem.close: queue full, sentinel not posted")
        if self._tee_queue is not None:
            try:
                self._tee_queue.put_nowait(None)
            except asyncio.QueueFull:
                logger.debug("HttpQueueDisplaySystem.close: tee queue full, sentinel not posted")


class HttpAutoApprovalSystem:
    """Auto-approve all approval requests.

    POC scope. Mid-turn approval round-trips over SSE are deferred to v2 so we
    can ship a working integration quickly. Document the security implication
    in any user-facing docs: any bundle tool that asks for approval will be
    auto-approved. This is equivalent to the CLI's ``-y`` / ``--yes`` flag.

    Note that ``ApprovalSystem.request`` is async even though this impl returns
    immediately. The Protocol requires it.
    """

    def __init__(self, *, log_requests: bool = True) -> None:
        self._log_requests = log_requests

    async def request(self, req: ApprovalRequest) -> ApprovalResponse:
        """Always return accept.

        We log every request so the operator can see what would have been
        prompted -- useful for evaluating whether mid-turn approval is needed.
        """
        if self._log_requests:
            payload: dict[str, Any] = req.get("payload") or {}
            tool_name = payload.get("toolName", payload.get("kind", "(unspecified)"))
            logger.info(
                "auto-approving request session=%s turn=%s kind=%s tool=%s",
                req.get("sessionId", "?"),
                req.get("turnId", "?"),
                req.get("kind", "?"),
                tool_name,
            )
        return {"action": "accept"}
