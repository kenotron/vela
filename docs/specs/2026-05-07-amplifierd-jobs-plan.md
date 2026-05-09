# amplifierd-jobs Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `plugins/amplifierd-jobs/` — a standalone amplifierd plugin that auto-triggers amplifierd sessions on a time-based schedule, with a REST API and an AI-callable tool.

**Architecture:** Two components in one package: `amplifierd_jobs` (FastAPI router + asyncio scheduler + SQLite store) mounted as an amplifierd plugin, and `amplifier_module_tool_jobs` (amplifier tool module) loaded into sessions via the vela bundle. The scheduler is a consumer of the existing session API — it hits `POST /sessions` and `POST /sessions/{id}/execute/stream` exactly as Vela does.

**Tech Stack:** Python 3.11+, FastAPI, aiosqlite, croniter, httpx (async HTTP to session API), pytest + pytest-asyncio

**Spec:** `docs/specs/2026-05-07-amplifierd-jobs-design.md`

---

## File Map

```
plugins/amplifierd-jobs/
├── pyproject.toml
├── amplifierd_jobs/
│   ├── __init__.py            — version export
│   ├── models.py              — Job, JobRun dataclasses + JSON serialization
│   ├── store.py               — SQLite persistence (aiosqlite)
│   ├── session_client.py      — async HTTP client for amplifierd session API
│   ├── scheduler.py           — asyncio scheduler (loop/cron/once) + semaphore
│   ├── router.py              — FastAPI /api/jobs routes
│   └── plugin.py              — amplifierd plugin entrypoint (create_router)
├── amplifier_module_tool_jobs/
│   └── __init__.py            — amplifier tool module (mount + tool functions)
└── tests/
    ├── conftest.py            — shared fixtures (tmp db, mock session API)
    ├── test_models.py         — Job/JobRun serialization
    ├── test_store.py          — SQLite CRUD
    ├── test_scheduler.py      — trigger timing + semaphore
    ├── test_session_client.py — session API calls (httpx mock)
    ├── test_router.py         — FastAPI routes (TestClient)
    └── test_tool.py           — AI tool functions
```

---

## Task 1: Package scaffold

**Files:**
- Create: `plugins/amplifierd-jobs/pyproject.toml`
- Create: `plugins/amplifierd-jobs/amplifierd_jobs/__init__.py`
- Create: `plugins/amplifierd-jobs/amplifier_module_tool_jobs/__init__.py`
- Create: `plugins/amplifierd-jobs/tests/__init__.py`

- [ ] **Step 1: Create pyproject.toml**

```toml
[project]
name = "amplifierd-jobs"
version = "0.1.0"
description = "Background job scheduler plugin for amplifierd"
requires-python = ">=3.11"
dependencies = [
    "fastapi>=0.100.0",
    "aiosqlite>=0.19.0",
    "croniter>=2.0.0",
    "httpx>=0.25.0",
]

[project.entry-points."amplifierd.plugins"]
jobs = "amplifierd_jobs.plugin"

[project.entry-points."amplifier.modules"]
tool-jobs = "amplifier_module_tool_jobs:mount"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["amplifierd_jobs", "amplifier_module_tool_jobs"]

[dependency-groups]
dev = ["pytest>=7.4", "pytest-asyncio>=0.23", "httpx>=0.25"]

[tool.pytest.ini_options]
asyncio_mode = "auto"
testpaths = ["tests"]
```

- [ ] **Step 2: Create package `__init__.py` files**

`plugins/amplifierd-jobs/amplifierd_jobs/__init__.py`:
```python
__version__ = "0.1.0"
```

`plugins/amplifierd-jobs/amplifier_module_tool_jobs/__init__.py`:
```python
"""Amplifier tool module — exposes job management tools to AI sessions."""
__amplifier_module_type__ = "tool"
```

`plugins/amplifierd-jobs/tests/__init__.py`:
```python
```

- [ ] **Step 3: Verify package installs**

```bash
cd plugins/amplifierd-jobs
uv pip install -e ".[dev]" --python $(which python3)
python3 -c "import amplifierd_jobs; print('ok')"
```
Expected: `ok`

- [ ] **Step 4: Commit**

```bash
git add plugins/amplifierd-jobs/
git commit -m "feat(jobs): scaffold amplifierd-jobs plugin package"
```

---

## Task 2: Models

**Files:**
- Create: `plugins/amplifierd-jobs/amplifierd_jobs/models.py`
- Create: `plugins/amplifierd-jobs/tests/test_models.py`

- [ ] **Step 1: Write failing tests**

`tests/test_models.py`:
```python
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
        run = JobRun(id="x", job_id="y", job_name="n", session_id="s",
                     status=status, source="manual")
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
```

- [ ] **Step 2: Run — verify FAIL**

