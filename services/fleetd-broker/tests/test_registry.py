"""Tests for the worker registry / admission target selection (D3)."""

from __future__ import annotations

import pytest
from fleetd_broker.registry import NoLiveWorkerError, WorkerRegistry


def test_register_and_list_live():
    reg = WorkerRegistry(heartbeat_interval_s=15.0)
    reg.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    live = reg.list_live()
    assert len(live) == 1
    assert live[0].machine_id == "vela0"


def test_stale_heartbeat_is_not_live():
    reg = WorkerRegistry(heartbeat_interval_s=0.01)
    reg.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    # Force an old heartbeat by manipulating the record directly.
    record = reg.get("vela0")
    assert record is not None
    record.last_heartbeat_ms -= 10_000
    assert reg.list_live() == []


def test_disconnect_marks_not_live_but_retains_record():
    reg = WorkerRegistry()
    reg.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    reg.disconnect("vela0")
    assert reg.list_live() == []
    assert reg.get("vela0") is not None  # retained for reconciliation


def test_select_target_by_pinned_machine_id():
    reg = WorkerRegistry()
    reg.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    reg.register(machine_id="gpu1", labels=["linux", "has:gpu"], runtimes=["shell"])
    target = reg.select_target(machine_id="gpu1", labels=[])
    assert target.machine_id == "gpu1"


def test_select_target_pinned_missing_raises_no_live_worker():
    reg = WorkerRegistry()
    reg.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    with pytest.raises(NoLiveWorkerError):
        reg.select_target(machine_id="nonexistent", labels=[])


def test_select_target_by_labels_least_loaded():
    reg = WorkerRegistry()
    reg.register(machine_id="a", labels=["linux"], runtimes=["shell"])
    reg.register(machine_id="b", labels=["linux"], runtimes=["shell"])
    reg.increment_active_jobs("a", +3)
    target = reg.select_target(machine_id=None, labels=["linux"])
    assert target.machine_id == "b"


def test_select_target_no_label_match_raises():
    reg = WorkerRegistry()
    reg.register(machine_id="a", labels=["linux"], runtimes=["shell"])
    with pytest.raises(NoLiveWorkerError):
        reg.select_target(machine_id=None, labels=["has:gpu"])


def test_select_targets_all_resolves_fanout_set():
    reg = WorkerRegistry()
    reg.register(machine_id="gpu1", labels=["linux", "has:gpu"], runtimes=["shell"])
    reg.register(machine_id="gpu2", labels=["linux", "has:gpu"], runtimes=["shell"])
    reg.register(machine_id="cpu1", labels=["linux"], runtimes=["shell"])
    targets = reg.select_targets_all(labels=["has:gpu"])
    assert {t.machine_id for t in targets} == {"gpu1", "gpu2"}
