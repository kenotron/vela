"""Item 4 (G1): zero lost events across a service restart.

Injects a known-cardinality sequence of job creates/updates against a
file-backed LedgerDB, closes that connection (simulating process death — no
graceful flush beyond normal commit semantics, matching the Android lane's
substitution rationale for a real kill/restart), opens a brand-new LedgerDB
instance at the same path (simulating the restarted process), and asserts
the final state matches exactly what should have survived.
"""

from __future__ import annotations

from ledger_service.db import LedgerDB


def test_zero_lost_events_across_restart(tmp_path):
    db_path = tmp_path / "ledger.db"

    # -- "process 1": create N jobs, apply M updates, then die (no clean shutdown) --
    db1 = LedgerDB(path=db_path)
    created_ids = []
    for i in range(5):
        record = db1.create_job(
            session_id="s1",
            turn_id="t1",
            tool_call_id=f"tc-{i}",
            spec={"i": i},
        )
        created_ids.append(record["job_id"])

    # Apply updates to a subset, including a full lifecycle on one job.
    db1.update_job(
        created_ids[0], status="running", progress_entry={"message": "started"}
    )
    db1.update_job(
        created_ids[0],
        status="needs_attention",
        attention={
            "required": True,
            "reason": "approval needed",
            "options": ["yes", "no"],
        },
    )
    db1.record_decision(created_ids[0], new_status="done")

    db1.update_job(created_ids[1], status="running")
    db1.update_job(created_ids[2], status="failed", result={"error": "boom"})

    expected_final = {job_id: db1.get_job(job_id) for job_id in created_ids}

    # Simulate a hard kill: close the connection with no extra flush call beyond
    # the commits already issued by each write (WAL + synchronous=FULL is what's
    # under test here, not any explicit application-level flush).
    db1.close()

    # -- "process 2": restart, open a fresh LedgerDB at the same file path --
    db2 = LedgerDB(path=db_path)

    for job_id, expected in expected_final.items():
        assert expected is not None
        actual = db2.get_job(job_id)
        assert actual is not None, f"job {job_id} was lost across restart"
        assert actual["status"] == expected["status"]
        assert actual["progress"] == expected["progress"]
        assert actual["attention"] == expected["attention"]
        assert actual["result"] == expected["result"]

    all_rows = db2.list_jobs(limit=1000)
    assert len(all_rows) == 5, "job count changed across restart"

    db2.close()