```bash
cd plugins/amplifierd-jobs
python3 -m pytest tests/test_models.py -v
```
Expected: `ImportError` or `ModuleNotFoundError` — models not yet created.

- [ ] **Step 3: Implement models.py**

`amplifierd_jobs/models.py`:
```python
from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from typing import Any, Literal


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _uuid() -> str:
    return str(uuid.uuid4())


@dataclass
class Job:
    id: str
    name: str
    trigger_type: Literal["loop", "cron", "once"]
    schedule: str
    prompt: str
    session_mode: Literal["fresh", "persistent"]

    description: str = ""
    bundle_name: str = "vela"
    persistent_session_id: str | None = None
    enabled: bool = True
    created_at: datetime = field(default_factory=_now)
    updated_at: datetime = field(default_factory=_now)
    next_run_at: datetime | None = None
    last_run_at: datetime | None = None
    last_run_status: str | None = None

    @classmethod
    def new(cls, **kwargs: Any) -> "Job":
        return cls(id=_uuid(), **kwargs)

    def to_json(self) -> str:
        d = asdict(self)
        # Convert datetimes to ISO strings
        for k in ("created_at", "updated_at", "next_run_at", "last_run_at"):
            if d[k] is not None:
                d[k] = d[k].isoformat() if isinstance(d[k], datetime) else d[k]
        return json.dumps(d)

    def to_api_dict(self) -> dict:
        d = json.loads(self.to_json())
        return d

    @classmethod
    def from_dict(cls, d: dict) -> "Job":
        def _dt(v: str | None) -> datetime | None:
            if v is None:
                return None
            return datetime.fromisoformat(v)

        return cls(
            id=d["id"],
            name=d["name"],
            trigger_type=d["trigger_type"],
            schedule=d["schedule"],
            prompt=d["prompt"],
            session_mode=d["session_mode"],
            description=d.get("description", ""),
            bundle_name=d.get("bundle_name", "vela"),
            persistent_session_id=d.get("persistent_session_id"),
            enabled=d.get("enabled", True),
            created_at=_dt(d.get("created_at")) or _now(),
            updated_at=_dt(d.get("updated_at")) or _now(),
            next_run_at=_dt(d.get("next_run_at")),
            last_run_at=_dt(d.get("last_run_at")),
            last_run_status=d.get("last_run_status"),
        )


@dataclass
class JobRun:
    id: str
    job_id: str
    job_name: str
    session_id: str
    status: Literal["running", "success", "failed", "cancelled"]
    source: Literal["scheduled", "manual"]

    started_at: datetime = field(default_factory=_now)
    ended_at: datetime | None = None

    @classmethod
    def new(cls, **kwargs: Any) -> "JobRun":
        return cls(id=_uuid(), **kwargs)

    def to_json(self) -> str:
        d = asdict(self)
        for k in ("started_at", "ended_at"):
            if d[k] is not None:
                d[k] = d[k].isoformat() if isinstance(d[k], datetime) else d[k]
        return json.dumps(d)

    def to_api_dict(self) -> dict:
        return json.loads(self.to_json())

    @classmethod
    def from_dict(cls, d: dict) -> "JobRun":
        def _dt(v: str | None) -> datetime | None:
            return datetime.fromisoformat(v) if v else None

        return cls(
            id=d["id"],
            job_id=d["job_id"],
            job_name=d["job_name"],
            session_id=d["session_id"],
            status=d["status"],
            source=d["source"],
            started_at=_dt(d.get("started_at")) or _now(),
            ended_at=_dt(d.get("ended_at")),
        )
```

- [ ] **Step 4: Run — verify PASS**

```bash
python3 -m pytest tests/test_models.py -v
```
Expected: 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add amplifierd_jobs/models.py tests/test_models.py
git commit -m "feat(jobs): Job and JobRun dataclasses with JSON serialization"
```

---

## Task 3: Store (SQLite)

**Files:**
- Create: `plugins/amplifierd-jobs/amplifierd_jobs/store.py`
- Create: `plugins/amplifierd-jobs/tests/conftest.py`
- Create: `plugins/amplifierd-jobs/tests/test_store.py`

- [ ] **Step 1: Write conftest.py with shared fixture**

`tests/conftest.py`:
```python
import pytest
import pytest_asyncio
from pathlib import Path
from amplifierd_jobs.store import JobStore

@pytest_asyncio.fixture
async def store(tmp_path: Path) -> JobStore:
    db_path = tmp_path / "jobs.db"
    s = JobStore(str(db_path))
    await s.init()
    return s
```

- [ ] **Step 2: Write failing tests**

`tests/test_store.py`:
```python
import pytest
from amplifierd_jobs.models import Job, JobRun
from amplifierd_jobs.store import JobStore

