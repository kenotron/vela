import json

from fastapi import FastAPI
from fastapi.testclient import TestClient

from vela_plugin.projects import make_projects_router


def _client(state, projects_path):
    app = FastAPI()
    app.include_router(make_projects_router(state, projects_path=projects_path))
    return TestClient(app)


def test_create_project_returns_record(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    r = client.post("/projects", json={"name": "Home", "description": "Mac mini"})
    assert r.status_code == 200
    body = r.json()
    assert body["name"] == "Home"
    assert body["description"] == "Mac mini"
    assert isinstance(body["id"], str) and len(body["id"]) >= 8
    assert isinstance(body["created_at"], float)


def test_create_project_persists_to_disk(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    client.post("/projects", json={"name": "A", "description": "x"})
    on_disk = json.loads(projects_path.read_text())
    assert len(on_disk) == 1
    assert on_disk[0]["name"] == "A"


def test_create_project_atomic_write_no_tmp_left_behind(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    client.post("/projects", json={"name": "A", "description": "x"})
    tmp = projects_path.with_suffix(projects_path.suffix + ".tmp")
    assert not tmp.exists()


def test_list_projects_empty(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    r = client.get("/projects")
    assert r.status_code == 200
    assert r.json() == []


def test_list_projects_returns_all(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    client.post("/projects", json={"name": "A", "description": ""})
    client.post("/projects", json={"name": "B", "description": ""})
    r = client.get("/projects")
    assert r.status_code == 200
    names = {p["name"] for p in r.json()}
    assert names == {"A", "B"}


def test_create_project_validates_body(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    r = client.post("/projects", json={})
    assert r.status_code == 422  # FastAPI validation error


from tests.conftest import FakeSessionManager  # noqa: E402  (re-import for clarity)
import types  # noqa: E402


def test_list_project_sessions_filters_by_project_id(projects_path):
    sessions = [
        {"id": "s1", "metadata": {"project_id": "p1"}},
        {"id": "s2", "metadata": {"project_id": "p2"}},
        {"id": "s3", "metadata": {"project_id": "p1"}},
        {"id": "s4", "metadata": {}},
    ]
    state = types.SimpleNamespace(session_manager=FakeSessionManager(sessions))
    client = _client(state, projects_path)

    r = client.get("/projects/p1/sessions")
    assert r.status_code == 200
    ids = [s["id"] for s in r.json()]
    assert ids == ["s1", "s3"]


def test_list_project_sessions_empty_when_none_match(projects_path):
    sessions = [{"id": "s1", "metadata": {"project_id": "other"}}]
    state = types.SimpleNamespace(session_manager=FakeSessionManager(sessions))
    client = _client(state, projects_path)
    r = client.get("/projects/missing/sessions")
    assert r.status_code == 200
    assert r.json() == []


def test_list_project_sessions_handles_session_without_metadata(projects_path):
    sessions = [{"id": "s1"}]  # no metadata key at all
    state = types.SimpleNamespace(session_manager=FakeSessionManager(sessions))
    client = _client(state, projects_path)
    r = client.get("/projects/p1/sessions")
    assert r.status_code == 200
    assert r.json() == []
