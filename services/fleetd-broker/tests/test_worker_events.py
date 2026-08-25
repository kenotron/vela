"""Tests for worker_events.py -- event-kind -> ledger-write mapping (D4/D5, 5.2).

Uses a fake LedgerClient-shaped object (records calls) so the ledger's own
service is never started for these unit tests -- kept for a lightweight,
fast, in-process suite. Real HTTP wiring is covered by test_app.py against
an in-process fake ledger app.
"""

from __future__ import annotations

import pytest
from fleetd_broker.ledger_client import ProgressCoalescer
from fleetd_broker.registry import WorkerRegistry
from fleetd_broker.sessions import SessionTable
from fleetd_broker.worker_events import MalformedEventError, WorkerEventHandler


class FakeLedgerClient:
    def __init__(self) -> None:
        self.calls: list[tuple[str, dict]] = []

    async def patch_job(self, job_id, **patch):
        self.calls.append((job_id, patch))
        return {"job_id": job_id, **patch}


@pytest.fixture
def handler():
    registry = WorkerRegistry()
    registry.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    sessions = SessionTable()
    sessions.bind_job("job-1", "vela0")
    registry.increment_active_jobs("vela0", +1)
    ledger = FakeLedgerClient()
    coalescer = ProgressCoalescer(interval_s=100.0)
    h = WorkerEventHandler(
        registry=registry, sessions=sessions, ledger=ledger, coalescer=coalescer
    )
    return h, ledger, registry, sessions


@pytest.mark.asyncio
async def test_started_sets_running(handler):
    h, ledger, _, _ = handler
    await h.handle({"kind": "started", "job_id": "job-1", "ts": 1, "runtime": "shell"})
    assert ledger.calls == [("job-1", {"status": "running"})]


@pytest.mark.asyncio
async def test_first_progress_flushes_immediately(handler):
    h, ledger, _, _ = handler
    await h.handle(
        {
            "kind": "progress",
            "job_id": "job-1",
            "ts": 1,
            "message": "cloned",
            "percent": 20,
        }
    )
    assert len(ledger.calls) == 1
    job_id, patch = ledger.calls[0]
    assert job_id == "job-1"
    assert patch["status"] == "running"
    assert patch["progress_entry"]["message"] == "cloned"
    assert patch["progress_entry"]["percent"] == 20


@pytest.mark.asyncio
async def test_second_progress_within_interval_is_held(handler):
    h, ledger, _, _ = handler
    await h.handle({"kind": "progress", "job_id": "job-1", "ts": 1, "message": "first"})
    await h.handle(
        {"kind": "progress", "job_id": "job-1", "ts": 2, "message": "second"}
    )
    # Only the first flushed; the second is held by the coalescer.
    assert len(ledger.calls) == 1


@pytest.mark.asyncio
async def test_attention_is_never_coalesced(handler):
    h, ledger, _, _ = handler
    await h.handle({"kind": "progress", "job_id": "job-1", "ts": 1, "message": "first"})
    await h.handle(
        {
            "kind": "attention",
            "job_id": "job-1",
            "ts": 2,
            "reason": "pick one",
            "options": ["a", "b"],
        }
    )
    assert len(ledger.calls) == 2
    _job_id, patch = ledger.calls[1]
    assert patch["status"] == "needs_attention"
    assert patch["attention"]["required"] is True
    assert patch["attention"]["reason"] == "pick one"
    assert patch["attention"]["options"] == ["a", "b"]


@pytest.mark.asyncio
async def test_cost_event(handler):
    h, ledger, _, _ = handler
    await h.handle(
        {"kind": "cost", "job_id": "job-1", "ts": 1, "usd": 0.41, "tokens": 88300}
    )
    _job_id, patch = ledger.calls[0]
    assert patch["cost"] == {"usd": 0.41, "tokens": 88300}


@pytest.mark.asyncio
async def test_finished_success_sets_done_and_flushes_pending(handler):
    h, ledger, registry, sessions = handler
    await h.handle({"kind": "progress", "job_id": "job-1", "ts": 1, "message": "first"})
    await h.handle({"kind": "progress", "job_id": "job-1", "ts": 2, "message": "held"})
    await h.handle(
        {
            "kind": "finished",
            "job_id": "job-1",
            "ts": 3,
            "exit_code": 0,
            "result": {"pr_url": "x"},
        }
    )
    # calls: progress(first) flush, progress(held) flush-on-terminal, finished(done)
    assert len(ledger.calls) == 3
    assert ledger.calls[1][1]["progress_entry"]["message"] == "held"
    assert ledger.calls[2][1]["status"] == "done"
    assert ledger.calls[2][1]["result"] == {"pr_url": "x"}  # noqa: keep
    # active job count decremented and binding removed
    assert registry.get("vela0").active_jobs == 0
    assert sessions.machine_for_job("job-1") is None


@pytest.mark.asyncio
async def test_finished_nonzero_exit_sets_failed(handler):
    h, ledger, _, _ = handler
    await h.handle({"kind": "finished", "job_id": "job-1", "ts": 1, "exit_code": 1})
    assert ledger.calls[-1][1]["status"] == "failed"


@pytest.mark.asyncio
async def test_failed_kind_sets_failed(handler):
    h, ledger, _, _ = handler
    await h.handle({"kind": "failed", "job_id": "job-1", "ts": 1, "error": "boom"})
    assert ledger.calls[-1][1]["status"] == "failed"


@pytest.mark.asyncio
async def test_unknown_kind_raises_malformed(handler):
    h, _, _, _ = handler
    with pytest.raises(MalformedEventError):
        await h.handle({"kind": "bogus", "job_id": "job-1"})


@pytest.mark.asyncio
async def test_missing_job_id_raises_malformed(handler):
    h, _, _, _ = handler
    with pytest.raises(MalformedEventError):
        await h.handle({"kind": "started"})
