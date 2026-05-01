import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

import vela_plugin.session_store as session_store_mod
from vela_plugin.projects import make_projects_router


def _client(state, projects_path, monkeypatch, tmp_path):
    sessions_file = tmp_path / "vela-sessions.json"
    monkeypatch.setattr(session_store_mod, "_store_path", lambda: sessions_file)
    app = FastAPI()
    app.include_router(make_projects_router(state, projects_path=projects_path))
    return TestClient(app)


# ── project CRUD ─────────────────────────────────────────────────────────────


def test_create_project_returns_record(fake_state, projects_path, monkeypatch, tmp_path):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    r = client.post("/projects", json={"name": "Home", "description": "Mac mini"})
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "Home"
    assert body["description"] == "Mac mini"
    assert isinstance(body["id"], str) and len(body["id"]) >= 8
    assert isinstance(body["created_at"], float)


def test_create_project_persists_to_disk(fake_state, projects_path, monkeypatch, tmp_path):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    client.post("/projects", json={"name": "A", "description": "x"})
    on_disk = json.loads(projects_path.read_text())
    assert len(on_disk) == 1
    assert on_disk[0]["name"] == "A"


def test_create_project_atomic_write_no_tmp_left_behind(
    fake_state, projects_path, monkeypatch, tmp_path
):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    client.post("/projects", json={"name": "A", "description": "x"})
    tmp = projects_path.with_suffix(projects_path.suffix + ".tmp")
    assert not tmp.exists()


def test_list_projects_empty(fake_state, projects_path, monkeypatch, tmp_path):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    r = client.get("/projects")
    assert r.status_code == 200
    assert r.json() == []


def test_list_projects_returns_all(fake_state, projects_path, monkeypatch, tmp_path):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    client.post("/projects", json={"name": "A", "description": ""})
    client.post("/projects", json={"name": "B", "description": ""})
    r = client.get("/projects")
    assert r.status_code == 200
    names = {p["name"] for p in r.json()}
    assert names == {"A", "B"}


def test_create_project_validates_body(fake_state, projects_path, monkeypatch, tmp_path):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    r = client.post("/projects", json={})
    assert r.status_code == 422  # FastAPI validation error


def test_delete_project_removes_record(fake_state, projects_path, monkeypatch, tmp_path):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    created = client.post("/projects", json={"name": "A", "description": ""}).json()
    pid = created["id"]
    r = client.delete(f"/projects/{pid}")
    assert r.status_code == 200
    assert client.get("/projects").json() == []


def test_delete_project_unknown_returns_404(fake_state, projects_path, monkeypatch, tmp_path):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    r = client.delete("/projects/does-not-exist")
    assert r.status_code == 404


# ── session endpoints (vela session store) ───────────────────────────────────


def test_list_project_sessions_empty_when_none_exist(
    fake_state, projects_path, monkeypatch, tmp_path
):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    r = client.get("/projects/p1/sessions")
    assert r.status_code == 200
    body = r.json()
    assert body["active"] == []
    assert body["recent"] == []
    assert body["total"] == 0


def test_create_project_session_returns_session_id(
    fake_state, projects_path, monkeypatch, tmp_path
):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    r = client.post(
        "/projects/p1/sessions",
        json={"title": "Test session", "working_directory": "~/work"},
    )
    assert r.status_code == 200
    body = r.json()
    assert "session_id" in body
    assert body["project_id"] == "p1"
    assert body["title"] == "Test session"
    assert body["working_directory"] == "~/work"
    assert body["status"] == "running"


def test_created_session_appears_in_list(
    fake_state, projects_path, monkeypatch, tmp_path
):
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)
    created = client.post(
        "/projects/p1/sessions", json={"title": "My session"}
    ).json()

    r = client.get("/projects/p1/sessions")
    assert r.status_code == 200
    body = r.json()
    assert body["total"] == 1
    assert len(body["active"]) == 1
    assert body["active"][0]["session_id"] == created["session_id"]


def test_list_sessions_separates_active_from_recent(
    fake_state, projects_path, monkeypatch, tmp_path
):
    """Active sessions (running/waiting) and completed ones are split into separate lists."""
    import time

    sessions_file = tmp_path / "vela-sessions.json"
    monkeypatch.setattr(session_store_mod, "_store_path", lambda: sessions_file)

    # Pre-seed: one running, one completed
    s_running = session_store_mod.VelaSession(
        session_id="r1", project_id="p1", created_at=time.time(), status="running"
    )
    s_done = session_store_mod.VelaSession(
        session_id="d1",
        project_id="p1",
        created_at=time.time() - 100,
        last_activity=time.time() - 100,
        status="completed",
    )
    session_store_mod.add_session(s_running)
    session_store_mod.add_session(s_done)

    app = FastAPI()
    app.include_router(make_projects_router(fake_state, projects_path=projects_path))
    client = TestClient(app)

    r = client.get("/projects/p1/sessions")
    assert r.status_code == 200
    body = r.json()
    assert [s["session_id"] for s in body["active"]] == ["r1"]
    assert [s["session_id"] for s in body["recent"]] == ["d1"]
    assert body["total"] == 2


def test_delete_project_does_not_touch_sessions(
    fake_state, projects_path, monkeypatch, tmp_path
):
    """Sessions in the vela store survive project deletion."""
    client = _client(fake_state, projects_path, monkeypatch, tmp_path)

    created = client.post("/projects", json={"name": "P1", "description": ""}).json()
    pid = created["id"]
    client.post(f"/projects/{pid}/sessions", json={"title": "session"})

    # Verify session exists
    before = client.get(f"/projects/{pid}/sessions").json()
    assert before["total"] == 1

    # Delete the project
    client.delete(f"/projects/{pid}")
    assert client.get("/projects").json() == []

    # Session still retrievable by project_id from the store
    after = client.get(f"/projects/{pid}/sessions").json()
    assert after["total"] == 1
