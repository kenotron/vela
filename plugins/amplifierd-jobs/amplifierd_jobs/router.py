from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone
from typing import Literal

from croniter import croniter
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, field_validator

from .models import Job
from .scheduler import JobScheduler, _parse_duration
from .store import JobStore

logger = logging.getLogger(__name__)


class CreateJobRequest(BaseModel):
    name: str
    trigger_type: Literal["loop", "cron", "once"]
    schedule: str
    prompt: str
    session_mode: Literal["fresh", "persistent"] = "fresh"
    description: str = ""
    bundle_name: str = "vela"

    @field_validator("trigger_type")
    @classmethod
    def validate_trigger_type(cls, v: str) -> str:
        if v not in ("loop", "cron", "once"):
            raise ValueError(f"trigger_type must be loop, cron, or once; got {v!r}")
        return v


def make_jobs_router(store: JobStore, scheduler: JobScheduler) -> APIRouter:
    router = APIRouter(prefix="/api/jobs", tags=["jobs"])

    def _validate_schedule(trigger_type: str, schedule: str) -> None:
        if trigger_type == "cron":
            try:
                croniter(schedule)
            except (ValueError, KeyError) as exc:
                raise HTTPException(400, f"Invalid cron expression: {exc}")
        elif trigger_type in ("loop", "once"):
            try:
                _parse_duration(schedule)
            except ValueError as exc:
                raise HTTPException(400, f"Invalid duration: {exc}")

    @router.get("")
    async def list_jobs() -> list[dict]:
        jobs = await store.list_jobs()
        return [j.to_api_dict() for j in jobs]

    @router.post("", status_code=201)
    async def create_job(req: CreateJobRequest) -> dict:
        _validate_schedule(req.trigger_type, req.schedule)
        job = Job.new(
            name=req.name,
            trigger_type=req.trigger_type,
            schedule=req.schedule,
            prompt=req.prompt,
            session_mode=req.session_mode,
            description=req.description,
            bundle_name=req.bundle_name,
        )
        await store.save_job(job)
        scheduler.add_job(job)
        return job.to_api_dict()

    @router.get("/runs")
    async def list_all_runs(limit: int = 100) -> list[dict]:
        runs = await store.list_all_runs(limit=limit)
        return [r.to_api_dict() for r in runs]

    @router.get("/{job_id}")
    async def get_job(job_id: str) -> dict:
        job = await store.get_job(job_id)
        if job is None:
            raise HTTPException(404, f"Job {job_id!r} not found")
        return job.to_api_dict()

    @router.put("/{job_id}")
    async def update_job(job_id: str, req: CreateJobRequest) -> dict:
        job = await store.get_job(job_id)
        if job is None:
            raise HTTPException(404, f"Job {job_id!r} not found")
        _validate_schedule(req.trigger_type, req.schedule)
        job.name = req.name
        job.trigger_type = req.trigger_type
        job.schedule = req.schedule
        job.prompt = req.prompt
        job.session_mode = req.session_mode
        job.description = req.description
        job.bundle_name = req.bundle_name
        job.updated_at = datetime.now(timezone.utc)
        await store.save_job(job)
        scheduler.add_job(job)
        return job.to_api_dict()

    @router.delete("/{job_id}", status_code=204)
    async def delete_job(job_id: str) -> None:
        job = await store.get_job(job_id)
        if job is None:
            raise HTTPException(404, f"Job {job_id!r} not found")
        scheduler.remove_job(job_id)
        await store.delete_job(job_id)

    @router.post("/{job_id}/trigger", status_code=202)
    async def trigger_job(job_id: str) -> dict:
        job = await store.get_job(job_id)
        if job is None:
            raise HTTPException(404, f"Job {job_id!r} not found")
        asyncio.create_task(scheduler._fire(job, source="manual"))
        return {"status": "queued", "job_id": job_id}

    @router.post("/{job_id}/enable")
    async def enable_job(job_id: str) -> dict:
        job = await store.get_job(job_id)
        if job is None:
            raise HTTPException(404, f"Job {job_id!r} not found")
        job.enabled = True
        await store.save_job(job)
        scheduler.add_job(job)
        return job.to_api_dict()

    @router.post("/{job_id}/disable")
    async def disable_job(job_id: str) -> dict:
        job = await store.get_job(job_id)
        if job is None:
            raise HTTPException(404, f"Job {job_id!r} not found")
        job.enabled = False
        await store.save_job(job)
        scheduler.remove_job(job_id)
        return job.to_api_dict()

    @router.get("/{job_id}/runs")
    async def list_job_runs(job_id: str, limit: int = 50) -> list[dict]:
        job = await store.get_job(job_id)
        if job is None:
            raise HTTPException(404, f"Job {job_id!r} not found")
        runs = await store.list_runs_for_job(job_id, limit=limit)
        return [r.to_api_dict() for r in runs]

    return router
