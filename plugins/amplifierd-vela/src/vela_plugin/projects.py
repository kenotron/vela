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


class ProjectRecord(BaseModel):
    id: str
    name: str
    description: str
    created_at: float
    working_dir: str = ""


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
    def create_project(body: dict = Body(default={})) -> ProjectRecord:
        name = (body.get("name") or "").strip()
        if not name:
            raise HTTPException(status_code=422, detail="name is required")
        record = ProjectRecord(
            id=uuid.uuid4().hex,
            name=name,
            description=body.get("description", ""),
            created_at=time.time(),
            working_dir=body.get("working_dir", ""),
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
        """Return sessions for this project split into active and recent.

        Status is hydrated from three sources in priority order:
          1. amplifierd in-memory session manager (live handle — most accurate)
          2. metadata.json on disk (session exists but not loaded into memory)
          3. vela-sessions.json snapshot (fallback — may be stale after restart)

        Sessions recorded as "running/waiting" in vela-sessions.json but absent
        from both memory and disk are surfaced as "completed" so the user can open
        them and resume execution if desired.
        """
        from . import session_store

        raw_sessions = session_store.get_sessions(project_id)
        if not raw_sessions:
            return {"active": [], "recent": [], "total": 0}

        # Use the state closure passed to make_projects_router to reach the
        # session manager without needing a Request parameter.
        manager = getattr(state, "session_manager", None)

        now = time.time()
        seven_days = 7 * 24 * 3600

        hydrated: list[Any] = []
        for s in raw_sessions:
            if manager is not None:
                live_handle = manager.get(s.session_id)
                if live_handle is not None:
                    # Session is live in memory — use authoritative status.
                    live_status = live_handle.status.value  # "idle", "executing", etc.
                    # Map amplifierd native statuses to vela statuses.
                    if live_status == "executing":
                        s.status = "running"
                    elif live_status in ("idle", "completed"):
                        s.status = "completed"
                    elif live_status == "failed":
                        s.status = "error"
                    try:
                        s.last_activity = live_handle.last_activity.timestamp()
                    except Exception:
                        pass
                else:
                    # Not in memory — try to get info from disk.
                    session_dir = manager.resolve_session_dir(s.session_id)
                    if session_dir is None:
                        # Truly gone: not in memory, not on disk.
                        # Keep the record but don't treat as active.
                        if s.status in ("running", "waiting"):
                            s.status = "completed"
                    else:
                        # On disk but not loaded: it was idle/completed.
                        # Read metadata.json for accurate last_activity / turn_count.
                        try:
                            from amplifierd.persistence import load_metadata
                            meta = load_metadata(session_dir)
                            last_updated = meta.get("last_updated")
                            if last_updated:
                                from datetime import datetime
                                dt = datetime.fromisoformat(last_updated)
                                s.last_activity = dt.timestamp()
                        except Exception:
                            pass
                        # Was recorded as running but is now cold on disk →
                        # mark completed so it shows in recent list.
                        if s.status in ("running", "waiting"):
                            s.status = "completed"

            hydrated.append(s)

        active = [s for s in hydrated if s.status in ("running", "waiting")]
        recent = [
            s
            for s in hydrated
            if s.status not in ("running", "waiting")
            and (s.last_activity == 0 or now - s.last_activity <= seven_days)
        ][:limit]

        return {
            "active": [_session_dict(s) for s in active],
            "recent": [_session_dict(s) for s in recent],
            "total": len(hydrated),
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



    @router.put("/{project_id}", response_model=ProjectRecord)
    def update_project(project_id: str, body: dict = Body(default={})) -> ProjectRecord:
            """Update a project's name and/or working directory."""
            projects = _read_projects(projects_path)
            project = next((p for p in projects if p["id"] == project_id), None)
            if project is None:
                raise HTTPException(status_code=404, detail="Project not found")

            if "name" in body and body["name"].strip():
                project["name"] = body["name"].strip()
            if "working_dir" in body:
                project["working_dir"] = body["working_dir"]
            if "description" in body:
                project["description"] = body["description"]

            _write_projects(projects_path, projects)
            return ProjectRecord(**project)


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