@pytest.mark.asyncio
async def test_save_and_get_job(store: JobStore):
    job = Job.new(name="test", trigger_type="loop", schedule="5m",
                  prompt="hello", session_mode="fresh")
    await store.save_job(job)
    fetched = await store.get_job(job.id)
    assert fetched is not None
    assert fetched.id == job.id
    assert fetched.name == "test"

@pytest.mark.asyncio
async def test_list_jobs(store: JobStore):
    job1 = Job.new(name="a", trigger_type="loop", schedule="5m",
                   prompt="p1", session_mode="fresh")
    job2 = Job.new(name="b", trigger_type="cron", schedule="0 9 * * *",
                   prompt="p2", session_mode="fresh")
    await store.save_job(job1)
    await store.save_job(job2)
    jobs = await store.list_jobs()
    assert len(jobs) == 2
    names = {j.name for j in jobs}
    assert names == {"a", "b"}

@pytest.mark.asyncio
async def test_list_enabled_jobs(store: JobStore):
    job1 = Job.new(name="enabled", trigger_type="loop", schedule="5m",
                   prompt="p", session_mode="fresh", enabled=True)
    job2 = Job.new(name="disabled", trigger_type="loop", schedule="5m",
                   prompt="p", session_mode="fresh", enabled=False)
    await store.save_job(job1)
    await store.save_job(job2)
    enabled = await store.list_enabled_jobs()
    assert len(enabled) == 1
    assert enabled[0].name == "enabled"

@pytest.mark.asyncio
async def test_delete_job(store: JobStore):
    job = Job.new(name="to-delete", trigger_type="loop", schedule="5m",
                  prompt="p", session_mode="fresh")
    await store.save_job(job)
    await store.delete_job(job.id)
    assert await store.get_job(job.id) is None

@pytest.mark.asyncio
async def test_save_and_get_run(store: JobStore):
    job = Job.new(name="test", trigger_type="loop", schedule="5m",
                  prompt="p", session_mode="fresh")
    await store.save_job(job)
    run = JobRun.new(job_id=job.id, job_name=job.name,
                     session_id="sess-123", status="running", source="scheduled")
    await store.save_run(run)
    fetched = await store.get_run(run.id)
    assert fetched is not None
    assert fetched.session_id == "sess-123"

@pytest.mark.asyncio
async def test_list_runs_for_job(store: JobStore):
    job = Job.new(name="test", trigger_type="loop", schedule="5m",
                  prompt="p", session_mode="fresh")
    await store.save_job(job)
    for i in range(3):
        run = JobRun.new(job_id=job.id, job_name=job.name,
                         session_id=f"sess-{i}", status="success", source="scheduled")
        await store.save_run(run)
    runs = await store.list_runs_for_job(job.id)
    assert len(runs) == 3

@pytest.mark.asyncio
async def test_delete_job_cascades_runs(store: JobStore):
    job = Job.new(name="test", trigger_type="loop", schedule="5m",
                  prompt="p", session_mode="fresh")
    await store.save_job(job)
    run = JobRun.new(job_id=job.id, job_name=job.name,
                     session_id="sess-x", status="success", source="manual")
    await store.save_run(run)
    await store.delete_job(job.id)
    runs = await store.list_runs_for_job(job.id)
    assert runs == []
```

- [ ] **Step 3: Run — verify FAIL**

```bash
python3 -m pytest tests/test_store.py -v
```
Expected: `ImportError` — store not yet created.

- [ ] **Step 4: Implement store.py**

`amplifierd_jobs/store.py`:
```python
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
                (job.id, job.to_json(),
                 job.updated_at.isoformat() if job.updated_at else "",
                 1 if job.enabled else 0),
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
```

- [ ] **Step 5: Run — verify PASS**

```bash
python3 -m pytest tests/test_store.py -v
```
Expected: 7 tests pass.

- [ ] **Step 6: Commit**

```bash
git add amplifierd_jobs/store.py tests/conftest.py tests/test_store.py
git commit -m "feat(jobs): SQLite store with aiosqlite"
```

---

## Task 4: Session client

**Files:**
- Create: `plugins/amplifierd-jobs/amplifierd_jobs/session_client.py`
- Create: `plugins/amplifierd-jobs/tests/test_session_client.py`

- [ ] **Step 1: Write failing tests**

`tests/test_session_client.py`:
```python
import pytest
import httpx
from unittest.mock import AsyncMock, patch
from amplifierd_jobs.session_client import SessionClient

BASE = "http://127.0.0.1:8410"
TOKEN = "test-token"

@pytest.mark.asyncio
async def test_create_session():
    client = SessionClient(base_url=BASE, token=TOKEN)
    response_data = {"session_id": "sess-abc123", "status": "idle"}
    with patch.object(client._http, "post", new_callable=AsyncMock) as mock_post:
        mock_resp = AsyncMock()
        mock_resp.raise_for_status = AsyncMock()
        mock_resp.json = AsyncMock(return_value=response_data)
        mock_post.return_value = mock_resp
        session_id = await client.create_session("vela")
    assert session_id == "sess-abc123"
    mock_post.assert_called_once()
    call_kwargs = mock_post.call_args
    assert "vela" in str(call_kwargs)

