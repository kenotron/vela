"""Amplifier tool module — exposes job management tools to AI sessions.

Each tool follows the amplifier-core Tool protocol:
  - name:         str property
  - description:  str property
  - input_schema: dict property (JSON Schema)
  - execute():    async method returning ToolResult

mount() registers all tools via coordinator.mount("tools", tool, name=tool.name)
and returns a metadata dict (not None — required by protocol_compliance validation).
"""

from __future__ import annotations

import asyncio
import logging
import os
from typing import Any

from amplifier_core import ToolResult

logger = logging.getLogger(__name__)

__amplifier_module_type__ = "tool"

# Set by mount() — shared across all tool instances in this session.
_store = None
_scheduler = None


# ── Helper ───────────────────────────────────────────────────────────────────


def _require_store():
    assert _store is not None, "store not initialized — call mount() first"
    return _store


def _require_scheduler():
    assert _scheduler is not None, "scheduler not initialized — call mount() first"
    return _scheduler


# ── Tool classes ──────────────────────────────────────────────────────────────


class CreateJobTool:
    """Register a new background job that runs on a schedule."""

    name = "create_job"
    description = (
        "Register a new background job that runs automatically on a schedule. "
        "trigger_type options: 'loop' (every N seconds/minutes/hours, e.g. '30m'), "
        "'cron' (standard 5-field cron expression, e.g. '0 9 * * 1-5' for weekdays at 9am), "
        "'once' (run once after optional delay, e.g. '5m'). "
        "session_mode: 'fresh' creates a new session each run, "
        "'persistent' reuses the same session (context accumulates across runs)."
    )
    input_schema = {
        "type": "object",
        "properties": {
            "name": {"type": "string", "description": "Human-readable job name"},
            "trigger_type": {
                "type": "string",
                "enum": ["loop", "cron", "once"],
                "description": "When to fire: loop/cron/once",
            },
            "schedule": {
                "type": "string",
                "description": "Duration for loop/once (e.g. '30m', '2h') or cron expr (e.g. '0 9 * * 1-5')",
            },
            "prompt": {
                "type": "string",
                "description": "Prompt sent to the session when the job fires",
            },
            "session_mode": {
                "type": "string",
                "enum": ["fresh", "persistent"],
                "default": "fresh",
                "description": "fresh=new session each run, persistent=reuse same session",
            },
            "bundle_name": {
                "type": "string",
                "default": "vela",
                "description": "Which bundle to use",
            },
            "description": {
                "type": "string",
                "default": "",
                "description": "Optional human description of the job",
            },
        },
        "required": ["name", "trigger_type", "schedule", "prompt"],
    }

    async def execute(self, input_data: dict[str, Any]) -> ToolResult:
        from croniter import croniter
        from amplifierd_jobs.models import Job
        from amplifierd_jobs.scheduler import _parse_duration

        trigger_type = input_data["trigger_type"]
        schedule = input_data["schedule"]

        if trigger_type == "cron":
            try:
                croniter(schedule)
            except Exception as e:
                return ToolResult(success=False, output=f"Invalid cron expression: {e}")
        elif trigger_type in ("loop", "once"):
            try:
                _parse_duration(schedule)
            except ValueError as e:
                return ToolResult(success=False, output=f"Invalid duration: {e}")

        job = Job.new(
            name=input_data["name"],
            trigger_type=trigger_type,
            schedule=schedule,
            prompt=input_data["prompt"],
            session_mode=input_data.get("session_mode", "fresh"),
            bundle_name=input_data.get("bundle_name", "vela"),
            description=input_data.get("description", ""),
        )
        store = _require_store()
        scheduler = _require_scheduler()
        await store.save_job(job)
        scheduler.add_job(job)
        return ToolResult(success=True, output=str(job.to_api_dict()))


class ListJobsTool:
    """List all registered background jobs."""

    name = "list_jobs"
    description = (
        "List all registered background jobs, including their schedule, "
        "last run status, and whether they are enabled."
    )
    input_schema = {
        "type": "object",
        "properties": {},
    }

    async def execute(self, input_data: dict[str, Any]) -> ToolResult:
        store = _require_store()
        jobs = await store.list_jobs()
        result = [j.to_api_dict() for j in jobs]
        if not result:
            return ToolResult(success=True, output="No background jobs registered.")
        import json

        return ToolResult(success=True, output=json.dumps(result, indent=2))


