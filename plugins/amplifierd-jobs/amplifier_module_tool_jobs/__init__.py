"""Amplifier tool module — exposes job management tools to AI sessions."""

from __future__ import annotations

import logging
from typing import TYPE_CHECKING, Any, Literal

from amplifierd_jobs.models import Job

if TYPE_CHECKING:
    from amplifierd_jobs.scheduler import JobScheduler
    from amplifierd_jobs.store import JobStore

logger = logging.getLogger(__name__)

__amplifier_module_type__ = "tool"

# Populated by mount() when loaded into a session
_store: JobStore | None = None
_scheduler: JobScheduler | None = None


async def _create_job(
    name: str,
    trigger_type: Literal["loop", "cron", "once"],
    schedule: str,
    prompt: str,
    session_mode: Literal["fresh", "persistent"] = "fresh",
    bundle_name: str = "vela",
    description: str = "",
) -> dict:
    """Register a new scheduled background job."""
    from croniter import croniter
    from amplifierd_jobs.scheduler import _parse_duration

    if trigger_type == "cron":
        try:
            croniter(schedule)
        except Exception as e:
            return {"error": f"Invalid cron expression: {e}"}
    elif trigger_type in ("loop", "once"):
        try:
            _parse_duration(schedule)
        except ValueError as e:
            return {"error": f"Invalid duration: {e}"}

    assert _store is not None, "store not initialized — call mount() first"
    assert _scheduler is not None, "scheduler not initialized — call mount() first"
    job = Job.new(
        name=name,
        trigger_type=trigger_type,
        schedule=schedule,
        prompt=prompt,
        session_mode=session_mode,
        bundle_name=bundle_name,
        description=description,
    )
    await _store.save_job(job)
    _scheduler.add_job(job)
    return job.to_api_dict()


async def _list_jobs() -> list[dict]:
    """List all registered jobs."""
    assert _store is not None, "store not initialized — call mount() first"
    jobs = await _store.list_jobs()
    return [j.to_api_dict() for j in jobs]


async def _get_job(job_id: str) -> dict:
    """Get a specific job by ID."""
    assert _store is not None, "store not initialized — call mount() first"
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    return job.to_api_dict()


async def _delete_job(job_id: str) -> dict:
    """Delete a job and cancel its schedule."""
    assert _store is not None, "store not initialized — call mount() first"
    assert _scheduler is not None, "scheduler not initialized — call mount() first"
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    _scheduler.remove_job(job_id)
    await _store.delete_job(job_id)
    return {"deleted": True, "job_id": job_id}


async def _trigger_job(job_id: str) -> dict:
    """Manually fire a job right now."""
    import asyncio

    assert _store is not None, "store not initialized — call mount() first"
    assert _scheduler is not None, "scheduler not initialized — call mount() first"
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    asyncio.create_task(_scheduler._fire(job, source="manual"))
    return {"status": "queued", "job_id": job_id}


async def _disable_job(job_id: str) -> dict:
    """Pause a job without deleting it."""
    assert _store is not None, "store not initialized — call mount() first"
    assert _scheduler is not None, "scheduler not initialized — call mount() first"
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    job.enabled = False
    await _store.save_job(job)
    _scheduler.remove_job(job_id)
    return job.to_api_dict()


async def _enable_job(job_id: str) -> dict:
    """Re-enable a paused job."""
    assert _store is not None, "store not initialized — call mount() first"
    assert _scheduler is not None, "scheduler not initialized — call mount() first"
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    job.enabled = True
    await _store.save_job(job)
    _scheduler.add_job(job)
    return job.to_api_dict()


async def mount(coordinator: Any, config: dict | None = None) -> Any:
    """Amplifier tool module entrypoint — called when loaded into a session."""
    global _store, _scheduler
    config = config or {}

    import os
    from amplifierd_jobs.store import JobStore
    from amplifierd_jobs.session_client import SessionClient
    from amplifierd_jobs.scheduler import JobScheduler

    db_path = config.get("db_path") or os.path.expanduser(
        os.environ.get("VELA_JOBS_DB", "~/.amplifierd/jobs.db")
    )
    base_url = config.get("amplifierd_url") or os.environ.get(
        "AMPLIFIERD_URL", "http://127.0.0.1:8410"
    )
    token = config.get("token") or os.environ.get("VELA_AUTH_TOKEN", "")
    max_parallel = int(
        config.get("max_parallel") or os.environ.get("VELA_JOBS_MAX_PARALLEL", "4")
    )

    _store = JobStore(db_path)
    await _store.init()
    client = SessionClient(base_url=base_url, token=token)
    _scheduler = JobScheduler(store=_store, client=client, max_parallel=max_parallel)

    tool_specs = [
        {
            "name": "create_job",
            "description": "Register a new background job that runs on a schedule",
            "function": _create_job,
            "parameters": {
                "name": {"type": "string", "description": "Human-readable job name"},
                "trigger_type": {
                    "type": "string",
                    "enum": ["loop", "cron", "once"],
                    "description": "loop=every N minutes, cron=cron expression, once=run once after delay",
                },
                "schedule": {
                    "type": "string",
                    "description": "Duration for loop/once (e.g. '30m', '2h'), cron expression for cron (e.g. '0 9 * * 1-5')",
                },
                "prompt": {
                    "type": "string",
                    "description": "The prompt sent to the session when the job fires",
                },
                "session_mode": {
                    "type": "string",
                    "enum": ["fresh", "persistent"],
                    "description": "fresh=new session each run, persistent=reuse same session",
                },
                "bundle_name": {
                    "type": "string",
                    "description": "Which bundle to use (default: vela)",
                },
                "description": {
                    "type": "string",
                    "description": "Optional description",
                },
            },
            "required": ["name", "trigger_type", "schedule", "prompt"],
        },
        {
            "name": "list_jobs",
            "description": "List all registered background jobs",
            "function": _list_jobs,
            "parameters": {},
        },
        {
            "name": "get_job",
            "description": "Get a specific job by ID",
            "function": _get_job,
            "parameters": {"job_id": {"type": "string"}},
        },
        {
            "name": "delete_job",
            "description": "Delete a job and cancel its schedule",
            "function": _delete_job,
            "parameters": {"job_id": {"type": "string"}},
        },
        {
            "name": "trigger_job",
            "description": "Manually fire a job right now",
            "function": _trigger_job,
            "parameters": {"job_id": {"type": "string"}},
        },
        {
            "name": "disable_job",
            "description": "Pause a job without deleting it",
            "function": _disable_job,
            "parameters": {"job_id": {"type": "string"}},
        },
        {
            "name": "enable_job",
            "description": "Re-enable a paused job",
            "function": _enable_job,
            "parameters": {"job_id": {"type": "string"}},
        },
    ]

    for spec in tool_specs:
        fn = spec.pop("function")
        await coordinator.mount("tools", fn, **spec)

    logger.info("tool-jobs: mounted %d tools", len(tool_specs))
