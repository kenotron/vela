# Phase 1 — `amplifierd-vela` Plugin Implementation Plan

> **Execution:** Use the subagent-driven-development workflow to implement this plan.

**Goal:** Build the `amplifierd-vela` Python plugin that adds token auth, project endpoints, capabilities reporting, and bundle activation to a remote `amplifierd` daemon — installable via `uv tool install --with git+https://github.com/kenotron/vela#subdirectory=plugins/amplifierd-vela git+https://github.com/microsoft/amplifierd`.

**Architecture:** Single Python package at `plugins/amplifierd-vela/`, exposing `vela_plugin:create_router` as the `amplifierd.plugins` entry point. Each concern is one module: `settings.py`, `auth.py`, `projects.py`, `capabilities.py`, `bundles.py`. `create_router(state)` activates bundles, mounts the project + capabilities routers under a token-auth dependency, and returns the resulting `APIRouter` to the host daemon.

**Tech Stack:** Python 3.11+, FastAPI, `uv` for environment, `pytest` + `httpx` + FastAPI's `TestClient` for tests, hand-rolled fakes (no mock libraries).

**Design reference:** `docs/plans/2026-04-29-amplifierd-node-bootstrap-design.md` — Section 2 is authoritative.

---

## Conventions used throughout the plan

- All commands assume working directory is `/Users/ken/workspace/vela` unless stated otherwise.
- Test commands use `uv run pytest` from inside `plugins/amplifierd-vela/`.
- Commit message convention: `feat(vela-plugin): <description>`.
- Tests follow the **RED → GREEN → COMMIT** cycle. The failing-test step is its own action; do not skip it.
- Authentication failures return `HTTP 401` with body `{"detail": {"error": "invalid_token"}}` — this is FastAPI's standard wrapping of `HTTPException(detail=...)`. Tests assert this exact shape; the design doc's `{"error": "invalid_token"}` is realised as the inner `detail` value.

---

## Task 1 — Package scaffold

**Files:**
- Create: `plugins/amplifierd-vela/pyproject.toml`
- Create: `plugins/amplifierd-vela/src/vela_plugin/__init__.py`
- Create: `plugins/amplifierd-vela/tests/__init__.py`
- Create: `plugins/amplifierd-vela/tests/conftest.py`
- Create: `plugins/amplifierd-vela/tests/test_smoke.py`

**Step 1: Create directories**
Run:
```
mkdir -p plugins/amplifierd-vela/src/vela_plugin plugins/amplifierd-vela/tests
```
Expected: no output, directories created.

**Step 2: Write `pyproject.toml`**
Create `plugins/amplifierd-vela/pyproject.toml` with exactly:
```toml
[project]
name = "amplifierd-vela"
version = "0.1.0"
description = "Vela-specific plugin for amplifierd: auth, projects, capabilities, bundles."
requires-python = ">=3.11"
dependencies = ["fastapi>=0.100.0"]

[project.entry-points."amplifierd.plugins"]
vela = "vela_plugin:create_router"

[build-system]
requires = ["hatchling"]
build-backend = "hatchling.build"

[tool.hatch.build.targets.wheel]
packages = ["src/vela_plugin"]

[dependency-groups]
dev = ["pytest>=7.4", "httpx>=0.25"]

[tool.pytest.ini_options]
pythonpath = ["src"]
testpaths = ["tests"]
```

**Step 3: Write `src/vela_plugin/__init__.py` stub**
Create `plugins/amplifierd-vela/src/vela_plugin/__init__.py` with:
```python
"""amplifierd-vela plugin package."""

__version__ = "0.1.0"
```

**Step 4: Write `tests/__init__.py`**
Create `plugins/amplifierd-vela/tests/__init__.py` empty:
```python
```

**Step 5: Write `tests/conftest.py`**
Create `plugins/amplifierd-vela/tests/conftest.py` with:
```python
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
```

**Step 6: Write smoke test**
Create `plugins/amplifierd-vela/tests/test_smoke.py` with:
```python
import vela_plugin


def test_package_has_version():
    assert vela_plugin.__version__ == "0.1.0"
```

**Step 7: Sync the plugin's environment**
Run:
```
cd plugins/amplifierd-vela && uv sync --group dev
```
Expected: uv resolves and installs `fastapi`, `pytest`, `httpx`, and the package itself; no errors.