class GetJobTool:
    """Get details for a specific background job by ID."""

    name = "get_job"
    description = "Get full details for a specific background job by its ID."
    input_schema = {
        "type": "object",
        "properties": {
            "job_id": {"type": "string", "description": "The job ID to look up"},
        },
        "required": ["job_id"],
    }

    async def execute(self, input_data: dict[str, Any]) -> ToolResult:
        import json

        store = _require_store()
        job = await store.get_job(input_data["job_id"])
        if job is None:
            return ToolResult(
                success=False, output=f"Job {input_data['job_id']!r} not found"
            )
        return ToolResult(success=True, output=json.dumps(job.to_api_dict(), indent=2))


class DeleteJobTool:
    """Delete a background job and cancel its schedule."""

    name = "delete_job"
    description = "Permanently delete a background job and cancel its schedule."
    input_schema = {
        "type": "object",
        "properties": {
            "job_id": {"type": "string", "description": "The job ID to delete"},
        },
        "required": ["job_id"],
    }

    async def execute(self, input_data: dict[str, Any]) -> ToolResult:
        job_id = input_data["job_id"]
        store = _require_store()
        scheduler = _require_scheduler()
        job = await store.get_job(job_id)
        if job is None:
            return ToolResult(success=False, output=f"Job {job_id!r} not found")
        scheduler.remove_job(job_id)
        await store.delete_job(job_id)
        return ToolResult(success=True, output=f"Job {job_id!r} deleted.")


class TriggerJobTool:
    """Manually fire a background job right now."""

    name = "trigger_job"
    description = (
        "Manually fire a background job immediately, regardless of its schedule."
    )
    input_schema = {
        "type": "object",
        "properties": {
            "job_id": {"type": "string", "description": "The job ID to fire"},
        },
        "required": ["job_id"],
    }

    async def execute(self, input_data: dict[str, Any]) -> ToolResult:
        job_id = input_data["job_id"]
        store = _require_store()
        scheduler = _require_scheduler()
        job = await store.get_job(job_id)
        if job is None:
            return ToolResult(success=False, output=f"Job {job_id!r} not found")
        asyncio.create_task(scheduler._fire(job, source="manual"))
        return ToolResult(
            success=True, output=f"Job {job_id!r} queued for immediate execution."
        )


class DisableJobTool:
    """Pause a background job without deleting it."""

    name = "disable_job"
    description = "Pause a background job. It will stop firing on its schedule but is not deleted."
    input_schema = {
        "type": "object",
        "properties": {
            "job_id": {"type": "string", "description": "The job ID to disable"},
        },
        "required": ["job_id"],
    }

    async def execute(self, input_data: dict[str, Any]) -> ToolResult:
        job_id = input_data["job_id"]
        store = _require_store()
        scheduler = _require_scheduler()
        job = await store.get_job(job_id)
        if job is None:
            return ToolResult(success=False, output=f"Job {job_id!r} not found")
        job.enabled = False
        await store.save_job(job)
        scheduler.remove_job(job_id)
        return ToolResult(success=True, output=f"Job {job.name!r} paused.")


class EnableJobTool:
    """Re-enable a paused background job."""

    name = "enable_job"
    description = (
        "Re-enable a paused background job so it resumes firing on its schedule."
    )
    input_schema = {
        "type": "object",
        "properties": {
            "job_id": {"type": "string", "description": "The job ID to enable"},
        },
        "required": ["job_id"],
    }

    async def execute(self, input_data: dict[str, Any]) -> ToolResult:
        job_id = input_data["job_id"]
        store = _require_store()
        scheduler = _require_scheduler()
        job = await store.get_job(job_id)
        if job is None:
            return ToolResult(success=False, output=f"Job {job_id!r} not found")
        job.enabled = True
        await store.save_job(job)
        scheduler.add_job(job)
        return ToolResult(success=True, output=f"Job {job.name!r} re-enabled.")


# ── mount() ───────────────────────────────────────────────────────────────────

_TOOLS = [
    CreateJobTool,
    ListJobsTool,
    GetJobTool,
    DeleteJobTool,
    TriggerJobTool,
    DisableJobTool,
    EnableJobTool,
]


async def mount(coordinator: Any, config: dict | None = None) -> dict[str, Any]:
    """Amplifier tool module entrypoint — called when loaded into a session.

    Initialises the job store and scheduler, then registers all 7 job tools
    with the coordinator.  Returns a metadata dict (required by protocol_compliance
    validation — must NOT return None).
    """
    global _store, _scheduler
    config = config or {}

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

    tools = [cls() for cls in _TOOLS]
    for tool in tools:
        await coordinator.mount("tools", tool, name=tool.name)

    names = [t.name for t in tools]
    logger.info("tool-jobs: mounted %d tools: %s", len(tools), names)
    return {
        "name": "tool-jobs",
        "version": "0.1.0",
        "provides": names,
    }
