from __future__ import annotations

import json
import aiosqlite
from pathlib import Path
from typing import Optional

from .models import Job, JobRun

CREATE_JOBS_TABLE = """
CREATE TABLE IF NOT EXISTS jobs (
    id         TEXT PRIMARY KEY,
    data       TEXT NOT NULL,
    updated_at TEXT NOT NULL,
    enabled    INTEGER NOT NULL DEFAULT 1
)
"""

CREATE_RUNS_TABLE = """
CREATE TABLE IF NOT EXISTS job_runs (
    id         TEXT PRIMARY KEY,
    job_id     TEXT NOT NULL,
    data       TEXT NOT NULL,
    started_at TEXT NOT NULL,
    FOREIGN KEY (job_id) REFERENCES jobs(id) ON DELETE CASCADE
)
"""

CREATE_RUN_IDX = """
CREATE INDEX IF NOT EXISTS idx_runs_job_id
ON job_runs(job_id, started_at DESC)
"""


class JobStore:
    def __init__(self, db_path: str):
        self._db_path = db_path

    async def init(self) -> None:
        Path(self._db_path).parent.mkdir(parents=True, exist_ok=True)
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute("PRAGMA foreign_keys = ON")
            await db.execute(CREATE_JOBS_TABLE)
            await db.execute(CREATE_RUNS_TABLE)
            await db.execute(CREATE_RUN_IDX)
            await db.commit()

    async def save_job(self, job: Job) -> None:
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute("PRAGMA foreign_keys = ON")
            await db.execute(
                "INSERT OR REPLACE INTO jobs(id, data, updated_at, enabled) VALUES (?,?,?,?)",
                (
                    job.id,
                    job.to_json(),
                    job.updated_at.isoformat() if job.updated_at else "",
                    1 if job.enabled else 0,
                ),
            )
            await db.commit()

    async def get_job(self, job_id: str) -> Optional[Job]:
        async with aiosqlite.connect(self._db_path) as db:
            async with db.execute(
                "SELECT data FROM jobs WHERE id = ?", (job_id,)
            ) as cur:
                row = await cur.fetchone()
                return Job.from_dict(json.loads(row[0])) if row else None

    async def list_jobs(self) -> list[Job]:
        async with aiosqlite.connect(self._db_path) as db:
            async with db.execute(
                "SELECT data FROM jobs ORDER BY updated_at DESC"
            ) as cur:
                return [Job.from_dict(json.loads(r[0])) async for r in cur]

    async def list_enabled_jobs(self) -> list[Job]:
        async with aiosqlite.connect(self._db_path) as db:
            async with db.execute(
                "SELECT data FROM jobs WHERE enabled = 1 ORDER BY updated_at DESC"
            ) as cur:
                return [Job.from_dict(json.loads(r[0])) async for r in cur]

    async def delete_job(self, job_id: str) -> None:
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute("PRAGMA foreign_keys = ON")
            await db.execute("DELETE FROM jobs WHERE id = ?", (job_id,))
            await db.commit()

    async def save_run(self, run: JobRun) -> None:
        async with aiosqlite.connect(self._db_path) as db:
            await db.execute(
                "INSERT OR REPLACE INTO job_runs(id, job_id, data, started_at) VALUES (?,?,?,?)",
                (run.id, run.job_id, run.to_json(), run.started_at.isoformat()),
            )
            await db.commit()

    async def get_run(self, run_id: str) -> Optional[JobRun]:
        async with aiosqlite.connect(self._db_path) as db:
            async with db.execute(
                "SELECT data FROM job_runs WHERE id = ?", (run_id,)
            ) as cur:
                row = await cur.fetchone()
                return JobRun.from_dict(json.loads(row[0])) if row else None

    async def list_runs_for_job(self, job_id: str, limit: int = 50) -> list[JobRun]:
        async with aiosqlite.connect(self._db_path) as db:
            async with db.execute(
                "SELECT data FROM job_runs WHERE job_id = ? "
                "ORDER BY started_at DESC LIMIT ?",
                (job_id, limit),
            ) as cur:
                return [JobRun.from_dict(json.loads(r[0])) async for r in cur]

    async def list_all_runs(self, limit: int = 100) -> list[JobRun]:
        async with aiosqlite.connect(self._db_path) as db:
            async with db.execute(
                "SELECT data FROM job_runs ORDER BY started_at DESC LIMIT ?",
                (limit,),
            ) as cur:
                return [JobRun.from_dict(json.loads(r[0])) async for r in cur]