**Step 8: Run smoke test (must pass — package is real)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_smoke.py -v
```
Expected: `1 passed`.

**Step 9: Commit**
Run:
```
git add plugins/amplifierd-vela
git commit -m "feat(vela-plugin): scaffold amplifierd-vela package"
```

---

## Task 2 — `settings.py`

**Files:**
- Create: `plugins/amplifierd-vela/src/vela_plugin/settings.py`
- Create: `plugins/amplifierd-vela/tests/test_settings.py`

**Step 1: Write the failing tests**
Create `plugins/amplifierd-vela/tests/test_settings.py` with:
```python
from pathlib import Path

from vela_plugin.settings import Settings, VelaSettings, load_settings


def test_load_full_settings(write_settings):
    path = write_settings({
        "bundles": ["superpowers", "lifeos"],
        "vela": {"auth_token": "abc123"},
    })
    s = load_settings(path)
    assert s.vela.auth_token == "abc123"
    assert s.bundles == ["superpowers", "lifeos"]


def test_load_missing_file_returns_defaults(tmp_path: Path):
    s = load_settings(tmp_path / "nonexistent.json")
    assert s == Settings(vela=VelaSettings(auth_token=""), bundles=[])


def test_load_missing_vela_key_returns_empty_token(write_settings):
    path = write_settings({"bundles": ["x"]})
    s = load_settings(path)
    assert s.vela.auth_token == ""
    assert s.bundles == ["x"]


def test_load_missing_bundles_returns_empty_list(write_settings):
    path = write_settings({"vela": {"auth_token": "t"}})
    s = load_settings(path)
    assert s.bundles == []
    assert s.vela.auth_token == "t"


def test_load_null_values_treated_as_empty(write_settings):
    path = write_settings({"vela": None, "bundles": None})
    s = load_settings(path)
    assert s.vela.auth_token == ""
    assert s.bundles == []
```

**Step 2: Run the tests (must FAIL — module does not exist)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_settings.py -v
```
Expected: `ModuleNotFoundError: No module named 'vela_plugin.settings'` (collection error).

**Step 3: Implement `settings.py`**
Create `plugins/amplifierd-vela/src/vela_plugin/settings.py` with:
```python
"""Read ~/.amplifierd/settings.json into typed dataclasses."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_PATH = Path.home() / ".amplifierd" / "settings.json"


@dataclass(frozen=True)
class VelaSettings:
    auth_token: str = ""


@dataclass(frozen=True)
class Settings:
    vela: VelaSettings = field(default_factory=VelaSettings)
    bundles: list[str] = field(default_factory=list)


def load_settings(path: Path = DEFAULT_PATH) -> Settings:
    """Load settings from ``path``. Returns defaults if file is missing or empty."""
    if not Path(path).exists():
        return Settings()

    data = json.loads(Path(path).read_text() or "{}")
    vela_raw = data.get("vela") or {}
    bundles_raw = data.get("bundles") or []

    return Settings(
        vela=VelaSettings(auth_token=str(vela_raw.get("auth_token", ""))),
        bundles=list(bundles_raw),
    )
```