@pytest.mark.asyncio
async def test_execute_prompt():
    client = SessionClient(base_url=BASE, token=TOKEN)
    response_data = {"correlation_id": "corr-1", "status": "accepted"}
    with patch.object(client._http, "post", new_callable=AsyncMock) as mock_post:
        mock_resp = AsyncMock()
        mock_resp.raise_for_status = AsyncMock()
        mock_resp.json = AsyncMock(return_value=response_data)
        mock_post.return_value = mock_resp
        await client.execute_prompt("sess-abc123", "hello world")
    call_args = mock_post.call_args
    assert "sess-abc123" in str(call_args)
    # body must use "prompt" field not "message"
    assert "prompt" in str(call_args)

@pytest.mark.asyncio
async def test_wait_for_completion_success(monkeypatch):
    client = SessionClient(base_url=BASE, token=TOKEN)
    # Simulate SSE stream that emits orchestrator:complete
    sse_lines = [
        b"event: orchestrator:complete\n",
        b'data: {"session_id": "sess-abc123", "data": {"orchestrator": "loop-vela"}}\n',
        b"\n",
    ]
    async def fake_stream(*args, **kwargs):
        class FakeResponse:
            async def aiter_lines(self):
                for line in sse_lines:
                    yield line.decode()
            async def __aenter__(self): return self
            async def __aexit__(self, *a): pass
        return FakeResponse()
    monkeypatch.setattr(client._http, "stream", fake_stream)
    status = await client.wait_for_completion("sess-abc123", timeout=10.0)
    assert status == "success"

@pytest.mark.asyncio
async def test_wait_for_completion_timeout(monkeypatch):
    client = SessionClient(base_url=BASE, token=TOKEN)
    import asyncio
    async def fake_stream(*args, **kwargs):
        class FakeResponse:
            async def aiter_lines(self):
                while True:
                    await asyncio.sleep(0.01)
                    yield ""
            async def __aenter__(self): return self
            async def __aexit__(self, *a): pass
        return FakeResponse()
    monkeypatch.setattr(client._http, "stream", fake_stream)
    status = await client.wait_for_completion("sess-abc123", timeout=0.05)
    assert status == "failed"
```

- [ ] **Step 2: Run — verify FAIL**

```bash
python3 -m pytest tests/test_session_client.py -v
```
Expected: `ImportError` — session_client not yet created.

- [ ] **Step 3: Implement session_client.py**

`amplifierd_jobs/session_client.py`:
```python
from __future__ import annotations

import asyncio
import json
import logging
from typing import Literal

import httpx

logger = logging.getLogger(__name__)

RunStatus = Literal["success", "failed", "cancelled"]


class SessionClient:
    """Async HTTP client for the amplifierd session API."""

    def __init__(self, base_url: str, token: str, timeout: float = 30.0):
        self._base = base_url.rstrip("/")
        self._token = token
        self._http = httpx.AsyncClient(
            headers={"x-amplifier-token": token},
            timeout=timeout,
        )

    async def create_session(self, bundle_name: str) -> str:
        """Create a new amplifierd session. Returns session_id."""
        resp = await self._http.post(
            f"{self._base}/sessions",
            json={"bundle_name": bundle_name},
        )
        resp.raise_for_status()
        return resp.json()["session_id"]

    async def execute_prompt(self, session_id: str, prompt: str) -> None:
        """Submit a prompt to an existing session for execution."""
        resp = await self._http.post(
            f"{self._base}/sessions/{session_id}/execute/stream",
            json={"prompt": prompt},
        )
        resp.raise_for_status()

    async def wait_for_completion(
        self, session_id: str, timeout: float = 1800.0
    ) -> RunStatus:
        """Subscribe to the session's SSE stream and wait for orchestrator:complete.

        Returns "success" on orchestrator:complete, "failed" on timeout or error.
        """
        url = f"{self._base}/events?session={session_id}"
        try:
            async with asyncio.timeout(timeout):
                async with self._http.stream("GET", url) as resp:
                    current_event: str | None = None
                    async for line in resp.aiter_lines():
                        line = line.strip()
                        if line.startswith("event:"):
                            current_event = line[len("event:"):].strip()
                        elif line.startswith("data:") and current_event:
                            if current_event == "orchestrator:complete":
                                return "success"
                            elif current_event in ("execution:end",):
                                try:
                                    data = json.loads(line[len("data:"):].strip())
                                    inner = data.get("data", {})
                                    if inner.get("status") == "error":
                                        return "failed"
                                except Exception:
                                    pass
                            current_event = None
        except asyncio.TimeoutError:
            logger.warning("wait_for_completion timed out for session %s", session_id)
            return "failed"
        except Exception as exc:
            logger.error("wait_for_completion error for session %s: %s", session_id, exc)
            return "failed"
        return "success"

    async def aclose(self) -> None:
        await self._http.aclose()
