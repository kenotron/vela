"""Project metadata endpoints backed by ~/.amplifierd/projects.json."""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter, Body, HTTPException
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
    projects_path: Path | None = None,
) -> APIRouter:
    # Resolve lazily so monkeypatching DEFAULT_PROJECTS_PATH works in tests.
    if projects_path is None:
        projects_path = DEFAULT_PROJECTS_PATH
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

    @router.get("/{project_id}/sessions")
    def list_project_sessions(project_id: str, limit: int = 20) -> dict[str, Any]:
        """Return sessions for this project split into active and recent."""
        from . import session_store

        sessions = session_store.get_sessions(project_id)
        now = time.time()
        seven_days = 7 * 24 * 3600

        active = [s for s in sessions if s.status in ("running", "waiting")]
        recent = [
            s
            for s in sessions
            if s.status in ("completed", "error", "cancelled")
            and (s.last_activity == 0 or now - s.last_activity <= seven_days)
        ][:limit]

        return {
            "active": [_session_dict(s) for s in active],
            "recent": [_session_dict(s) for s in recent],
            "total": len(sessions),
        }

    @router.post("/{project_id}/sessions")
    def create_project_session(
        project_id: str, body: dict[str, Any] = Body(default={})
    ) -> dict[str, Any]:
        """Register a session with this project in the vela session store.

        If the Vela app has already created a real amplifierd session and passes
        its session_id in the body, we just register it (no UUID generation).
        Otherwise we create a new UUID for a metadata-only entry.
        """
        from . import session_store

        # Accept a pre-created session_id from the Vela Android app
        existing_session_id = body.get("session_id", "")
        session_id = existing_session_id if existing_session_id else str(uuid.uuid4())
        title = body.get("title", "")
        working_dir = body.get("working_directory", "~")

        session = session_store.VelaSession(
            session_id=session_id,
            project_id=project_id,
            created_at=time.time(),
            last_activity=time.time(),
            status="running",
            title=title,
        )
        session_store.add_session(session)

        return {
            "session_id": session_id,
            "project_id": project_id,
            "status": "running",
            "title": title,
            "working_directory": working_dir,
            "created_at": session.created_at,
        }

    @router.delete("/{project_id}")
    def delete_project(project_id: str) -> dict[str, str]:
        projects = _read_projects(projects_path)
        remaining = [p for p in projects if p["id"] != project_id]
        if len(remaining) == len(projects):
            raise HTTPException(status_code=404, detail={"error": "project_not_found"})
        _write_projects(projects_path, remaining)
        return {"id": project_id, "status": "deleted"}

    return router


def _session_dict(s: Any) -> dict[str, Any]:
    return {
        "session_id": s.session_id,
        "project_id": s.project_id,
        "status": s.status,
        "title": s.title,
        "created_at": s.created_at,
        "last_activity": s.last_activity,
    }
