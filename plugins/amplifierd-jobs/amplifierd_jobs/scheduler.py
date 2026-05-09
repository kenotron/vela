from __future__ import annotations

import asyncio
import logging
import re
from datetime import datetime, timezone

from croniter import croniter

from .models import Job, JobRun
from .session_client import SessionClient
from .store import JobStore

logger = logging.getLogger(__name__)


def _parse_duration(s: str) -> float:
    """Parse a duration string like '30m', '2h', '1h30m', '0s', '0.05s' into seconds."""
    s = s.strip().lower()
    if not s or s == "0":
        return 0.0
    total = 0.0
    for value, unit in re.findall(r"(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)", s):
        v = float(value)
        if unit == "ms":
            total += v / 1000
        elif unit == "s":
            total += v
        elif unit == "m":
            total += v * 60
        elif unit == "h":
            total += v * 3600
        elif unit == "d":
            total += v * 86400
    if total == 0.0:
        raise ValueError(f"Cannot parse duration: {s!r}")
    return total


class JobScheduler:
    def __init__(self, store: JobStore, client: SessionClient, max_parallel: int = 4):
        self._store = store
        self._client = client
        self._tasks: dict[str, asyncio.Task] = {}
        self._semaphore = asyncio.Semaphore(max_parallel)

    # ── Public API ──────────────────────────────────────────────────────────

    def add_job(self, job: Job) -> None:
        """Start the trigger task for a job. Replaces any existing task."""
        self.remove_job(job.id)
        if not job.enabled:
            return
        if job.trigger_type == "loop":
            secs = _parse_duration(job.schedule)
            task = asyncio.create_task(self._run_loop(job, secs), name=f"job-{job.id}")
        elif job.trigger_type == "cron":
            task = asyncio.create_task(
                self._run_cron(job, job.schedule), name=f"job-{job.id}"
            )
        elif job.trigger_type == "once":
            delay = _parse_duration(job.schedule) if job.schedule else 0.0
            task = asyncio.create_task(self._run_once(job, delay), name=f"job-{job.id}")
        else:
            logger.warning(
                "Unknown trigger_type %r for job %s", job.trigger_type, job.id
            )
            return
        self._tasks[job.id] = task
        task.add_done_callback(lambda t: self._tasks.pop(job.id, None))

    def remove_job(self, job_id: str) -> None:
        """Cancel and remove the trigger task for a job."""
        task = self._tasks.pop(job_id, None)
        if task and not task.done():
            task.cancel()

    async def load_and_start(self) -> None:
        """Load all enabled jobs from the store and start their triggers."""
        jobs = await self._store.list_enabled_jobs()
        for job in jobs:
            self.add_job(job)
        logger.info("JobScheduler: started %d jobs", len(jobs))

    async def shutdown(self) -> None:
        """Cancel all running trigger tasks."""
        for task in list(self._tasks.values()):
            task.cancel()
        if self._tasks:
            await asyncio.gather(*self._tasks.values(), return_exceptions=True)
        self._tasks.clear()

    # ── Trigger runners ──────────────────────────────────────────────────────

    async def _run_loop(self, job: Job, interval_secs: float) -> None:
        while job.enabled:
            await self._fire(job)
            await asyncio.sleep(interval_secs)

    async def _run_cron(self, job: Job, expr: str) -> None:
        while job.enabled:
            cron = croniter(expr, datetime.now(timezone.utc))
            next_dt = cron.get_next(datetime)
            job.next_run_at = next_dt
            await self._store.save_job(job)
            delay = (next_dt - datetime.now(timezone.utc)).total_seconds()
            await asyncio.sleep(max(0.0, delay))
            if job.enabled:
                await self._fire(job)

    async def _run_once(self, job: Job, delay_secs: float) -> None:
        if delay_secs > 0:
            await asyncio.sleep(delay_secs)
        if job.enabled:
            await self._fire(job)
        job.enabled = False
        await self._store.save_job(job)

    # ── Firing ───────────────────────────────────────────────────────────────

    async def _fire(self, job: Job, source: str = "scheduled") -> None:
        async with self._semaphore:
            await self._fire_inner(job, source)

    async def _fire_inner(self, job: Job, source: str) -> None:
        run = JobRun.new(
            job_id=job.id,
            job_name=job.name,
            session_id="",
            status="running",
            source=source,
        )
        try:
            # Resolve session
            if job.session_mode == "persistent" and job.persistent_session_id:
                session_id = job.persistent_session_id
            else:
                session_id = await self._client.create_session(job.bundle_name)
                if job.session_mode == "persistent":
                    job.persistent_session_id = session_id
                    await self._store.save_job(job)

            run.session_id = session_id
            await self._store.save_run(run)

            await self._client.execute_prompt(session_id, job.prompt)
            final_status = await self._client.wait_for_completion(session_id)
            run.status = final_status
        except Exception as exc:
            logger.error("Job %s fire failed: %s", job.id, exc)
            run.status = "failed"
        finally:
            run.ended_at = datetime.now(timezone.utc)
            job.last_run_at = run.ended_at
            job.last_run_status = run.status
            await self._store.save_run(run)
            await self._store.save_job(job)