```

- [ ] **Step 4: Run — verify PASS**

```bash
python3 -m pytest tests/test_session_client.py -v
```
Expected: 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add amplifierd_jobs/session_client.py tests/test_session_client.py
git commit -m "feat(jobs): async session client for amplifierd API"
```

---

## Task 5: Scheduler

**Files:**
- Create: `plugins/amplifierd-jobs/amplifierd_jobs/scheduler.py`
- Create: `plugins/amplifierd-jobs/tests/test_scheduler.py`

- [ ] **Step 1: Write failing tests**

`tests/test_scheduler.py`:
```python
import asyncio
import pytest
from unittest.mock import AsyncMock, MagicMock, patch
from datetime import datetime, timezone
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
    # job should be disabled after firing
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
    # Second fire: session already set, should NOT create new
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
```

- [ ] **Step 2: Run — verify FAIL**

```bash
python3 -m pytest tests/test_scheduler.py -v
```
Expected: `ImportError`.

- [ ] **Step 3: Implement scheduler.py**

`amplifierd_jobs/scheduler.py`:
```python
from __future__ import annotations

import asyncio
import logging
from datetime import datetime, timezone, timedelta

from croniter import croniter

from .models import Job, JobRun
from .session_client import SessionClient
from .store import JobStore

logger = logging.getLogger(__name__)


def _parse_duration(s: str) -> float:
    """Parse a duration string like '30m', '2h', '1h30m', '0s' into seconds."""
    s = s.strip().lower()
    if not s or s == "0":
        return 0.0
    total = 0.0
    import re
    for value, unit in re.findall(r"(\d+(?:\.\d+)?)\s*(ms|s|m|h|d)", s):
        v = float(value)
        if unit == "ms":   total += v / 1000
        elif unit == "s":  total += v
        elif unit == "m":  total += v * 60
        elif unit == "h":  total += v * 3600
        elif unit == "d":  total += v * 86400
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
            task = asyncio.create_task(self._run_loop(job, secs),
                                       name=f"job-{job.id}")
        elif job.trigger_type == "cron":
            task = asyncio.create_task(self._run_cron(job, job.schedule),
                                       name=f"job-{job.id}")
        elif job.trigger_type == "once":
            delay = _parse_duration(job.schedule) if job.schedule else 0.0
            task = asyncio.create_task(self._run_once(job, delay),
                                       name=f"job-{job.id}")
        else:
            logger.warning("Unknown trigger_type %r for job %s", job.trigger_type, job.id)
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

    # ── Trigger runners ─────────────────────────────────────────────────────

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
        from datetime import datetime, timezone
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
```

- [ ] **Step 4: Run — verify PASS**

```bash
python3 -m pytest tests/test_scheduler.py -v
```
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add amplifierd_jobs/scheduler.py tests/test_scheduler.py
git commit -m "feat(jobs): asyncio scheduler with loop/cron/once and semaphore"
```

---

## Task 6: REST router

**Files:**
- Create: `plugins/amplifierd-jobs/amplifierd_jobs/router.py`
- Create: `plugins/amplifierd-jobs/tests/test_router.py`

- [ ] **Step 1: Write failing tests**

`tests/test_router.py`:
```python
import pytest
import json
from fastapi import FastAPI
from fastapi.testclient import TestClient
from unittest.mock import AsyncMock, MagicMock
from amplifierd_jobs.router import make_jobs_router
from amplifierd_jobs.store import JobStore
from amplifierd_jobs.scheduler import JobScheduler

@pytest.fixture
def client(tmp_path):
    import asyncio
    store = MagicMock(spec=JobStore)
    scheduler = MagicMock(spec=JobScheduler)

    async def _list_jobs():
        return []
    async def _save_job(j): pass
    async def _get_job(jid): return None
    async def _delete_job(jid): pass
    async def _list_runs_for_job(jid, limit=50): return []
    async def _list_all_runs(limit=100): return []

    store.list_jobs = _list_jobs
    store.save_job = _save_job
    store.get_job = _get_job
    store.delete_job = _delete_job
    store.list_runs_for_job = _list_runs_for_job
    store.list_all_runs = _list_all_runs
    scheduler.add_job = MagicMock()
    scheduler.remove_job = MagicMock()
    scheduler._fire = AsyncMock()

    app = FastAPI()
    app.include_router(make_jobs_router(store=store, scheduler=scheduler))
    return TestClient(app)

def test_list_jobs_empty(client):
    resp = client.get("/api/jobs")
    assert resp.status_code == 200
    assert resp.json() == []

