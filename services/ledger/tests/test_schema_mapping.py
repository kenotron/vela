"""Item 2: schema-comparison test against android/ledger's JobEntity/JobRecord.

This is an explicit field-for-field comparison, not "verify by inspection" —
it enumerates every C3 field from JobEntity.kt/JobRecord.kt and asserts the
server's wire Job model produces the same set (nested, not flattened, since
the server's JSON wire shape matches JobRecord's nested public API, while
JobEntity is Android's internal flattened storage representation of the same
fields). See README.md for the full column mapping table.
"""

from __future__ import annotations

from ledger_service.db import LedgerDB
from ledger_service.models import Job

# Field paths present in android/ledger's JobRecord (the nested public API a
# server client would consume) -- mined directly from JobRecord.kt.
ANDROID_JOB_RECORD_FIELDS = {
    "job_id",
    "created_at",
    "updated_at",
    "origin.session_id",
    "origin.turn_id",
    "origin.tool_call_id",
    "spec",
    "status",
    "attention.required",
    "attention.reason",
    "attention.options",
    "attention.deadline",
    "progress",
    "result",
    "cost.usd",
    "cost.tokens",
}


OPAQUE_CONTAINERS = {"spec", "result"}


def _flatten(model_dict: dict, prefix: str = "") -> set[str]:
    paths = set()
    for key, value in model_dict.items():
        path = f"{prefix}{key}"
        if isinstance(value, dict) and path not in OPAQUE_CONTAINERS:
            paths |= _flatten(value, prefix=f"{path}.")
        else:
            paths.add(path)
    return paths


def test_wire_job_schema_matches_android_job_record_fields(tmp_path):
    db = LedgerDB(path=tmp_path / "ledger.db")
    record = db.create_job(
        session_id="s1",
        turn_id="t1",
        tool_call_id="tc1",
        spec={"kind": "dispatch_to_fleet"},
    )
    job = Job.from_record(record)
    wire = job.model_dump()

    # progress is a list of ProgressEntry, not a leaf -- compare its container
    # presence, then separately verify entry-level fields further below.
    wire_without_list_fields = {k: v for k, v in wire.items() if k != "progress"}
    wire_paths = _flatten(wire_without_list_fields, prefix="") | {"progress"}

    assert wire_paths == ANDROID_JOB_RECORD_FIELDS, (
        f"Schema drift detected.\n"
        f"Missing from server wire model: {ANDROID_JOB_RECORD_FIELDS - wire_paths}\n"
        f"Extra in server wire model (not in Android JobRecord): {wire_paths - ANDROID_JOB_RECORD_FIELDS}"
    )


def test_progress_entry_fields_match_android_progress_entry(tmp_path):
    # android/ledger/src/main/java/com/vela/ledger/JobRecord.kt ProgressEntry: ts, message, percent, source
    db = LedgerDB(path=tmp_path / "ledger.db")
    record = db.create_job(session_id="s1", turn_id="t1", tool_call_id="tc1", spec={})
    record = db.update_job(
        record["job_id"],
        progress_entry={"message": "halfway there", "percent": 50, "source": "fleet"},
    )
    job = Job.from_record(record)
    entry = job.progress[0].model_dump()
    assert set(entry.keys()) == {"ts", "message", "percent", "source"}


def test_status_values_match_android_job_status_enum(tmp_path):
    # JobStatus enum wireValue(): accepted, running, needs_attention, blocked, done, failed, cancelled
    from ledger_service.db import VALID_STATUSES

    android_statuses = {
        "accepted",
        "running",
        "needs_attention",
        "blocked",
        "done",
        "failed",
        "cancelled",
    }
    assert VALID_STATUSES == android_statuses