**Step 4: Run tests (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_settings.py -v
```
Expected: `5 passed`.

**Step 5: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/settings.py plugins/amplifierd-vela/tests/test_settings.py
git commit -m "feat(vela-plugin): add settings loader for ~/.amplifierd/settings.json"
```

---

## Task 3 — `auth.py`

**Files:**
- Create: `plugins/amplifierd-vela/src/vela_plugin/auth.py`
- Create: `plugins/amplifierd-vela/tests/test_auth.py`

**Step 1: Write the failing tests**
Create `plugins/amplifierd-vela/tests/test_auth.py` with:
```python
from fastapi import APIRouter, Depends, FastAPI
from fastapi.testclient import TestClient

from vela_plugin.auth import make_require_token
from vela_plugin.settings import Settings, VelaSettings


def _build_app(settings: Settings) -> TestClient:
    app = FastAPI()
    require = make_require_token(settings)

    router = APIRouter()

    @router.get("/protected", dependencies=[Depends(require)])
    def protected():
        return {"ok": True}

    @router.get("/health", dependencies=[Depends(require)])
    def health():
        return {"status": "ok"}

    app.include_router(router)
    return TestClient(app)


def test_valid_token_passes():
    client = _build_app(Settings(vela=VelaSettings(auth_token="secret")))
    r = client.get("/protected", headers={"X-Amplifier-Token": "secret"})
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_missing_token_rejected():
    client = _build_app(Settings(vela=VelaSettings(auth_token="secret")))
    # TestClient defaults client.host to "testclient" — not a localhost bypass
    r = client.get("/protected")
    assert r.status_code == 401
    assert r.json() == {"detail": {"error": "invalid_token"}}


def test_wrong_token_rejected():
    client = _build_app(Settings(vela=VelaSettings(auth_token="secret")))
    r = client.get("/protected", headers={"X-Amplifier-Token": "wrong"})
    assert r.status_code == 401
    assert r.json() == {"detail": {"error": "invalid_token"}}


def test_health_bypasses_auth():
    client = _build_app(Settings(vela=VelaSettings(auth_token="secret")))
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_localhost_ipv4_bypasses_auth():
    client = _build_app(Settings(vela=VelaSettings(auth_token="secret")))
    # Override transport client host to 127.0.0.1
    r = client.get("/protected", headers={"host": "localhost"}, extensions={})
    # TestClient's client tuple is set via `client` kwarg on the constructor —
    # use a direct ASGI scope override instead:
    from starlette.testclient import TestClient as _TC  # type: ignore
    app = client.app
    tc = _TC(app, base_url="http://testserver", client=("127.0.0.1", 12345))
    r = tc.get("/protected")
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_localhost_ipv6_bypasses_auth():
    from fastapi.testclient import TestClient as _TC
    settings = Settings(vela=VelaSettings(auth_token="secret"))
    client = _build_app(settings)
    tc = _TC(client.app, base_url="http://testserver", client=("::1", 12345))
    r = tc.get("/protected")
    assert r.status_code == 200


def test_empty_configured_token_still_rejects_anonymous():
    """A node without a configured token should NOT silently allow remote callers."""
    client = _build_app(Settings(vela=VelaSettings(auth_token="")))
    r = client.get("/protected")
    assert r.status_code == 401
```

**Step 2: Run the tests (must FAIL — module missing)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_auth.py -v
```
Expected: `ModuleNotFoundError: No module named 'vela_plugin.auth'`.

**Step 3: Implement `auth.py`**
Create `plugins/amplifierd-vela/src/vela_plugin/auth.py` with:
```python
"""FastAPI token-auth dependency for the Vela plugin.

Reads ``vela.auth_token`` from settings; validates the ``X-Amplifier-Token``
header on every request. Bypasses the check for localhost callers and for
``/health`` so the bootstrap verify step can probe without a token.
"""

from __future__ import annotations

import hmac
from typing import Awaitable, Callable

from fastapi import HTTPException, Request

from .settings import Settings


_LOCAL_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})


def make_require_token(settings: Settings) -> Callable[[Request], Awaitable[None]]:
    """Build the FastAPI dependency bound to a specific ``Settings`` instance."""
    expected = settings.vela.auth_token

    async def require_token(request: Request) -> None:
        if request.url.path == "/health":
            return
        client = request.client
        if client is not None and client.host in _LOCAL_HOSTS:
            return
        provided = request.headers.get("X-Amplifier-Token", "")
        if not expected or not hmac.compare_digest(provided, expected):
            raise HTTPException(
                status_code=401,
                detail={"error": "invalid_token"},
            )

    return require_token
```

**Step 4: Run the tests (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_auth.py -v
```
Expected: `7 passed`.

**Step 5: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/auth.py plugins/amplifierd-vela/tests/test_auth.py
git commit -m "feat(vela-plugin): add token auth dependency with localhost and /health bypass"
```

---

## Task 4 — `projects.py` (POST + GET `/projects`)

**Files:**
- Create: `plugins/amplifierd-vela/src/vela_plugin/projects.py`
- Create: `plugins/amplifierd-vela/tests/test_projects.py`

**Step 1: Write the failing tests**
Create `plugins/amplifierd-vela/tests/test_projects.py` with:
```python
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
```

**Step 2: Run the tests (must FAIL — module missing)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_projects.py -v
```
Expected: `ModuleNotFoundError: No module named 'vela_plugin.projects'`.

