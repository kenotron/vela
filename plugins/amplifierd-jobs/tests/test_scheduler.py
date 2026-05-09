import asyncio
import pytest
from unittest.mock import AsyncMock
from amplifierd_jobs.models import Job
from amplifierd_jobs.scheduler import JobScheduler


def make_job(trigger_type="loop", schedule="100ms", session_mode="fresh", **kw):
    return Job.new(
        name="test",
        trigger_type=trigger_type,
        schedule=schedule,
        prompt="do something",
        session_mode=session_mode,
        **kw,
    )


@pytest.fixture
def mock_store():
    store = AsyncMock()
    store.save_job = AsyncMock()
    store.save_run = AsyncMock()
    return store


@pytest.fixture
def mock_client():
    client = AsyncMock()
    client.create_session = AsyncMock(return_value="sess-new")
    client.execute_prompt = AsyncMock()
    client.wait_for_completion = AsyncMock(return_value="success")
    return client


@pytest.mark.asyncio
async def test_loop_trigger_fires_multiple_times(mock_store, mock_client):
    scheduler = JobScheduler(store=mock_store, client=mock_client, max_parallel=4)
    job = make_job(trigger_type="loop", schedule="0.05s")
    task = asyncio.create_task(scheduler._run_loop(job, 0.05))
    await asyncio.sleep(0.18)
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass
    assert mock_client.create_session.call_count >= 2


@pytest.mark.asyncio
async def test_once_trigger_fires_once_and_disables(mock_store, mock_client):
    scheduler = JobScheduler(store=mock_store, client=mock_client, max_parallel=4)
    job = make_job(trigger_type="once", schedule="0s")
    await scheduler._run_once(job, 0.0)
    assert mock_client.create_session.call_count == 1
    mock_store.save_job.assert_called()
    saved_job = mock_store.save_job.call_args[0][0]
    assert saved_job.enabled is False


@pytest.mark.asyncio
async def test_fresh_mode_creates_new_session_each_time(mock_store, mock_client):
    scheduler = JobScheduler(store=mock_store, client=mock_client, max_parallel=4)
    job = make_job(session_mode="fresh")
    await scheduler._fire(job)
    await scheduler._fire(job)
    assert mock_client.create_session.call_count == 2


@pytest.mark.asyncio
async def test_persistent_mode_reuses_session(mock_store, mock_client):
    scheduler = JobScheduler(store=mock_store, client=mock_client, max_parallel=4)
    job = make_job(session_mode="persistent")
    assert job.persistent_session_id is None
    await scheduler._fire(job)
    first_session_id = job.persistent_session_id
    assert first_session_id == "sess-new"
    mock_client.create_session.reset_mock()
    await scheduler._fire(job)
    mock_client.create_session.assert_not_called()


@pytest.mark.asyncio
async def test_semaphore_limits_concurrency(mock_store, mock_client):
    """At most max_parallel jobs run simultaneously."""
    running = 0
    max_seen = 0

    async def slow_complete(*a, **kw):
        nonlocal running, max_seen
        running += 1
        max_seen = max(max_seen, running)
        await asyncio.sleep(0.05)
        running -= 1
        return "success"

    mock_client.wait_for_completion = slow_complete
    scheduler = JobScheduler(store=mock_store, client=mock_client, max_parallel=2)

    jobs = [make_job() for _ in range(6)]
    await asyncio.gather(*[scheduler._fire(j) for j in jobs])
    assert max_seen <= 2


@pytest.mark.asyncio
async def test_add_and_remove_job(mock_store, mock_client):
    scheduler = JobScheduler(store=mock_store, client=mock_client, max_parallel=4)
    job = make_job(trigger_type="loop", schedule="10s")
    scheduler.add_job(job)
    assert job.id in scheduler._tasks
    scheduler.remove_job(job.id)
    assert job.id not in scheduler._tasks
