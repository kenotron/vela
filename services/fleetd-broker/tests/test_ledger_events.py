"""Integration test for issue #32: a decision posted to the ledger actually
resumes the fleet job, not just the ledger's status flag.

Unlike test_app.py, this test runs both services over **real network
sockets** (via uvicorn), not httpx.ASGITransport. That's a deliberate choice:
httpx's ASGITransport fully buffers a response body before returning it to
the client, so it cannot carry a genuinely open-ended SSE stream -- an
in-process attempt to read /ledger/events through it simply hangs forever.
Real sockets are also what the broker actually uses in production (the
default LedgerClient talks HTTP, not ASGITransport), so this is exercising
the real code path end to end:

    POST /ledger/jobs/{id}/decision            (real HTTP)
    -> ledger publishes "job.decided" on /ledger/events (real SSE stream)
    -> LedgerEventSubscriber consumes it        (real HTTP GET, streaming)
    -> SessionTable.relay_decision pushes to the worker's live session

A fake WS-session double stands in for the not-yet-built
`velafleet-worker` process (mirroring the existing
`test_decision_relay_without_connected_worker_returns_409` pattern).
"""

from __future__ import annotations

import asyncio
import socket

import httpx
import pytest
import uvicorn
from fleetd_broker.app import create_app
from fleetd_broker.ledger_events import LedgerEventSubscriber


def _ledger_available() -> bool:
    try:
        import ledger_service  # noqa: F401

        return True
    except ImportError:
        return False


LEDGER_AVAILABLE = _ledger_available()


def _free_port() -> int:
    s = socket.socket()
    try:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]
    finally:
        s.close()


class _RunningServer:
    def __init__(self, server: uvicorn.Server, task: asyncio.Task[None]) -> None:
        self.server = server
        self.task = task

    async def stop(self) -> None:
        self.server.should_exit = True
        await self.task


async def _start_uvicorn(app, port: int) -> _RunningServer:
    config = uvicorn.Config(app, host="127.0.0.1", port=port, log_level="warning")
    server = uvicorn.Server(config)
    task = asyncio.create_task(server.serve())
    while not server.started:
        await asyncio.sleep(0.01)
    return _RunningServer(server, task)


@pytest.fixture
async def live_servers(tmp_path):
    if not LEDGER_AVAILABLE:
        pytest.skip(
            "ledger_service not installed in this venv -- install "
            "services/ledger in editable mode to run full integration tests"
        )
    from ledger_service.app import create_app as create_ledger_app

    ledger_port = _free_port()
    broker_port = _free_port()

    ledger_app = create_ledger_app(db_path=tmp_path / "ledger.db")
    broker_app = create_app(
        ledger_url=f"http://127.0.0.1:{ledger_port}", heartbeat_interval_s=15.0
    )

    ledger_server = await _start_uvicorn(ledger_app, ledger_port)
    broker_server = await _start_uvicorn(broker_app, broker_port)

    ledger_base = f"http://127.0.0.1:{ledger_port}"
    broker_base = f"http://127.0.0.1:{broker_port}"

    async with httpx.AsyncClient(base_url=ledger_base) as ledger_client:
        yield broker_app, ledger_client, broker_base

    await broker_server.stop()
    await ledger_server.stop()


class FakeWorkerSender:
    """Stand-in for a connected worker's WebSocket session."""

    def __init__(self) -> None:
        self.received: list[tuple[str, str, str]] = []
        self.event = asyncio.Event()

    async def send_decision(self, machine_id: str, job_id: str, text: str) -> None:
        self.received.append((machine_id, job_id, text))
        self.event.set()


@pytest.mark.asyncio
async def test_decision_causes_resume_via_sse_subscriber(live_servers):
    """Full round-trip: POST a decision to the ledger, and -- with no direct
    call to the broker's own relay endpoint -- the bound worker session
    receives the push, proving the job actually resumes."""
    broker_app, ledger_client, _broker_base = live_servers

    # Create a real ledger job to decide on.
    create_resp = await ledger_client.post(
        "/ledger/jobs",
        json={
            "origin": {
                "session_id": "s1",
                "turn_id": "t1",
                "tool_call_id": "tc-resume-1",
            },
            "spec": {"title": "waiting job"},
            "status": "needs_attention",
        },
    )
    assert create_resp.status_code == 201
    job_id = create_resp.json()["job_id"]

    # Bind the job to a worker and attach a fake connected session -- this
    # is what "a live worker is holding this job open, awaiting a decision"
    # looks like from the broker's perspective.
    broker_app.state.sessions.bind_job(job_id, "vela0")
    fake_sender = FakeWorkerSender()
    broker_app.state.sessions.attach_sender("vela0", fake_sender)

    subscriber: LedgerEventSubscriber = broker_app.state.ledger_events
    subscriber.start()
    try:
        # Give the subscriber a moment to establish its SSE connection
        # before the decision is posted, so the event isn't missed.
        await asyncio.sleep(0.2)

        # The actual decision round-trip: POST straight to the ledger, the
        # way a human's swipe decision does -- never touching the broker's
        # /fleet/jobs/{id}/decision endpoint directly.
        decide_resp = await ledger_client.post(
            f"/ledger/jobs/{job_id}/decision",
            json={"new_status": "running"},
        )
        assert decide_resp.status_code == 200
        assert decide_resp.json()["status"] == "running"

        # Give the subscriber's background task a chance to consume the SSE
        # event and relay it.
        await asyncio.wait_for(fake_sender.event.wait(), timeout=5.0)
    finally:
        await subscriber.stop()

    assert len(fake_sender.received) == 1
    machine_id, relayed_job_id, text = fake_sender.received[0]
    assert machine_id == "vela0"
    assert relayed_job_id == job_id
    assert text == "running"

    # And the ledger's own record reflects the decision durably -- the
    # relay is additive to persistence, not a replacement for it.
    job_resp = await ledger_client.get(f"/ledger/jobs/{job_id}")
    assert job_resp.json()["status"] == "running"


@pytest.mark.asyncio
async def test_handle_event_ignores_jobs_this_broker_does_not_own(live_servers):
    """A job.decided event for a job with no known machine binding (e.g. it
    belongs to a different broker instance, or was never dispatched via the
    fleet plane) must not raise or attempt a relay."""
    broker_app, _, _ = live_servers
    subscriber: LedgerEventSubscriber = broker_app.state.ledger_events

    # Should not raise even though "unbound-job" has no session binding.
    await subscriber.handle_event(
        "job.decided", {"job_id": "unbound-job", "status": "running"}
    )


@pytest.mark.asyncio
async def test_handle_event_swallows_disconnected_worker(live_servers):
    """If the bound worker isn't currently connected, the subscriber logs
    and moves on rather than crashing the loop -- the decision remains
    durably recorded in the ledger even though live delivery failed."""
    broker_app, _, _ = live_servers
    subscriber: LedgerEventSubscriber = broker_app.state.ledger_events
    broker_app.state.sessions.bind_job("job-orphaned", "vela0")

    # No sender attached for vela0 -- relay_decision raises
    # WorkerUnavailableError internally; handle_event must swallow it.
    await subscriber.handle_event(
        "job.decided", {"job_id": "job-orphaned", "status": "running"}
    )
