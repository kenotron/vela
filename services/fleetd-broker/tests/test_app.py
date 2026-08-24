"""Integration tests for the broker's HTTP/WS surface.

Runs the real fleetd_broker FastAPI app against a real ledger_service app
(services/ledger/), both driven in-process via httpx.ASGITransport -- no
sockets, no subprocess, but every byte of the frozen C3 contract is
exercised for real (not a hand-rolled fake), matching the ledger service's
own test convention (services/ledger/tests/).
"""

from __future__ import annotations

import httpx
import pytest
from fleetd_broker.app import create_app


def _ledger_available() -> bool:
    try:
        import ledger_service  # noqa: F401

        return True
    except ImportError:
        return False


LEDGER_AVAILABLE = _ledger_available()


@pytest.fixture
def ledger_app(tmp_path):
    if not LEDGER_AVAILABLE:
        pytest.skip(
            "ledger_service not installed in this venv -- install "
            "services/ledger in editable mode to run full integration tests"
        )
    from ledger_service.app import create_app as create_ledger_app

    return create_ledger_app(db_path=tmp_path / "ledger.db")


@pytest.fixture
async def clients(ledger_app):
    ledger_transport = httpx.ASGITransport(app=ledger_app)
    async with httpx.AsyncClient(
        transport=ledger_transport, base_url="http://ledger"
    ) as ledger_client:
        broker_app = create_app(ledger_url="http://ledger", heartbeat_interval_s=15.0)
        # Point the broker's internal ledger client at the same in-process
        # ledger app rather than a real network address.
        broker_app.state.ledger._client = httpx.AsyncClient(
            transport=ledger_transport, base_url="http://ledger"
        )
        broker_transport = httpx.ASGITransport(app=broker_app)
        async with httpx.AsyncClient(
            transport=broker_transport, base_url="http://broker"
        ) as broker_client:
            yield broker_app, broker_client, ledger_client


@pytest.mark.asyncio
async def test_dispatch_with_no_live_worker_returns_400(clients):
    _, broker_client, _ = clients
    resp = await broker_client.post(
        "/fleet/dispatch",
        json={
            "origin": {
                "session_id": "s1",
                "turn_id": "t1",
                "tool_call_id": "tc1",
            },
            "spec": {
                "title": "test job",
                "runtime": "shell",
                "prompt": "echo hi",
                "target": {"labels": ["linux"]},
            },
        },
    )
    assert resp.status_code == 400
    assert "UNREACHABLE" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_dispatch_after_registration_creates_ledger_job(clients):
    broker_app, broker_client, ledger_client = clients
    # Register a worker directly against the registry (WS session tested
    # separately below) so admission has a live target.
    broker_app.state.registry.register(
        machine_id="vela0", labels=["linux"], runtimes=["shell"]
    )

    resp = await broker_client.post(
        "/fleet/dispatch",
        json={
            "origin": {
                "session_id": "s1",
                "turn_id": "t1",
                "tool_call_id": "tc1",
            },
            "spec": {
                "title": "test job",
                "runtime": "shell",
                "prompt": "echo hi",
                "target": {"labels": ["linux"]},
            },
        },
    )
    assert resp.status_code == 202
    body = resp.json()
    assert body["machine_id"] == "vela0"
    assert body["status"] == "accepted"

    ledger_resp = await ledger_client.get(f"/ledger/jobs/{body['job_id']}")
    assert ledger_resp.status_code == 200
    assert ledger_resp.json()["status"] == "accepted"


@pytest.mark.asyncio
async def test_dispatch_is_idempotent_on_tool_call_id(clients):
    broker_app, broker_client, _ = clients
    broker_app.state.registry.register(
        machine_id="vela0", labels=["linux"], runtimes=["shell"]
    )
    payload = {
        "origin": {"session_id": "s1", "turn_id": "t1", "tool_call_id": "dup-tc"},
        "spec": {
            "title": "test job",
            "runtime": "shell",
            "prompt": "echo hi",
            "target": {"labels": ["linux"]},
        },
    }
    r1 = await broker_client.post("/fleet/dispatch", json=payload)
    r2 = await broker_client.post("/fleet/dispatch", json=payload)
    assert r1.json()["job_id"] == r2.json()["job_id"]


@pytest.mark.asyncio
async def test_decision_relay_without_connected_worker_returns_409(clients):
    broker_app, broker_client, _ = clients
    broker_app.state.sessions.bind_job("job-x", "vela0")
    resp = await broker_client.post(
        "/fleet/jobs/job-x/decision", json={"job_id": "job-x", "text": "in-place"}
    )
    assert resp.status_code == 409


@pytest.mark.asyncio
async def test_decision_relay_mismatched_job_id_returns_400(clients):
    _, broker_client, _ = clients
    resp = await broker_client.post(
        "/fleet/jobs/job-x/decision", json={"job_id": "job-y", "text": "in-place"}
    )
    assert resp.status_code == 400


@pytest.mark.asyncio
async def test_list_workers_reflects_registration(clients):
    broker_app, broker_client, _ = clients
    broker_app.state.registry.register(
        machine_id="vela0", labels=["linux"], runtimes=["shell"]
    )
    resp = await broker_client.get("/fleet/workers")
    assert resp.status_code == 200
    workers = resp.json()
    assert len(workers) == 1
    assert workers[0]["machine_id"] == "vela0"
    assert workers[0]["live"] is True


@pytest.mark.asyncio
async def test_healthz(clients):
    _, broker_client, _ = clients
    resp = await broker_client.get("/healthz")
    assert resp.status_code == 200
    assert resp.json() == {"status": "ok"}
