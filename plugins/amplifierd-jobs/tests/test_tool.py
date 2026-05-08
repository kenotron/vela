import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from amplifierd_jobs.models import Job


@pytest.mark.asyncio
async def test_create_job_tool():
    from amplifier_module_tool_jobs import _create_job

    mock_store = AsyncMock()
    mock_store.save_job = AsyncMock()
    mock_scheduler = MagicMock()
    mock_scheduler.add_job = MagicMock()

    with (
        patch("amplifier_module_tool_jobs._store", mock_store),
        patch("amplifier_module_tool_jobs._scheduler", mock_scheduler),
    ):
        result = await _create_job(
            name="test",
            trigger_type="loop",
            schedule="30m",
            prompt="do something",
            session_mode="fresh",
        )
    assert result["name"] == "test"
    assert result["trigger_type"] == "loop"
    assert "id" in result
    mock_store.save_job.assert_called_once()
    mock_scheduler.add_job.assert_called_once()


@pytest.mark.asyncio
async def test_list_jobs_tool():
    from amplifier_module_tool_jobs import _list_jobs

    job = Job.new(
        name="x",
        trigger_type="cron",
        schedule="0 9 * * *",
        prompt="p",
        session_mode="fresh",
    )
    mock_store = AsyncMock()
    mock_store.list_jobs = AsyncMock(return_value=[job])

    with patch("amplifier_module_tool_jobs._store", mock_store):
        result = await _list_jobs()
    assert len(result) == 1
    assert result[0]["name"] == "x"


@pytest.mark.asyncio
async def test_delete_job_tool():
    from amplifier_module_tool_jobs import _delete_job

    mock_store = AsyncMock()
    mock_store.get_job = AsyncMock(
        return_value=Job.new(
            name="x",
            trigger_type="loop",
            schedule="5m",
            prompt="p",
            session_mode="fresh",
        )
    )
    mock_store.delete_job = AsyncMock()
    mock_scheduler = MagicMock()
    mock_scheduler.remove_job = MagicMock()

    with (
        patch("amplifier_module_tool_jobs._store", mock_store),
        patch("amplifier_module_tool_jobs._scheduler", mock_scheduler),
    ):
        result = await _delete_job("some-id")
    assert result["deleted"] is True
