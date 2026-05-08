"""Tests for the amplifier_module_tool_jobs tool module.

Runs against amplifierd's Python env (which has amplifier_core installed).
From the repo root:
    ~/.local/share/uv/tools/amplifierd/bin/python -m pytest \
        plugins/amplifierd-jobs/tests/test_tool.py -v
"""

import pytest
from unittest.mock import AsyncMock, MagicMock


@pytest.mark.asyncio
async def test_mount_registers_all_tools():
    """mount() must call coordinator.mount() for every tool (the Iron Law)."""
    from amplifier_module_tool_jobs import mount

    coordinator = MagicMock()
    coordinator.mount = AsyncMock()

    result = await mount(coordinator, config={"db_path": "/tmp/test-mount.db"})

    # 7 tools expected
    assert coordinator.mount.call_count == 7

    # Each call must register into "tools"
    for call in coordinator.mount.call_args_list:
        assert call[0][0] == "tools"

    # mount() must return a metadata dict (not None)
    assert result is not None
    assert "name" in result
    assert "provides" in result
    assert len(result["provides"]) == 7


@pytest.mark.asyncio
async def test_tool_protocol_compliance():
    """Each tool class must expose name, description, input_schema, execute."""
    from amplifier_module_tool_jobs import _TOOLS

    for cls in _TOOLS:
        tool = cls()
        assert isinstance(tool.name, str) and tool.name, f"{cls.__name__}.name is empty"
        assert isinstance(tool.description, str) and tool.description, \
            f"{cls.__name__}.description is empty"
        assert isinstance(tool.input_schema, dict), \
            f"{cls.__name__}.input_schema is not a dict"
        assert callable(tool.execute), f"{cls.__name__}.execute is not callable"


@pytest.mark.asyncio
async def test_list_jobs_returns_empty_when_no_jobs():
    """list_jobs returns a friendly message when there are no jobs."""
    from amplifier_module_tool_jobs import ListJobsTool
    import amplifier_module_tool_jobs as mod

    mock_store = AsyncMock()
    mock_store.list_jobs = AsyncMock(return_value=[])

    original = mod._store
    mod._store = mock_store
    try:
        tool = ListJobsTool()
        result = await tool.execute({})
        assert result.success is True
        assert "No background" in result.output
    finally:
        mod._store = original


@pytest.mark.asyncio
async def test_create_job_tool_saves_and_schedules():
    """create_job saves the job and adds it to the scheduler."""
    from amplifier_module_tool_jobs import CreateJobTool
    import amplifier_module_tool_jobs as mod
    

    mock_store = AsyncMock()
    mock_store.save_job = AsyncMock()
    mock_scheduler = MagicMock()
    mock_scheduler.add_job = MagicMock()

    original_store = mod._store
    original_sched = mod._scheduler
    mod._store = mock_store
    mod._scheduler = mock_scheduler
    try:
        tool = CreateJobTool()
        result = await tool.execute({
            "name": "test job",
            "trigger_type": "loop",
            "schedule": "30m",
            "prompt": "do something",
        })
        assert result.success is True
        mock_store.save_job.assert_called_once()
        mock_scheduler.add_job.assert_called_once()
    finally:
        mod._store = original_store
        mod._scheduler = original_sched


@pytest.mark.asyncio
async def test_delete_job_not_found():
    """delete_job returns failure when job doesn't exist."""
    from amplifier_module_tool_jobs import DeleteJobTool
    import amplifier_module_tool_jobs as mod

    mock_store = AsyncMock()
    mock_store.get_job = AsyncMock(return_value=None)
    mock_scheduler = MagicMock()

    original_store = mod._store
    original_sched = mod._scheduler
    mod._store = mock_store
    mod._scheduler = mock_scheduler
    try:
        tool = DeleteJobTool()
        result = await tool.execute({"job_id": "nonexistent"})
        assert result.success is False
        assert "not found" in result.output
    finally:
        mod._store = original_store
        mod._scheduler = original_sched