def test_create_job(client):
    resp = client.post("/api/jobs", json={
        "name": "test job",
        "trigger_type": "loop",
        "schedule": "30m",
        "prompt": "do something",
        "session_mode": "fresh",
    })
    assert resp.status_code == 201
    data = resp.json()
    assert data["name"] == "test job"
    assert data["trigger_type"] == "loop"
    assert "id" in data

def test_create_job_invalid_trigger(client):
    resp = client.post("/api/jobs", json={
        "name": "bad",
        "trigger_type": "watch",
        "schedule": "path",
        "prompt": "p",
        "session_mode": "fresh",
    })
    assert resp.status_code == 422

def test_create_job_invalid_cron(client):
    resp = client.post("/api/jobs", json={
        "name": "bad cron",
        "trigger_type": "cron",
        "schedule": "not-a-valid-cron",
        "prompt": "p",
        "session_mode": "fresh",
    })
    assert resp.status_code == 400

def test_get_job_not_found(client):
    resp = client.get("/api/jobs/nonexistent")
    assert resp.status_code == 404

def test_trigger_job_not_found(client):
    resp = client.post("/api/jobs/nonexistent/trigger")
    assert resp.status_code == 404
```

- [ ] **Step 2: Run — verify FAIL**

```bash
python3 -m pytest tests/test_router.py -v
```
Expected: `ImportError`.

- [ ] **Step 3: Implement router.py**

`amplifierd_jobs/router.py`:
```python
from __future__ import annotations

import logging
from typing import Any, Literal

from croniter import croniter
from fastapi import APIRouter, HTTPException
from pydantic import BaseModel, field_validator

from .models import Job, JobRun
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
        from datetime import datetime, timezone
        job.updated_at = datetime.now(timezone.utc)
        await store.save_job(job)
        scheduler.add_job(job)  # hot-reload: cancels old task, starts new one
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
        import asyncio
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
```

- [ ] **Step 4: Run — verify PASS**

```bash
python3 -m pytest tests/test_router.py -v
```
Expected: 6 tests pass.

- [ ] **Step 5: Commit**

```bash
git add amplifierd_jobs/router.py tests/test_router.py
git commit -m "feat(jobs): FastAPI /api/jobs router with full CRUD"
```

---

## Task 7: Plugin entrypoint + AI tool module

**Files:**
- Create: `plugins/amplifierd-jobs/amplifierd_jobs/plugin.py`
- Modify: `plugins/amplifierd-jobs/amplifier_module_tool_jobs/__init__.py`
- Create: `plugins/amplifierd-jobs/tests/test_tool.py`

- [ ] **Step 1: Implement plugin.py**

`amplifierd_jobs/plugin.py`:
```python
"""amplifierd plugin entrypoint.

Registers the /api/jobs router and starts the background scheduler
when amplifierd loads this plugin.
"""
from __future__ import annotations

import logging
import os
from typing import Any

from fastapi import APIRouter

from .session_client import SessionClient
from .scheduler import JobScheduler
from .store import JobStore

logger = logging.getLogger(__name__)

_store: JobStore | None = None
_scheduler: JobScheduler | None = None


def create_router(state: Any) -> APIRouter:
    """amplifierd plugin entrypoint — called when the plugin is loaded."""
    global _store, _scheduler

    db_path = os.path.expanduser(
        os.environ.get("VELA_JOBS_DB", "~/.amplifierd/jobs.db")
    )
    base_url = os.environ.get("AMPLIFIERD_URL", "http://127.0.0.1:8410")
    token = os.environ.get("VELA_AUTH_TOKEN", "")
    max_parallel = int(os.environ.get("VELA_JOBS_MAX_PARALLEL", "4"))

    _store = JobStore(db_path)
    client = SessionClient(base_url=base_url, token=token)
    _scheduler = JobScheduler(store=_store, client=client, max_parallel=max_parallel)

    # Hook into FastAPI lifespan to init DB and start scheduler
    from fastapi import FastAPI
    app = getattr(state, "app", None)
    if app is not None:
        original_startup = app.router.on_startup[:]

        @app.on_event("startup")
        async def _jobs_startup():
            await _store.init()
            await _scheduler.load_and_start()
            logger.info("amplifierd-jobs: scheduler started")

        @app.on_event("shutdown")
        async def _jobs_shutdown():
            await _scheduler.shutdown()
            logger.info("amplifierd-jobs: scheduler stopped")

    from .router import make_jobs_router
    return make_jobs_router(store=_store, scheduler=_scheduler)


def get_store() -> JobStore | None:
    return _store
```

- [ ] **Step 2: Write failing tool tests**

`tests/test_tool.py`:
```python
import pytest
from unittest.mock import AsyncMock, patch, MagicMock
from amplifierd_jobs.models import Job, JobRun

