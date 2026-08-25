"""fanout-strategy-all-wiring residual: `strategy: "all"` dispatched end-to-end
through POST /fleet/dispatch (design doc 5.3), with 3+ fake workers.
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
        broker_app.state.ledger._client = httpx.AsyncClient(
            transport=ledger_transport, base_url="http://ledger"
        )
        broker_transport = httpx.ASGITransport(app=broker_app)
        async with httpx.AsyncClient(
            transport=broker_transport, base_url="http://broker"
        ) as broker_client:
            yield broker_app, broker_client, ledger_client


@pytest.mark.asyncio
async def test_fanout_dispatches_to_every_matching_live_worker(clients):
    broker_app, broker_client, ledger_client = clients
    for machine_id in ("gpu1", "gpu2", "gpu3"):
        broker_app.state.registry.register(
            machine_id=machine_id, labels=["linux", "has:gpu"], runtimes=["shell"]
        )
    # A non-matching worker must not receive a fan-out job.
    broker_app.state.registry.register(
        machine_id="cpu1", labels=["linux"], runtimes=["shell"]
    )

    resp = await broker_client.post(
        "/fleet/dispatch",
        json={
            "origin": {
                "session_id": "s1",
                "turn_id": "t1",
                "tool_call_id": "tc-fanout",
            },
            "spec": {
                "title": "fanout job",
                "runtime": "shell",
                "prompt": "echo hi",
                "target": {"labels": ["has:gpu"], "strategy": "all"},
            },
        },
    )
    assert resp.status_code == 202
    body = resp.json()
    assert body["strategy"] == "all"
    assert body["job_id"] is None
    machines = {j["machine_id"] for j in body["jobs"]}
    assert machines == {"gpu1", "gpu2", "gpu3"}

    job_ids = {j["job_id"] for j in body["jobs"]}
    assert len(job_ids) == 3, "each fan-out target must get its own ledger job"

    for job in body["jobs"]:
        ledger_resp = await ledger_client.get(f"/ledger/jobs/{job['job_id']}")
        assert ledger_resp.status_code == 200
        assert ledger_resp.json()["status"] == "accepted"

    workers_resp = await broker_client.get("/fleet/workers")
    by_machine = {w["machine_id"]: w for w in workers_resp.json()}
    assert by_machine["gpu1"]["active_jobs"] == 1
    assert by_machine["gpu2"]["active_jobs"] == 1
    assert by_machine["gpu3"]["active_jobs"] == 1
    assert by_machine["cpu1"]["active_jobs"] == 0


@pytest.mark.asyncio
async def test_fanout_with_no_matching_worker_returns_400(clients):
    _, broker_client, _ = clients
    resp = await broker_client.post(
        "/fleet/dispatch",
        json={
            "origin": {
                "session_id": "s1",
                "turn_id": "t1",
                "tool_call_id": "tc-fanout-none",
            },
            "spec": {
                "title": "fanout job",
                "runtime": "shell",
                "prompt": "echo hi",
                "target": {"labels": ["has:gpu"], "strategy": "all"},
            },
        },
    )
    assert resp.status_code == 400
    assert "UNREACHABLE" in resp.json()["detail"]


@pytest.mark.asyncio
async def test_fanout_is_idempotent_per_target_on_retry(clients):
    broker_app, broker_client, _ = clients
    broker_app.state.registry.register(
        machine_id="gpu1", labels=["has:gpu"], runtimes=["shell"]
    )
    broker_app.state.registry.register(
        machine_id="gpu2", labels=["has:gpu"], runtimes=["shell"]
    )
    payload = {
        "origin": {"session_id": "s1", "turn_id": "t1", "tool_call_id": "dup-fanout"},
        "spec": {
            "title": "fanout job",
            "runtime": "shell",
            "prompt": "echo hi",
            "target": {"labels": ["has:gpu"], "strategy": "all"},
        },
    }
    r1 = await broker_client.post("/fleet/dispatch", json=payload)
    r2 = await broker_client.post("/fleet/dispatch", json=payload)
    ids1 = sorted(j["job_id"] for j in r1.json()["jobs"])
    ids2 = sorted(j["job_id"] for j in r2.json()["jobs"])
    assert ids1 == ids2
