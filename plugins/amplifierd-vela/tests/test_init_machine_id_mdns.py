"""Tests for machine_id + mDNS wiring and /health override in vela_plugin __init__."""

from __future__ import annotations

import types
import json
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient


# ---------------------------------------------------------------------------
# Shared fixtures
# ---------------------------------------------------------------------------


@pytest.fixture
def patched_settings(tmp_path: Path, monkeypatch):
    """Redirect settings + projects paths to a tmp dir."""
    amp = tmp_path / ".amplifierd"
    amp.mkdir(parents=True)
    settings_file = amp / "settings.json"
    projects_file = amp / "projects.json"

    settings_file.write_text(json.dumps({
        "bundles": [],
        "vela": {"auth_token": "tok"},
    }))

    monkeypatch.setattr("vela_plugin.settings.DEFAULT_PATH", settings_file)
    monkeypatch.setattr("vela_plugin.projects.DEFAULT_PROJECTS_PATH", projects_file)

    from vela_plugin import bundles
    bundles._bundle_errors.clear()
    yield settings_file
    bundles._bundle_errors.clear()


def _make_state():
    return types.SimpleNamespace(
        bundle_registry=types.SimpleNamespace(
            loaded=[],
            load=lambda n: None,
        ),
        session_manager=types.SimpleNamespace(list_sessions=lambda: []),
    )


# ---------------------------------------------------------------------------
# create_router: machine_id wiring
# ---------------------------------------------------------------------------


def test_create_router_sets_machine_id_on_state(patched_settings, monkeypatch):
    """create_router must call get_machine_id() and store result on state.machine_id."""
    import vela_plugin

    state = _make_state()

    monkeypatch.setattr("vela_plugin.machine_id.get_machine_id", lambda: "test-uuid-0001")
    # Stub out mDNS to avoid real network ops in tests
    mock_mdns = MagicMock()
    monkeypatch.setattr("vela_plugin.mdns_service.AmplifierdMdnsService", lambda **kw: mock_mdns)

    vela_plugin.create_router(state)

    assert state.machine_id == "test-uuid-0001"


def test_create_router_stores_mdns_service_on_state(patched_settings, monkeypatch):
    """create_router must instantiate AmplifierdMdnsService and store on state.mdns_service."""
    import vela_plugin

    state = _make_state()

    monkeypatch.setattr("vela_plugin.machine_id.get_machine_id", lambda: "test-uuid-0002")
    mock_mdns_instance = MagicMock()
    mock_mdns_cls = MagicMock(return_value=mock_mdns_instance)
    monkeypatch.setattr("vela_plugin.mdns_service.AmplifierdMdnsService", mock_mdns_cls)

    vela_plugin.create_router(state)

    assert state.mdns_service is mock_mdns_instance


def test_create_router_calls_mdns_start_with_hostname(patched_settings, monkeypatch):
    """create_router must call mdns.start(label=socket.gethostname())."""
    import socket
    import vela_plugin

    state = _make_state()

    monkeypatch.setattr("vela_plugin.machine_id.get_machine_id", lambda: "test-uuid-0003")
    mock_mdns = MagicMock()
    monkeypatch.setattr("vela_plugin.mdns_service.AmplifierdMdnsService", lambda **kw: mock_mdns)

    vela_plugin.create_router(state)

    mock_mdns.start.assert_called_once_with(label=socket.gethostname())


def test_create_router_passes_machine_id_to_mdns(patched_settings, monkeypatch):
    """create_router must pass machine_id= to AmplifierdMdnsService constructor."""
    import vela_plugin

    state = _make_state()

    monkeypatch.setattr("vela_plugin.machine_id.get_machine_id", lambda: "test-uuid-0004")
    mock_mdns_instance = MagicMock()
    mock_mdns_cls = MagicMock(return_value=mock_mdns_instance)
    monkeypatch.setattr("vela_plugin.mdns_service.AmplifierdMdnsService", mock_mdns_cls)

    vela_plugin.create_router(state)

    mock_mdns_cls.assert_called_once_with(machine_id="test-uuid-0004")