@pytest.mark.asyncio
async def test_create_job_tool():
    from amplifier_module_tool_jobs import _create_job
    mock_store = AsyncMock()
    mock_store.save_job = AsyncMock()
    mock_scheduler = MagicMock()
    mock_scheduler.add_job = MagicMock()

    with patch("amplifier_module_tool_jobs._store", mock_store), \
         patch("amplifier_module_tool_jobs._scheduler", mock_scheduler):
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
    job = Job.new(name="x", trigger_type="cron", schedule="0 9 * * *",
                  prompt="p", session_mode="fresh")
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
    mock_store.get_job = AsyncMock(return_value=Job.new(
        name="x", trigger_type="loop", schedule="5m",
        prompt="p", session_mode="fresh"))
    mock_store.delete_job = AsyncMock()
    mock_scheduler = MagicMock()
    mock_scheduler.remove_job = MagicMock()

    with patch("amplifier_module_tool_jobs._store", mock_store), \
         patch("amplifier_module_tool_jobs._scheduler", mock_scheduler):
        result = await _delete_job("some-id")
    assert result["deleted"] is True
```

- [ ] **Step 3: Run — verify FAIL**

```bash
python3 -m pytest tests/test_tool.py -v
```
Expected: `ImportError` — tool functions not yet created.

- [ ] **Step 4: Implement tool module**

`amplifier_module_tool_jobs/__init__.py`:
```python
"""Amplifier tool module — exposes job management tools to AI sessions."""
from __future__ import annotations

import logging
from typing import Any, Literal

from amplifierd_jobs.models import Job

logger = logging.getLogger(__name__)

__amplifier_module_type__ = "tool"

# These are populated by mount() when the module is loaded into a session
_store = None
_scheduler = None


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
    jobs = await _store.list_jobs()
    return [j.to_api_dict() for j in jobs]


async def _get_job(job_id: str) -> dict:
    """Get a specific job by ID."""
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    return job.to_api_dict()


async def _delete_job(job_id: str) -> dict:
    """Delete a job and cancel its schedule."""
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    _scheduler.remove_job(job_id)
    await _store.delete_job(job_id)
    return {"deleted": True, "job_id": job_id}


async def _trigger_job(job_id: str) -> dict:
    """Manually fire a job right now."""
    import asyncio
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    asyncio.create_task(_scheduler._fire(job, source="manual"))
    return {"status": "queued", "job_id": job_id}


async def _disable_job(job_id: str) -> dict:
    """Pause a job without deleting it."""
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    job.enabled = False
    await _store.save_job(job)
    _scheduler.remove_job(job_id)
    return job.to_api_dict()


async def _enable_job(job_id: str) -> dict:
    """Re-enable a paused job."""
    job = await _store.get_job(job_id)
    if job is None:
        return {"error": f"Job {job_id!r} not found"}
    job.enabled = True
    await _store.save_job(job)
    _scheduler.add_job(job)
    return job.to_api_dict()


async def mount(coordinator: Any, config: dict | None = None) -> Any:
    """Amplifier tool module entrypoint."""
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
    max_parallel = int(config.get("max_parallel") or
                       os.environ.get("VELA_JOBS_MAX_PARALLEL", "4"))

    _store = JobStore(db_path)
    await _store.init()
    client = SessionClient(base_url=base_url, token=token)
    _scheduler = JobScheduler(store=_store, client=client, max_parallel=max_parallel)

    # Register tools with coordinator
    tool_specs = [
        {
            "name": "create_job",
            "description": "Register a new background job that runs on a schedule",
            "function": _create_job,
            "parameters": {
                "name": {"type": "string", "description": "Human-readable job name"},
                "trigger_type": {"type": "string", "enum": ["loop", "cron", "once"],
                                 "description": "loop=every N minutes, cron=cron expression, once=run once after delay"},
                "schedule": {"type": "string",
                             "description": "Duration for loop/once (e.g. '30m', '2h'), cron expression for cron (e.g. '0 9 * * 1-5')"},
                "prompt": {"type": "string", "description": "The prompt sent to the session when the job fires"},
                "session_mode": {"type": "string", "enum": ["fresh", "persistent"],
                                 "description": "fresh=new session each run, persistent=reuse same session"},
                "bundle_name": {"type": "string", "description": "Which bundle to use (default: vela)"},
                "description": {"type": "string", "description": "Optional description"},
            },
            "required": ["name", "trigger_type", "schedule", "prompt"],
        },
        {"name": "list_jobs", "description": "List all registered background jobs",
         "function": _list_jobs, "parameters": {}},
        {"name": "get_job", "description": "Get a specific job by ID",
         "function": _get_job, "parameters": {"job_id": {"type": "string"}}},
        {"name": "delete_job", "description": "Delete a job and cancel its schedule",
         "function": _delete_job, "parameters": {"job_id": {"type": "string"}}},
        {"name": "trigger_job", "description": "Manually fire a job right now",
         "function": _trigger_job, "parameters": {"job_id": {"type": "string"}}},
        {"name": "disable_job", "description": "Pause a job without deleting it",
         "function": _disable_job, "parameters": {"job_id": {"type": "string"}}},
        {"name": "enable_job", "description": "Re-enable a paused job",
         "function": _enable_job, "parameters": {"job_id": {"type": "string"}}},
    ]

    for spec in tool_specs:
        fn = spec.pop("function")
        await coordinator.mount("tools", fn, **spec)

    logger.info("tool-jobs: mounted %d tools", len(tool_specs))
