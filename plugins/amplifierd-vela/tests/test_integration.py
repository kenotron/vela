"""Full request lifecycle through create_router with all four plugin features wired."""

import json
import types
from pathlib import Path

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import vela_plugin
from vela_plugin import bundles


@pytest.fixture
def app(tmp_path: Path, monkeypatch):
    home = tmp_path / "home"
    amp = home / ".amplifierd"
    amp.mkdir(parents=True)
    settings_file = amp / "settings.json"
    projects_file = amp / "projects.json"

    settings_file.write_text(json.dumps({
        "bundles": ["superpowers", "broken"],
        "vela": {"auth_token": "secret-token"},
    }))

    monkeypatch.setattr("vela_plugin.settings.DEFAULT_PATH", settings_file)
    monkeypatch.setattr("vela_plugin.projects.DEFAULT_PROJECTS_PATH", projects_file)
    bundles._bundle_errors.clear()

    loaded: list[str] = []

    def load(name: str) -> None:
        if name == "broken":
            raise RuntimeError("repo unreachable")
        loaded.append(name)

    sessions = [
        {"id": "s-alpha", "metadata": {"project_id": "P_PLACEHOLDER"}},
        {"id": "s-other", "metadata": {"project_id": "other"}},
    ]

    state = types.SimpleNamespace(
        bundle_registry=types.SimpleNamespace(loaded=loaded, load=load),
        session_manager=types.SimpleNamespace(list_sessions=lambda: sessions),
        tool_registry=types.SimpleNamespace(list_tools=lambda: ["bash", "read_file"]),
    )

    fastapi_app = FastAPI()
    fastapi_app.include_router(vela_plugin.create_router(state))
    yield fastapi_app, sessions
    bundles._bundle_errors.clear()


def _auth(headers=None):
    headers = dict(headers or {})
    headers["X-Amplifier-Token"] = "secret-token"
    return headers


def test_full_lifecycle(app):
    fastapi_app, sessions = app
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

    # 4. Wire a session to that project_id and re-list.
    sessions[0]["metadata"]["project_id"] = pid
    project_sessions = client.get(f"/projects/{pid}/sessions", headers=_auth()).json()
    assert [s["id"] for s in project_sessions] == ["s-alpha"]

    # 5. List projects: returns the one we created.
    listed = client.get("/projects", headers=_auth()).json()
    assert [p["id"] for p in listed] == [pid]

    # 6. Delete the project; sessions remain in the session manager.
    assert client.delete(f"/projects/{pid}", headers=_auth()).status_code == 200
    assert client.get("/projects", headers=_auth()).json() == []
    # Session list is untouched.
    assert len(sessions) == 2
