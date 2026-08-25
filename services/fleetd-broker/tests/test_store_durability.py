"""durable-broker-store residual: a real close-and-reopen test, matching
services/ledger's test_durability_restart.py pattern (design doc 4.1).
"""

from __future__ import annotations

from fleetd_broker.registry import WorkerRegistry
from fleetd_broker.sessions import SessionTable
from fleetd_broker.store import BrokerStore


def test_worker_and_job_bindings_survive_restart(tmp_path):
    db_path = tmp_path / "broker.db"

    # -- "process 1": register workers, bind jobs, then die (no clean flush
    #    beyond the commits already issued by each write) --
    store1 = BrokerStore(db_path)
    registry1 = WorkerRegistry(heartbeat_interval_s=15.0, store=store1)
    sessions1 = SessionTable(store=store1)

    registry1.register(machine_id="vela0", labels=["linux"], runtimes=["shell"])
    registry1.register(
        machine_id="gpu1", labels=["linux", "has:gpu"], runtimes=["shell"]
    )
    registry1.increment_active_jobs("vela0", +2)
    sessions1.bind_job("job-a", "vela0")
    sessions1.bind_job("job-b", "gpu1")
    sessions1.unbind_job("job-b")
    sessions1.bind_job("job-c", "gpu1")

    store1.close()

    # -- "process 2": restart, open a fresh BrokerStore/registry/sessions at
    #    the same path --
    store2 = BrokerStore(db_path)
    registry2 = WorkerRegistry(heartbeat_interval_s=15.0, store=store2)
    sessions2 = SessionTable(store=store2)

    vela0 = registry2.get("vela0")
    assert vela0 is not None, "worker vela0 was lost across restart"
    assert vela0.labels == {"linux"}
    assert vela0.active_jobs == 2
    # No session can still be open across a restart -- restored records
    # start disconnected until the worker actually reconnects.
    assert vela0.connected is False

    gpu1 = registry2.get("gpu1")
    assert gpu1 is not None
    assert gpu1.labels == {"linux", "has:gpu"}

    assert sessions2.machine_for_job("job-a") == "vela0"
    assert sessions2.machine_for_job("job-b") is None  # was unbound before restart
    assert sessions2.machine_for_job("job-c") == "gpu1"

    store2.close()


def test_load_workers_reflects_latest_upsert(tmp_path):
    store = BrokerStore(tmp_path / "broker.db")
    registry = WorkerRegistry(heartbeat_interval_s=15.0, store=store)
    registry.register(machine_id="a", labels=["x"], runtimes=["shell"])
    registry.register(machine_id="a", labels=["x", "y"], runtimes=["shell", "py"])

    rows = store.load_workers()
    assert len(rows) == 1
    assert set(rows[0]["labels"]) == {"x", "y"}
    store.close()
