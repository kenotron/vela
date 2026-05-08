import pytest
from amplifierd_jobs.models import Job, JobRun
from amplifierd_jobs.store import JobStore


@pytest.mark.asyncio
async def test_save_and_get_job(store: JobStore):
    job = Job.new(
        name="test",
        trigger_type="loop",
        schedule="5m",
        prompt="hello",
        session_mode="fresh",
    )
    await store.save_job(job)
    fetched = await store.get_job(job.id)
    assert fetched is not None
    assert fetched.id == job.id
    assert fetched.name == "test"


@pytest.mark.asyncio
async def test_list_jobs(store: JobStore):
    job1 = Job.new(
        name="a", trigger_type="loop", schedule="5m", prompt="p1", session_mode="fresh"
    )
    job2 = Job.new(
        name="b",
        trigger_type="cron",
        schedule="0 9 * * *",
        prompt="p2",
        session_mode="fresh",
    )
    await store.save_job(job1)
    await store.save_job(job2)
    jobs = await store.list_jobs()
    assert len(jobs) == 2
    names = {j.name for j in jobs}
    assert names == {"a", "b"}


@pytest.mark.asyncio
async def test_list_enabled_jobs(store: JobStore):
    job1 = Job.new(
        name="enabled",
        trigger_type="loop",
        schedule="5m",
        prompt="p",
        session_mode="fresh",
        enabled=True,
    )
    job2 = Job.new(
        name="disabled",
        trigger_type="loop",
        schedule="5m",
        prompt="p",
        session_mode="fresh",
        enabled=False,
    )
    await store.save_job(job1)
    await store.save_job(job2)
    enabled = await store.list_enabled_jobs()
    assert len(enabled) == 1
    assert enabled[0].name == "enabled"


@pytest.mark.asyncio
async def test_delete_job(store: JobStore):
    job = Job.new(
        name="to-delete",
        trigger_type="loop",
        schedule="5m",
        prompt="p",
        session_mode="fresh",
    )
    await store.save_job(job)
    await store.delete_job(job.id)
    assert await store.get_job(job.id) is None


@pytest.mark.asyncio
async def test_save_and_get_run(store: JobStore):
    job = Job.new(
        name="test",
        trigger_type="loop",
        schedule="5m",
        prompt="p",
        session_mode="fresh",
    )
    await store.save_job(job)
    run = JobRun.new(
        job_id=job.id,
        job_name=job.name,
        session_id="sess-123",
        status="running",
        source="scheduled",
    )
    await store.save_run(run)
    fetched = await store.get_run(run.id)
    assert fetched is not None
    assert fetched.session_id == "sess-123"


@pytest.mark.asyncio
async def test_list_runs_for_job(store: JobStore):
    job = Job.new(
        name="test",
        trigger_type="loop",
        schedule="5m",
        prompt="p",
        session_mode="fresh",
    )
    await store.save_job(job)
    for i in range(3):
        run = JobRun.new(
            job_id=job.id,
            job_name=job.name,
            session_id=f"sess-{i}",
            status="success",
            source="scheduled",
        )
        await store.save_run(run)
    runs = await store.list_runs_for_job(job.id)
    assert len(runs) == 3


@pytest.mark.asyncio
async def test_delete_job_cascades_runs(store: JobStore):
    job = Job.new(
        name="test",
        trigger_type="loop",
        schedule="5m",
        prompt="p",
        session_mode="fresh",
    )
    await store.save_job(job)
    run = JobRun.new(
        job_id=job.id,
        job_name=job.name,
        session_id="sess-x",
        status="success",
        source="manual",
    )
    await store.save_run(run)
    await store.delete_job(job.id)
    runs = await store.list_runs_for_job(job.id)
    assert runs == []