**Step 3: Implement `projects.py` (create + list)**
Create `plugins/amplifierd-vela/src/vela_plugin/projects.py` with:
```python
"""Project metadata endpoints backed by ~/.amplifierd/projects.json."""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter
from pydantic import BaseModel


DEFAULT_PROJECTS_PATH = Path.home() / ".amplifierd" / "projects.json"


class ProjectCreate(BaseModel):
    name: str
    description: str


class ProjectRecord(BaseModel):
    id: str
    name: str
    description: str
    created_at: float


def _read_projects(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    raw = path.read_text() or "[]"
    return list(json.loads(raw))


def _write_projects(path: Path, projects: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(json.dumps(projects, indent=2))
    os.replace(tmp, path)


def make_projects_router(
    state: Any,
    projects_path: Path = DEFAULT_PROJECTS_PATH,
) -> APIRouter:
    router = APIRouter(prefix="/projects")

    @router.post("", response_model=ProjectRecord)
    def create_project(body: ProjectCreate) -> ProjectRecord:
        record = ProjectRecord(
            id=uuid.uuid4().hex,
            name=body.name,
            description=body.description,
            created_at=time.time(),
        )
        projects = _read_projects(projects_path)
        projects.append(record.model_dump())
        _write_projects(projects_path, projects)
        return record

    @router.get("", response_model=list[ProjectRecord])
    def list_projects() -> list[ProjectRecord]:
        return [ProjectRecord(**p) for p in _read_projects(projects_path)]

    return router
```

