import platform
import socket
import types

from fastapi import FastAPI
from fastapi.testclient import TestClient

from vela_plugin.capabilities import make_capabilities_router


def _client(state):
    app = FastAPI()
    app.include_router(make_capabilities_router(state))
    return TestClient(app)


def test_capabilities_returns_required_fields(fake_state):
    client = _client(fake_state)
    r = client.get("/capabilities")
    assert r.status_code == 200
    body = r.json()

    assert body["hostname"] == socket.gethostname()
    expected_platform = f"{platform.system().lower()}/{platform.machine().lower()}"
    assert body["platform"] == expected_platform
    assert body["vela_plugin_version"] == "0.1.0"
    assert isinstance(body["amplifierd_version"], str)
    assert isinstance(body["active_bundles"], list)
    assert isinstance(body["available_tools"], list)
    assert isinstance(body["errors"], list)


def test_capabilities_active_bundles_reflects_state(fake_state):
    fake_state.bundle_registry.loaded = ["superpowers", "lifeos"]
    client = _client(fake_state)
    body = client.get("/capabilities").json()
    assert body["active_bundles"] == ["superpowers", "lifeos"]


def test_capabilities_available_tools_from_state():
    state = types.SimpleNamespace(
        bundle_registry=types.SimpleNamespace(loaded=[]),
        tool_registry=types.SimpleNamespace(list_tools=lambda: ["bash", "read_file"]),
    )
    client = _client(state)
    body = client.get("/capabilities").json()
    assert body["available_tools"] == ["bash", "read_file"]


def test_capabilities_handles_missing_tool_registry(fake_state):
    """Plugin must not crash if amplifierd doesn't expose a tool registry."""
    # fake_state has no tool_registry attribute
    client = _client(fake_state)
    body = client.get("/capabilities").json()
    assert body["available_tools"] == []


def test_capabilities_includes_bundle_errors(fake_state):
    from vela_plugin import bundles
    bundles._bundle_errors.clear()
    bundles._bundle_errors.append("lifeos: not found")
    try:
        client = _client(fake_state)
        body = client.get("/capabilities").json()
        assert "lifeos: not found" in body["errors"]
    finally:
        bundles._bundle_errors.clear()
