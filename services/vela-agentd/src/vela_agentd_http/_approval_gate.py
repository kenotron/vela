"""F2: a real approval gate replacing unconditional auto-approve.

Fork point rationale: upstream's ``HttpAutoApprovalSystem`` (in
``vela_agentd_lib.protocol_points.defaults_http``, untouched) always returns
``{"action": "accept"}``. This module implements
``vela_agentd_lib.protocol_points.base.ApprovalSystem`` with real
suspend/resolve/timeout semantics:

1. ``request()`` publishes an ``approval/requested`` event on the C2
   broadcaster (so a connected C2 client can see and act on it).
2. It creates an ``asyncio.Future`` keyed by a generated approval id and
   awaits it with a bounded timeout (``VELA_AGENTD_APPROVAL_TIMEOUT_SECONDS``,
   default 30s).
3. A C2 client resolves the pending approval via
   ``POST /v1/approvals/{id}/decision`` (see ``routes/approvals.py``), which
   looks up the future in this gate's registry and sets its result.
4. If no decision arrives before the timeout, the future is cancelled and the
   gate resolves to ``{"action": "deny"}`` -- F-4's adversarial requirement:
   a privileged tool call with NO C2 client attached must deny and return,
   never hang the server.

This gate holds per-request in-memory state only (a dict of pending
futures); it is intentionally simple (no persistence) since approvals are a
single-turn, single-process concern for this fork -- consistent with the
kernel philosophy of keeping policy decisions (accept/deny/timeout default)
at the edge rather than in upstream's kernel/session-runner code.
"""

from __future__ import annotations

import asyncio
import logging
import os
import time
import uuid
from typing import Any

from vela_agentd_lib.protocol_points.base import ApprovalRequest, ApprovalResponse

logger = logging.getLogger(__name__)

_DEFAULT_TIMEOUT_SECONDS = 30.0


def _timeout_seconds() -> float:
    raw = os.environ.get("VELA_AGENTD_APPROVAL_TIMEOUT_SECONDS")
    if not raw:
        return _DEFAULT_TIMEOUT_SECONDS
    try:
        return float(raw)
    except ValueError:
        return _DEFAULT_TIMEOUT_SECONDS


class ApprovalGate:
    """Real approval gate: publish on C2, await decision or timeout-deny.

    One instance is shared for the process (registered on ``app.state``);
    each ``request()`` call creates its own pending-future entry keyed by a
    fresh approval id, so concurrent requests across sessions/turns do not
    interfere with each other.
    """

    def __init__(self, *, broadcaster: Any, timeout_seconds: float | None = None) -> None:
        self._broadcaster = broadcaster
        self._timeout_seconds = timeout_seconds if timeout_seconds is not None else _timeout_seconds()
        self._pending: dict[str, asyncio.Future[ApprovalResponse]] = {}

    async def request(self, req: ApprovalRequest) -> ApprovalResponse:
        approval_id = uuid.uuid4().hex
        loop = asyncio.get_running_loop()
        future: asyncio.Future[ApprovalResponse] = loop.create_future()
        self._pending[approval_id] = future

        payload: dict[str, Any] = req.get("payload") or {}
        event = {
            "type": "approval/requested",
            "approvalId": approval_id,
            "sessionId": req.get("sessionId"),
            "turnId": req.get("turnId"),
            "kind": req.get("kind"),
            "toolName": payload.get("toolName", payload.get("kind", "(unspecified)")),
            "payload": payload,
            "timeoutSeconds": self._timeout_seconds,
        }
        logger.info(
            "approval requested id=%s session=%s turn=%s tool=%s timeout=%.1fs",
            approval_id,
            event["sessionId"],
            event["turnId"],
            event["toolName"],
            self._timeout_seconds,
        )
        try:
            self._broadcaster.publish(event)
        except Exception:
            logger.debug("approval_gate: failed to publish approval/requested; continuing to await/timeout")

        start = time.monotonic()
        try:
            result = await asyncio.wait_for(future, timeout=self._timeout_seconds)
            logger.info(
                "approval id=%s resolved action=%r after %.2fs",
                approval_id,
                result.get("action"),
                time.monotonic() - start,
            )
            return result
        except TimeoutError:
            logger.warning(
                "approval id=%s TIMED OUT after %.1fs with no C2 decision -- defaulting to deny (decline)",
                approval_id,
                self._timeout_seconds,
            )
            return {"action": "decline"}
        finally:
            self._pending.pop(approval_id, None)
            if not future.done():
                future.cancel()

    def resolve(self, approval_id: str, action: str) -> bool:
        """Resolve a pending approval from a C2 client's decision POST.

        Returns True if a pending approval with this id existed and was
        resolved; False if the id is unknown (already resolved, timed out,
        or never existed) -- callers should treat False as a 404.
        """
        future = self._pending.get(approval_id)
        if future is None or future.done():
            return False
        normalized: ApprovalResponse = {"action": "accept"} if action == "accept" else {"action": "decline"}
        future.set_result(normalized)
        return True
