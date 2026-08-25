"""Stage L1 additions (design doc §12): version field on the wire, and the
terminal-state guard on record_decision (§5.3 / RF-7).
"""

from __future__ import annotations

import pytest

from ledger_service.db import JobAlreadyTerminalError, LedgerDB
from ledger_service.models import Job


def test_version_field_present_and_increments_on_update(tmp_path):
    db = LedgerDB(path=tmp_path / "ledger.db")
    record = db.create_job(session_id="s1", turn_id="t1", tool_call_id="tc1", spec={})
    job = Job.from_record(record)
    assert job.version == 1

    updated = db.update_job(record["job_id"], status="running")
    assert Job.from_record(updated).version == 2

    decided = db.record_decision(record["job_id"], new_status="done")
    assert Job.from_record(decided).version == 3


@pytest.mark.parametrize("terminal_status", ["done", "failed", "cancelled"])
def test_decision_against_terminal_job_is_rejected(tmp_path, terminal_status):
    db = LedgerDB(path=tmp_path / "ledger.db")
    record = db.create_job(session_id="s1", turn_id="t1", tool_call_id="tc1", spec={})
    db.record_decision(record["job_id"], new_status=terminal_status)

    with pytest.raises(JobAlreadyTerminalError):
        db.record_decision(record["job_id"], new_status="accepted")


def test_repeated_identical_decision_against_terminal_job_is_a_noop(tmp_path):
    db = LedgerDB(path=tmp_path / "ledger.db")
    record = db.create_job(session_id="s1", turn_id="t1", tool_call_id="tc1", spec={})
    first = db.record_decision(record["job_id"], new_status="done")

    # Calling it again with the same terminal status converges harmlessly
    # (design doc §4.1) rather than raising.
    second = db.record_decision(record["job_id"], new_status="done")
    assert second["status"] == "done"
    assert second["version"] == first["version"]


def test_non_terminal_decision_still_works_normally(tmp_path):
    db = LedgerDB(path=tmp_path / "ledger.db")
    record = db.create_job(session_id="s1", turn_id="t1", tool_call_id="tc1", spec={})
    decided = db.record_decision(record["job_id"], new_status="running")
    assert decided["status"] == "running"
    assert decided["attention"]["required"] is False
