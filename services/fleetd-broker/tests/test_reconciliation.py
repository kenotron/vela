"""reconnect-reconciliation residual: a reconnecting worker's job-set is
diffed against the broker's own bindings and corrected (design doc 4.1),
using deterministic in-process fakes -- same style as test_registry.py /
test_worker_events.py.
"""

from __future__ import annotations

from typing import Any

import pytest
from fleetd_broker.reconciliation import Reconciler
from fleetd_broker.registry import WorkerRegistry
from fleetd_broker.sessions import SessionTable


class FakeLedgerClient:
    def __init__(self) -> None:
        self.patches: list[dict[str, Any]] = []

    async def patch_job(self, job_id: str, **patch: Any) -> dict[str, Any]:
        entry = {"job_id": job_id, **patch}
        self.patches.append(entry)
        return entry


@pytest.fixture
def rig():
    registry = WorkerRegistry(heartbeat_interval_s=15.0)
    sessions = SessionTable()
    ledger = FakeLedgerClient()
    reconciler = Reconciler(registry=registry, sessions=sessions, ledger=ledger)
    registry.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    return registry, sessions, ledger, reconciler


@pytest.mark.asyncio
async def test_vanished_job_is_marked_failed_and_unbound(rig):
    registry, sessions, ledger, reconciler = rig
    sessions.bind_job("job-a", "vela0")
    sessions.bind_job("job-b", "vela0")
    registry.increment_active_jobs("vela0", +2)

    # Worker reconnects only reporting job-a -- job-b vanished while
    # disconnected.
    result = await reconciler.reconcile("vela0", ["job-a"])

    assert result.vanished == ["job-b"]
    assert result.resumed == []
    assert sessions.machine_for_job("job-b") is None
    assert sessions.machine_for_job("job-a") == "vela0"
    assert registry.get("vela0").active_jobs == 1

    failed_patches = [p for p in ledger.patches if p["job_id"] == "job-b"]
    assert len(failed_patches) == 1
    assert failed_patches[0]["status"] == "failed"


@pytest.mark.asyncio
async def test_worker_reported_job_broker_did_not_know_is_resumed(rig):
    _registry, sessions, ledger, reconciler = rig
    # Broker has no binding for job-c (e.g. lost before a durable-store
    # write landed), but the worker still reports running it.
    result = await reconciler.reconcile("vela0", ["job-c"])

    assert result.resumed == ["job-c"]
    assert result.vanished == []
    assert sessions.machine_for_job("job-c") == "vela0"
    assert ledger.patches == []


@pytest.mark.asyncio
async def test_matching_job_sets_reconcile_to_no_change(rig):
    _registry, sessions, ledger, reconciler = rig
    sessions.bind_job("job-a", "vela0")
    result = await reconciler.reconcile("vela0", ["job-a"])
    assert result.resumed == []
    assert result.vanished == []
    assert sessions.machine_for_job("job-a") == "vela0"
    assert ledger.patches == []


@pytest.mark.asyncio
async def test_empty_reported_set_marks_all_known_jobs_vanished(rig):
    registry, sessions, _ledger, reconciler = rig
    sessions.bind_job("job-a", "vela0")
    sessions.bind_job("job-b", "vela0")
    registry.increment_active_jobs("vela0", +2)

    result = await reconciler.reconcile("vela0", [])

    assert sorted(result.vanished) == ["job-a", "job-b"]
    assert registry.get("vela0").active_jobs == 0
    assert sessions.machine_for_job("job-a") is None
    assert sessions.machine_for_job("job-b") is None
