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
