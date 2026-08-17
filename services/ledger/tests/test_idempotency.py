"""Item 5 (G2): job creation is idempotent on origin.tool_call_id."""

from __future__ import annotations

from ledger_service.db import LedgerDB


def test_creating_job_twice_with_same_tool_call_id_does_not_duplicate(tmp_path):
    db = LedgerDB(path=tmp_path / "ledger.db")

    first = db.create_job(
        session_id="s1", turn_id="t1", tool_call_id="tc-dup", spec={"a": 1}
    )
    second = db.create_job(
        session_id="s1", turn_id="t1", tool_call_id="tc-dup", spec={"a": 1}
    )

    assert first["job_id"] == second["job_id"]

    rows = db.list_jobs(limit=1000)
    matching = [r for r in rows if r["origin"]["tool_call_id"] == "tc-dup"]
    assert len(matching) == 1


def test_different_tool_call_ids_create_distinct_jobs(tmp_path):
    db = LedgerDB(path=tmp_path / "ledger.db")
    a = db.create_job(session_id="s1", turn_id="t1", tool_call_id="tc-a", spec={})
    b = db.create_job(session_id="s1", turn_id="t1", tool_call_id="tc-b", spec={})
    assert a["job_id"] != b["job_id"]
