"""Shared fixtures: fake amplifierd state, temp settings, temp projects file."""

import json
import types
from pathlib import Path

import pytest


class FakeSessionManager:
    def __init__(self, sessions=None):
        self._sessions = list(sessions or [])

    def list_sessions(self):
        return list(self._sessions)


class FakeBundleRegistry:
    def __init__(self, fail_for=None):
        self.fail_for = set(fail_for or [])
        self.loaded = []

    def load(self, name):
        if name in self.fail_for:
            raise RuntimeError(f"could not load bundle {name}")
        self.loaded.append(name)


@pytest.fixture
def fake_state():
    return types.SimpleNamespace(
        session_manager=FakeSessionManager(),
        bundle_registry=FakeBundleRegistry(),
    )


@pytest.fixture
def settings_path(tmp_path: Path) -> Path:
    """Path to a fresh settings.json under a tmp HOME-like dir."""
    amplifierd_dir = tmp_path / ".amplifierd"
    amplifierd_dir.mkdir()
    return amplifierd_dir / "settings.json"


@pytest.fixture
def write_settings(settings_path: Path):
    def _write(payload: dict) -> Path:
        settings_path.write_text(json.dumps(payload))
        return settings_path

    return _write


@pytest.fixture
def projects_path(tmp_path: Path) -> Path:
    return tmp_path / ".amplifierd" / "projects.json"
