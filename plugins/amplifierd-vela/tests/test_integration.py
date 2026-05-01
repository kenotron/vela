"""Full request lifecycle through create_router with all four plugin features wired."""

import json
import types
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import vela_plugin
import vela_plugin.session_store as session_store_mod
from vela_plugin import bundles


@pytest.fixture
def app(tmp_path: Path, monkeypatch):
    home = tmp_path / "home"
    amp = home / ".amplifierd"
    amp.mkdir(parents=True)
    settings_file = amp / "settings.json"
    projects_file = amp / "projects.json"
    sessions_file = amp / "vela-sessions.json"

    settings_file.write_text(json.dumps({
        "bundles": ["superpowers", "broken"],
        "vela": {"auth_token": "secret-token"},
    }))

    monkeypatch.setattr("vela_plugin.settings.DEFAULT_PATH", settings_file)
    monkeypatch.setattr("vela_plugin.projects.DEFAULT_PROJECTS_PATH", projects_file)
    monkeypatch.setattr(session_store_mod, "_store_path", lambda: sessions_file)
    bundles._bundle_errors.clear()

    loaded: list[str] = []

    def load(name: str) -> None:
        if name == "broken":
            raise RuntimeError("repo unreachable")
        loaded.append(name)

    state = types.SimpleNamespace(
        bundle_registry=types.SimpleNamespace(loaded=loaded, load=load),
        session_manager=types.SimpleNamespace(list_sessions=lambda: []),
        tool_registry=types.SimpleNamespace(list_tools=lambda: ["bash", "read_file"]),
    )

    fastapi_app = FastAPI()
    fastapi_app.include_router(vela_plugin.create_router(state))
    yield fastapi_app
    bundles._bundle_errors.clear()


def _auth(headers=None):
    headers = dict(headers or {})
    headers["X-Amplifier-Token"] = "secret-token"
    return headers


def test_full_lifecycle(app):
    fastapi_app = app
    client = TestClient(fastapi_app)

    # 1. Auth: anonymous request rejected.
    assert client.get("/capabilities").status_code == 401

    # 2. Capabilities reports active bundles AND the bundle activation error.
    caps = client.get("/capabilities", headers=_auth()).json()
    assert caps["active_bundles"] == ["superpowers"]
    assert any("broken" in e for e in caps["errors"])
    assert caps["available_tools"] == ["bash", "read_file"]
    assert caps["vela_plugin_version"] == "0.1.0"

    # 3. Create a project.
    created = client.post(
        "/projects",
        json={"name": "Home", "description": "Mac mini"},
        headers=_auth(),
    ).json()
    pid = created["id"]

    # 4. Create a vela session for the project, then list it.
    new_session = client.post(
        f"/projects/{pid}/sessions",
        json={"title": "alpha"},
        headers=_auth(),
    ).json()
    assert "session_id" in new_session

    project_sessions = client.get(f"/projects/{pid}/sessions", headers=_auth()).json()
    assert project_sessions["total"] == 1
    assert project_sessions["active"][0]["session_id"] == new_session["session_id"]

    # 5. List projects: returns the one we created.
    listed = client.get("/projects", headers=_auth()).json()
    assert [p["id"] for p in listed] == [pid]

    # 6. Delete the project; sessions remain in the vela store.
    assert client.delete(f"/projects/{pid}", headers=_auth()).status_code == 200
    assert client.get("/projects", headers=_auth()).json() == []
    # Sessions are still in the vela store (project deletion doesn't purge them).
    after = client.get(f"/projects/{pid}/sessions", headers=_auth()).json()
    assert after["total"] == 1