# ---------------------------------------------------------------------------
# _inject_health_override: gc-based route insertion
# ---------------------------------------------------------------------------


def test_inject_health_override_inserts_route_at_position_0():
    """_inject_health_override must insert a /health route at index 0 of app.routes."""
    from vela_plugin import _inject_health_override

    app = FastAPI()
    state = types.SimpleNamespace(session_manager=None)
    # Simulate app.state = state (stored in app.__dict__)
    app.__dict__["state"] = state

    _inject_health_override(state, "injected-uuid")

    assert len(app.routes) > 0
    assert app.routes[0].path == "/health"


def test_inject_health_override_route_path_is_health():
    """The inserted route has path '/health' and GET method."""
    from vela_plugin import _inject_health_override

    app = FastAPI()
    state = types.SimpleNamespace(session_manager=None)
    app.__dict__["state"] = state

    _inject_health_override(state, "my-machine-id")

    first_route = app.routes[0]
    assert first_route.path == "/health"
    assert "GET" in first_route.methods


def test_inject_health_override_warns_when_no_app_found(caplog):
    """_inject_health_override must log a warning when it cannot find a FastAPI app."""
    import logging
    from vela_plugin import _inject_health_override

    # Pass a state that is NOT stored in any FastAPI app's __dict__
    state = types.SimpleNamespace()

    with caplog.at_level(logging.WARNING, logger="vela_plugin"):
        _inject_health_override(state, "orphaned-uuid")

    assert any("machine_id will NOT appear" in r.message for r in caplog.records)


# ---------------------------------------------------------------------------
# /health override endpoint: response fields
# ---------------------------------------------------------------------------


def test_health_endpoint_returns_machine_id():
    """The injected /health route must return a JSON body with a 'machine_id' field."""
    from vela_plugin import _inject_health_override

    app = FastAPI()
    # Do not set start_time — getattr default of time.time() will be used
    state = types.SimpleNamespace(
        session_manager=types.SimpleNamespace(list_sessions=lambda: []),
    )
    app.__dict__["state"] = state
    # Make app.state also return our state so request.app.state works
    app.state = state  # type: ignore[assignment]

    _inject_health_override(state, "health-test-uuid")

    client = TestClient(app)
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()
    assert body["machine_id"] == "health-test-uuid"


def test_health_endpoint_returns_required_fields():
    """Injected /health must return status, version, uptime_seconds, active_sessions, rust_engine."""
    from vela_plugin import _inject_health_override

    app = FastAPI()
    state = types.SimpleNamespace(
        session_manager=types.SimpleNamespace(list_sessions=lambda: []),
    )
    app.__dict__["state"] = state
    app.state = state  # type: ignore[assignment]

    _inject_health_override(state, "fields-test-uuid")

    client = TestClient(app)
    resp = client.get("/health")
    assert resp.status_code == 200
    body = resp.json()

    assert body["status"] == "healthy"
    assert "version" in body
    assert "uptime_seconds" in body
    assert "active_sessions" in body
    assert "rust_engine" in body
    assert "machine_id" in body


def test_health_endpoint_active_sessions_count():
    """Injected /health active_sessions reflects session_manager.list_sessions() length."""
    from vela_plugin import _inject_health_override

    app = FastAPI()
    fake_sessions = ["s1", "s2", "s3"]
    state = types.SimpleNamespace(
        session_manager=types.SimpleNamespace(list_sessions=lambda: fake_sessions),
    )
    app.__dict__["state"] = state
    app.state = state  # type: ignore[assignment]

    _inject_health_override(state, "sessions-uuid")

    client = TestClient(app)
    resp = client.get("/health")
    assert resp.json()["active_sessions"] == 3


def test_health_endpoint_returns_zero_sessions_when_no_manager():
    """Injected /health active_sessions is 0 when session_manager is absent."""
    from vela_plugin import _inject_health_override

    app = FastAPI()
    state = types.SimpleNamespace(session_manager=None)
    app.__dict__["state"] = state
    app.state = state  # type: ignore[assignment]

    _inject_health_override(state, "no-manager-uuid")

    client = TestClient(app)
    resp = client.get("/health")
    assert resp.json()["active_sessions"] == 0
