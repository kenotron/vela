import pytest_asyncio
from pathlib import Path
from amplifierd_jobs.store import JobStore


@pytest_asyncio.fixture
async def store(tmp_path: Path) -> JobStore:
    db_path = tmp_path / "jobs.db"
    s = JobStore(str(db_path))
    await s.init()
    return s
