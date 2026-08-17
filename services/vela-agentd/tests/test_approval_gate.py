"""F2 adversarial test: privileged tool call with NO C2 client attached.

Must time out and deny -- never hang the server (F-4).
"""

from __future__ import annotations

import asyncio
import time

import pytest

from vela_agentd_http._approval_gate import ApprovalGate
from vela_agentd_http._c2_broadcaster import EventBroadcaster


@pytest.mark.asyncio
async def test_no_c2_client_times_out_and_denies() -> None:
    """No subscriber ever calls resolve() -- must deny after the configured timeout."""
    broadcaster = EventBroadcaster()  # no subscribers attached at all
    gate = ApprovalGate(broadcaster=broadcaster, timeout_seconds=0.2)

    start = time.monotonic()
    result = await asyncio.wait_for(
        gate.request(
            {
                "sessionId": "s1",
                "turnId": "t1",
                "approvalId": "unused",
                "kind": "privileged_tool",
                "payload": {"toolName": "delete_everything"},
                "timeoutMs": 200,
            }
        ),
        timeout=2.0,  # outer safety net: if the gate hangs, THIS fails, not the process
    )
    elapsed = time.monotonic() - start

    assert result == {"action": "decline"}
    # Resolved close to the configured timeout, not immediately and not hanging.
    assert 0.15 <= elapsed < 1.5


@pytest.mark.asyncio
async def test_c2_client_decision_resolves_before_timeout() -> None:
    """A C2 client that posts a decision before the timeout wins immediately."""
    broadcaster = EventBroadcaster()
    gate = ApprovalGate(broadcaster=broadcaster, timeout_seconds=5.0)

    async def _client_decides_soon() -> None:
        await asyncio.sleep(0.05)
        # Simulate the client reading the approval id off the C2 event stream:
        # in real usage it comes from the published approval/requested event;
        # here we grab it via the gate's internal pending map for test simplicity.
        (approval_id,) = list(gate._pending.keys())  # noqa: SLF001 - test introspection
        assert gate.resolve(approval_id, "accept") is True

    task = asyncio.create_task(_client_decides_soon())
    start = time.monotonic()
    result = await asyncio.wait_for(
        gate.request(
            {
                "sessionId": "s1",
                "turnId": "t1",
                "approvalId": "unused",
                "kind": "privileged_tool",
                "payload": {"toolName": "read_file"},
                "timeoutMs": 5000,
            }
        ),
        timeout=2.0,
    )
    elapsed = time.monotonic() - start
    await task

    assert result == {"action": "accept"}
    assert elapsed < 1.0  # resolved fast, well before the 5s timeout


@pytest.mark.asyncio
async def test_resolve_unknown_id_returns_false() -> None:
    broadcaster = EventBroadcaster()
    gate = ApprovalGate(broadcaster=broadcaster, timeout_seconds=5.0)
    assert gate.resolve("nonexistent-id", "accept") is False