**Step 4: Run the tests (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_projects.py -v
```
Expected: `6 passed`.

**Step 5: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/projects.py plugins/amplifierd-vela/tests/test_projects.py
git commit -m "feat(vela-plugin): add POST and GET /projects endpoints with atomic persistence"
```

---

## Task 5 — `projects.py` GET `/projects/{id}/sessions`

**Files:**
- Modify: `plugins/amplifierd-vela/src/vela_plugin/projects.py`
- Modify: `plugins/amplifierd-vela/tests/test_projects.py`

**Step 1: Append failing tests**
Append to `plugins/amplifierd-vela/tests/test_projects.py`:
```python
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
```

**Step 2: Run the new tests (must FAIL)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_projects.py -v -k "sessions"
```
Expected: `404 Not Found` for the new routes — 3 failed.

**Step 3: Add the route to `projects.py`**
In `plugins/amplifierd-vela/src/vela_plugin/projects.py`, inside `make_projects_router` (after `list_projects`, before `return router`), add:
```python
    @router.get("/{project_id}/sessions")
    def list_project_sessions(project_id: str) -> list[dict[str, Any]]:
        sessions = state.session_manager.list_sessions()
        out: list[dict[str, Any]] = []
        for s in sessions:
            metadata = s.get("metadata") if isinstance(s, dict) else getattr(s, "metadata", None)
            if isinstance(metadata, dict) and metadata.get("project_id") == project_id:
                out.append(s)
        return out
```

**Step 4: Run the tests (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_projects.py -v
```
Expected: `9 passed`.

**Step 5: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/projects.py plugins/amplifierd-vela/tests/test_projects.py
git commit -m "feat(vela-plugin): add GET /projects/{id}/sessions endpoint"
```

---

## Task 6 — `projects.py` DELETE `/projects/{id}`

**Files:**
- Modify: `plugins/amplifierd-vela/src/vela_plugin/projects.py`
- Modify: `plugins/amplifierd-vela/tests/test_projects.py`

**Step 1: Append failing tests**
Append to `plugins/amplifierd-vela/tests/test_projects.py`:
```python
def test_delete_project_removes_record(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    created = client.post("/projects", json={"name": "A", "description": ""}).json()
    pid = created["id"]
    r = client.delete(f"/projects/{pid}")
    assert r.status_code == 200
    assert client.get("/projects").json() == []


def test_delete_project_unknown_returns_404(fake_state, projects_path):
    client = _client(fake_state, projects_path)
    r = client.delete("/projects/does-not-exist")
    assert r.status_code == 404


def test_delete_project_does_not_touch_sessions(projects_path):
    """Sessions are detached, never deleted, when a project is removed."""
    sessions = [{"id": "s1", "metadata": {"project_id": "p1"}}]
    fsm = FakeSessionManager(sessions)
    state = types.SimpleNamespace(session_manager=fsm)
    client = _client(state, projects_path)

    # Seed a project and remove it.
    created = client.post("/projects", json={"name": "P1", "description": ""}).json()
    client.delete(f"/projects/{created['id']}")

    # Session list is untouched.
    assert fsm.list_sessions() == sessions
```

**Step 2: Run the new tests (must FAIL)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_projects.py -v -k "delete"
```
Expected: `405 Method Not Allowed` or `404` from missing route — 3 failed.

**Step 3: Add the DELETE route**
In `plugins/amplifierd-vela/src/vela_plugin/projects.py`, inside `make_projects_router` (after `list_project_sessions`), add:
```python
    @router.delete("/{project_id}")
    def delete_project(project_id: str) -> dict[str, str]:
        from fastapi import HTTPException

        projects = _read_projects(projects_path)
        remaining = [p for p in projects if p["id"] != project_id]
        if len(remaining) == len(projects):
            raise HTTPException(status_code=404, detail={"error": "project_not_found"})
        _write_projects(projects_path, remaining)
        return {"id": project_id, "status": "deleted"}
```

Move the `from fastapi import HTTPException` up to the top-level imports (replace the local `from fastapi import APIRouter` line with `from fastapi import APIRouter, HTTPException`) and remove the local import inside the function.

**Step 4: Run the tests (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_projects.py -v
```
Expected: `12 passed`.

**Step 5: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/projects.py plugins/amplifierd-vela/tests/test_projects.py
git commit -m "feat(vela-plugin): add DELETE /projects/{id} endpoint that detaches sessions"
```

---

## Task 7 — `capabilities.py`

**Files:**
- Create: `plugins/amplifierd-vela/src/vela_plugin/capabilities.py`
- Create: `plugins/amplifierd-vela/tests/test_capabilities.py`

**Step 1: Write the failing tests**
Create `plugins/amplifierd-vela/tests/test_capabilities.py` with:
```python
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
```

**Step 2: Run the tests (must FAIL — module missing)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_capabilities.py -v
```
Expected: `ModuleNotFoundError: No module named 'vela_plugin.capabilities'` (and `vela_plugin.bundles`).

**Step 3: Implement a temporary `bundles.py` stub**
Create `plugins/amplifierd-vela/src/vela_plugin/bundles.py` with:
```python
"""Bundle activation. Real implementation lands in Task 8."""

_bundle_errors: list[str] = []
```
This is just a placeholder so capabilities can import the error list now; the full module ships in Task 8.

**Step 4: Implement `capabilities.py`**
Create `plugins/amplifierd-vela/src/vela_plugin/capabilities.py` with:
```python
"""GET /capabilities — what this node can do, for the Android Node Detail screen."""

from __future__ import annotations

import platform
import socket
from importlib import metadata
from typing import Any

from fastapi import APIRouter

from . import __version__ as PLUGIN_VERSION
from . import bundles


def _amplifierd_version() -> str:
    try:
        return metadata.version("amplifierd")
    except Exception:
        return "unknown"


def _platform_string() -> str:
    return f"{platform.system().lower()}/{platform.machine().lower()}"


def _list_tools(state: Any) -> list[str]:
    registry = getattr(state, "tool_registry", None)
    if registry is None:
        return []
    list_tools = getattr(registry, "list_tools", None)
    if list_tools is None:
        return []
    try:
        return list(list_tools())
    except Exception:
        return []


def _list_active_bundles(state: Any) -> list[str]:
    registry = getattr(state, "bundle_registry", None)
    if registry is None:
        return []
    return list(getattr(registry, "loaded", []))


def make_capabilities_router(state: Any) -> APIRouter:
    router = APIRouter()

    @router.get("/capabilities")
    def capabilities() -> dict[str, Any]:
        return {
            "hostname": socket.gethostname(),
            "platform": _platform_string(),
            "amplifierd_version": _amplifierd_version(),
            "vela_plugin_version": PLUGIN_VERSION,
            "active_bundles": _list_active_bundles(state),
            "available_tools": _list_tools(state),
            "errors": list(bundles._bundle_errors),
        }

    return router
```

**Step 5: Run the tests (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_capabilities.py -v
```
Expected: `5 passed`.

**Step 6: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/capabilities.py plugins/amplifierd-vela/src/vela_plugin/bundles.py plugins/amplifierd-vela/tests/test_capabilities.py
git commit -m "feat(vela-plugin): add GET /capabilities endpoint"
```

---

## Task 8 — `bundles.py` activation

**Files:**
- Modify: `plugins/amplifierd-vela/src/vela_plugin/bundles.py`
- Create: `plugins/amplifierd-vela/tests/test_bundles.py`

**Step 1: Write the failing tests**
Create `plugins/amplifierd-vela/tests/test_bundles.py` with:
```python
import logging
import types

import pytest

from tests.conftest import FakeBundleRegistry
from vela_plugin import bundles
from vela_plugin.settings import Settings, VelaSettings


@pytest.fixture(autouse=True)
def reset_bundle_errors():
    bundles._bundle_errors.clear()
    yield
    bundles._bundle_errors.clear()


def test_activate_bundles_loads_each_one():
    state = types.SimpleNamespace(bundle_registry=FakeBundleRegistry())
    settings = Settings(bundles=["superpowers", "lifeos"], vela=VelaSettings())
    bundles.activate_bundles(state, settings)
    assert state.bundle_registry.loaded == ["superpowers", "lifeos"]
    assert bundles._bundle_errors == []


def test_activate_bundles_logs_each_success(caplog):
    state = types.SimpleNamespace(bundle_registry=FakeBundleRegistry())
    settings = Settings(bundles=["superpowers"], vela=VelaSettings())
    with caplog.at_level(logging.INFO, logger="vela_plugin.bundles"):
        bundles.activate_bundles(state, settings)
    assert any("[vela] activated bundle: superpowers" in r.message for r in caplog.records)


def test_activate_bundles_swallows_failure(caplog):
    state = types.SimpleNamespace(
        bundle_registry=FakeBundleRegistry(fail_for=["broken"])
    )
    settings = Settings(bundles=["superpowers", "broken"], vela=VelaSettings())
    with caplog.at_level(logging.WARNING, logger="vela_plugin.bundles"):
        bundles.activate_bundles(state, settings)  # must NOT raise
    assert "superpowers" in state.bundle_registry.loaded
    assert any("failed to activate bundle: broken" in r.message for r in caplog.records)
    assert any("broken" in err for err in bundles._bundle_errors)


def test_activate_bundles_no_registry_is_noop():
    state = types.SimpleNamespace(bundle_registry=None)
    settings = Settings(bundles=["superpowers"], vela=VelaSettings())
    bundles.activate_bundles(state, settings)  # must not raise
    assert bundles._bundle_errors == []


def test_activate_bundles_empty_list_is_noop():
    reg = FakeBundleRegistry()
    state = types.SimpleNamespace(bundle_registry=reg)
    settings = Settings(bundles=[], vela=VelaSettings())
    bundles.activate_bundles(state, settings)
    assert reg.loaded == []
```

**Step 2: Run the tests (must FAIL — function missing)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_bundles.py -v
```
Expected: `AttributeError: module 'vela_plugin.bundles' has no attribute 'activate_bundles'` — 5 failed.

**Step 3: Replace `bundles.py` with the real implementation**
Overwrite `plugins/amplifierd-vela/src/vela_plugin/bundles.py` with:
```python
"""Activate Amplifier bundles at plugin startup. Failures are logged, never raised."""

from __future__ import annotations

import logging
from typing import Any

from .settings import Settings


logger = logging.getLogger(__name__)

# Module-level error list read by capabilities.py. Populated by activate_bundles.
_bundle_errors: list[str] = []


def activate_bundles(state: Any, settings: Settings) -> None:
    """Load every bundle named in settings via ``state.bundle_registry.load``."""
    registry = getattr(state, "bundle_registry", None)
    if registry is None:
        return

    for name in settings.bundles:
        try:
            registry.load(name)
        except Exception as exc:  # noqa: BLE001 — by design: never break startup
            msg = f"{name}: {exc}"
            _bundle_errors.append(msg)
            logger.warning("[vela] failed to activate bundle: %s", msg)
            continue
        logger.info("[vela] activated bundle: %s", name)
```

**Step 4: Run the tests (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_bundles.py -v
```
Expected: `5 passed`.

**Step 5: Re-run the capabilities suite to confirm no regression**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_capabilities.py -v
```
Expected: `5 passed`.

**Step 6: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/bundles.py plugins/amplifierd-vela/tests/test_bundles.py
git commit -m "feat(vela-plugin): add bundle activation that logs and never raises"
```

---

## Task 9 — `create_router` integration

**Files:**
- Modify: `plugins/amplifierd-vela/src/vela_plugin/__init__.py`

**Step 1: Write the failing test**
Create `plugins/amplifierd-vela/tests/test_create_router.py` with:
```python
import json
import types
from pathlib import Path

import pytest
from fastapi import APIRouter, FastAPI
from fastapi.testclient import TestClient

import vela_plugin
from vela_plugin import bundles
from vela_plugin.settings import DEFAULT_PATH


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
```

**Step 2: Run the test (must FAIL — `create_router` not implemented)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_create_router.py -v
```
Expected: `AttributeError: module 'vela_plugin' has no attribute 'create_router'` — 3 failed.

**Step 3: Implement `create_router` in `__init__.py`**
Overwrite `plugins/amplifierd-vela/src/vela_plugin/__init__.py` with:
```python
"""amplifierd-vela plugin entry point."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

__version__ = "0.1.0"


def create_router(state: Any) -> APIRouter:
    """Plugin entry point. Activates bundles and mounts auth + endpoints."""
    # Imported lazily so test fixtures can monkeypatch DEFAULT_PATH before load.
    from .auth import make_require_token
    from .bundles import activate_bundles
    from .capabilities import make_capabilities_router
    from .projects import make_projects_router
    from .settings import load_settings

    settings = load_settings()
    activate_bundles(state, settings)

    require_token = make_require_token(settings)

    router = APIRouter()
    router.include_router(
        make_projects_router(state),
        dependencies=[Depends(require_token)],
    )
    router.include_router(
        make_capabilities_router(state),
        dependencies=[Depends(require_token)],
    )
    return router
```

**Step 4: Run the test (must PASS)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_create_router.py -v
```
Expected: `3 passed`.

**Step 5: Commit**
Run:
```
git add plugins/amplifierd-vela/src/vela_plugin/__init__.py plugins/amplifierd-vela/tests/test_create_router.py
git commit -m "feat(vela-plugin): wire create_router(state) entry point"
```

---

## Task 10 — End-to-end integration test

**Files:**
- Create: `plugins/amplifierd-vela/tests/test_integration.py`

**Step 1: Write the integration test**
Create `plugins/amplifierd-vela/tests/test_integration.py` with:
```python
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
```

**Step 2: Run the integration test (must PASS — everything is wired)**
Run:
```
cd plugins/amplifierd-vela && uv run pytest tests/test_integration.py -v
```
Expected: `1 passed`.

**Step 3: Run the full suite to confirm no regression**
Run:
```
cd plugins/amplifierd-vela && uv run pytest -v
```
Expected: all tests pass — total `36 passed` (5 settings + 7 auth + 12 projects + 5 capabilities + 5 bundles + 3 create_router + 1 integration + 1 smoke + minor fixture-import lines as needed; the precise count may vary by ±2 if a test was skipped or split — the requirement is **0 failures**).

**Step 4: Commit**
Run:
```
git add plugins/amplifierd-vela/tests/test_integration.py
git commit -m "feat(vela-plugin): add end-to-end integration test for plugin lifecycle"
```

---

## Verification checklist

After Task 10, the following should all be true:

- [ ] `cd plugins/amplifierd-vela && uv run pytest -v` → 0 failures.
- [ ] `plugins/amplifierd-vela/pyproject.toml` declares the `amplifierd.plugins` entry point as `vela = "vela_plugin:create_router"`.
- [ ] `vela_plugin.create_router(state)` returns a FastAPI `APIRouter` and never raises during bundle activation, even when bundles fail to load.
- [ ] All routes under the returned router require `X-Amplifier-Token` unless the request is from `127.0.0.1`/`::1`/`localhost` or hits `/health`.
- [ ] `~/.amplifierd/projects.json` is written atomically (write to `.tmp` → `os.replace`); no `.tmp` file remains after a successful write.
- [ ] `git log --oneline` shows one commit per task with the `feat(vela-plugin): ...` convention.

## Out of scope for this phase

- The Android `NodeBootstrapper` (Section 3 of the design).
- Service-file generation (Section 1 Step 6 of the design).
- Idempotency of `uv tool install --force` and the upgrade flow (Section 5).
- Publishing to PyPI (Decision 1 — git URL is fine for development).

These will be addressed in subsequent phase plans.