```

- [ ] **Step 5: Run all tests — verify PASS**

```bash
python3 -m pytest tests/ -v
```
Expected: all tests pass.

- [ ] **Step 6: Commit**

```bash
git add amplifierd_jobs/plugin.py amplifier_module_tool_jobs/__init__.py tests/test_tool.py
git commit -m "feat(jobs): plugin entrypoint and AI tool module"
```

---

## Task 8: Install, wire, and smoke test

**Files:**
- Modify: `~/.amplifier/bundles/vela.md` — add tool-jobs module
- Modify: `~/.amplifierd/start-amplifierd.sh` — register jobs plugin
- Modify: `plugins/amplifierd-jobs/` (no new files — this is wiring + verification)

- [ ] **Step 1: Install into amplifierd env**

```bash
cd /Users/ken/workspace/vela
uv pip install -e plugins/amplifierd-jobs \
  --python ~/.local/share/uv/tools/amplifierd/bin/python
```

Expected output: `Successfully installed amplifierd-jobs-0.1.0`

Verify:
```bash
~/.local/share/uv/tools/amplifierd/bin/python \
  -c "import amplifierd_jobs; import amplifier_module_tool_jobs; print('ok')"
```
Expected: `ok`

- [ ] **Step 2: Register jobs plugin with amplifierd**

Add to `~/.amplifierd/start-amplifierd.sh` (after the vela bundle registration):

```bash
# Register jobs plugin
for i in $(seq 1 20); do
  if curl -sf http://127.0.0.1:8410/health >/dev/null 2>&1; then
    curl -sf -H "x-amplifier-token: $TOKEN" -H "Content-Type: application/json" \
      -d '{"name":"jobs","uri":"/Users/ken/workspace/vela/plugins/amplifierd-jobs"}' \
      http://127.0.0.1:8410/bundles/register >/dev/null 2>&1 && echo "jobs plugin registered"
    break
  fi
  sleep 2
done
```

- [ ] **Step 3: Add tool-jobs to vela bundle**

In `~/.amplifier/bundles/vela.md`, add to the `session:` block:

```yaml
  tools:
    - module: tool-jobs
      source: /Users/ken/workspace/vela/plugins/amplifierd-jobs
      config:
        amplifierd_url: http://127.0.0.1:8410
        token: ${VELA_AUTH_TOKEN}
        max_parallel: 4
```

- [ ] **Step 4: Restart amplifierd**

```bash
launchctl bootout gui/$(id -u)/com.vela.amplifierd 2>/dev/null; sleep 2
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.vela.amplifierd.plist
sleep 10
```

- [ ] **Step 5: Smoke test the REST API**

```bash
TOKEN="cjpOWhqUiF0hET9_Lj9qygN8P9JScIbU5EF3O3fFmIQ"

# List jobs (should be empty)
curl -sf -H "x-amplifier-token: $TOKEN" http://127.0.0.1:8410/api/jobs
# Expected: []

# Create a once job
JOB=$(curl -sf -H "x-amplifier-token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"name":"smoke-test","trigger_type":"once","schedule":"0s","prompt":"say: JOBS_SMOKE_TEST","session_mode":"fresh"}' \
  http://127.0.0.1:8410/api/jobs)
echo $JOB
JOB_ID=$(echo $JOB | python3 -c "import sys,json; print(json.load(sys.stdin)['id'])")
# Expected: JSON with id, name, trigger_type

# Wait 3s for once job to fire
sleep 3

# Check runs
curl -sf -H "x-amplifier-token: $TOKEN" http://127.0.0.1:8410/api/jobs/$JOB_ID/runs
# Expected: at least one run with status=success
```

- [ ] **Step 6: Smoke test the AI tool**

```bash
cd /Users/ken/workspace/vela/harness
./harness "use the create_job tool to schedule a job: every 5 minutes, ask yourself what time it is. Use loop trigger, fresh session."
```

Expected output: one `[create_job]` tool block with the job definition, followed by a confirmation message from the assistant.

- [ ] **Step 7: Commit**

```bash
git add plugins/amplifierd-jobs/
git commit -m "feat(jobs): complete amplifierd-jobs plugin with scheduler, REST API, and AI tool"
```
