import json
import types
from pathlib import Path

import pytest
from fastapi import APIRouter, FastAPI
from fastapi.testclient import TestClient

import vela_plugin
from vela_plugin import bundles


@pytest.fixture
def patched_settings(tmp_path: Path, monkeypatch):
    """Redirect settings + projects paths to a tmp dir."""
    home = tmp_path / "home"
    amp = home / ".amplifierd"
    amp.mkdir(parents=True)
    settings_file = amp / "settings.json"
    projects_file = amp / "projects.json"

    settings_file.write_text(json.dumps({
        "bundles": ["superpowers"],
        "vela": {"auth_token": "tok"},
    }))

    monkeypatch.setattr("vela_plugin.settings.DEFAULT_PATH", settings_file)
    monkeypatch.setattr("vela_plugin.projects.DEFAULT_PROJECTS_PATH", projects_file)
    bundles._bundle_errors.clear()
    yield types.SimpleNamespace(settings=settings_file, projects=projects_file)
    bundles._bundle_errors.clear()


def test_create_router_returns_apirouter(patched_settings):
    state = types.SimpleNamespace(
        bundle_registry=types.SimpleNamespace(loaded=[], load=lambda n: state.bundle_registry.loaded.append(n)),
        session_manager=types.SimpleNamespace(list_sessions=lambda: []),
    )
    router = vela_plugin.create_router(state)
    assert isinstance(router, APIRouter)


def test_create_router_activates_bundles_at_startup(patched_settings):
    loaded: list[str] = []
    state = types.SimpleNamespace(
        bundle_registry=types.SimpleNamespace(loaded=loaded, load=lambda n: loaded.append(n)),
        session_manager=types.SimpleNamespace(list_sessions=lambda: []),
    )
    vela_plugin.create_router(state)
    assert loaded == ["superpowers"]


def test_create_router_protected_routes_require_token(patched_settings):
    loaded: list[str] = []
    state = types.SimpleNamespace(
        bundle_registry=types.SimpleNamespace(loaded=loaded, load=lambda n: loaded.append(n)),
        session_manager=types.SimpleNamespace(list_sessions=lambda: []),
    )
    app = FastAPI()
    app.include_router(vela_plugin.create_router(state))
    client = TestClient(app)

    # No token: 401
    r = client.get("/capabilities")
    assert r.status_code == 401

    # Correct token: 200
    r = client.get("/capabilities", headers={"X-Amplifier-Token": "tok"})
    assert r.status_code == 200
    assert r.json()["vela_plugin_version"] == "0.1.0"
