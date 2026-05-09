import json
from datetime import datetime, timezone
from amplifierd_jobs.models import Job, JobRun


def test_job_roundtrip():
    job = Job(
        id="test-id",
        name="morning summary",
        trigger_type="cron",
        schedule="0 9 * * 1-5",
        prompt="summarize my emails",
        session_mode="fresh",
    )
    data = json.loads(job.to_json())
    restored = Job.from_dict(data)
    assert restored.id == job.id
    assert restored.name == job.name
    assert restored.trigger_type == job.trigger_type
    assert restored.schedule == job.schedule
    assert restored.session_mode == job.session_mode
    assert restored.enabled is True
    assert restored.persistent_session_id is None


def test_job_persistent_session_id_roundtrip():
    job = Job(
        id="test-id",
        name="monitor",
        trigger_type="loop",
        schedule="30m",
        prompt="check status",
        session_mode="persistent",
        persistent_session_id="sess-abc123",
    )
    data = json.loads(job.to_json())
    restored = Job.from_dict(data)
    assert restored.persistent_session_id == "sess-abc123"


def test_job_run_roundtrip():
    run = JobRun(
        id="run-id",
        job_id="job-id",
        job_name="test job",
        session_id="sess-xyz",
        status="success",
        source="scheduled",
    )
    data = json.loads(run.to_json())
    restored = JobRun.from_dict(data)
    assert restored.id == run.id
    assert restored.status == run.status
    assert restored.ended_at is None


def test_job_run_all_statuses():
    for status in ("running", "success", "failed", "cancelled"):
        run = JobRun(
            id="x",
            job_id="y",
            job_name="n",
            session_id="s",
            status=status,
            source="manual",
        )
        assert json.loads(run.to_json())["status"] == status


def test_job_to_api_dict_includes_computed_fields():
    job = Job(
        id="test-id",
        name="test",
        trigger_type="loop",
        schedule="5m",
        prompt="do something",
        session_mode="fresh",
        last_run_at=datetime(2026, 5, 7, 9, 0, 0, tzinfo=timezone.utc),
        last_run_status="success",
    )
    d = job.to_api_dict()
    assert d["last_run_at"] is not None
    assert d["last_run_status"] == "success"
