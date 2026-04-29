"""Project metadata endpoints backed by ~/.amplifierd/projects.json."""

from __future__ import annotations

import json
import os
import time
import uuid
from pathlib import Path
from typing import Any

from fastapi import APIRouter, HTTPException
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

    @router.get("/{project_id}/sessions")
    def list_project_sessions(project_id: str) -> list[dict[str, Any]]:
        sessions = state.session_manager.list_sessions()
        out: list[dict[str, Any]] = []
        for s in sessions:
            metadata = s.get("metadata") if isinstance(s, dict) else getattr(s, "metadata", None)
            if isinstance(metadata, dict) and metadata.get("project_id") == project_id:
                out.append(s)
        return out

    @router.delete("/{project_id}")
    def delete_project(project_id: str) -> dict[str, str]:
        projects = _read_projects(projects_path)
        remaining = [p for p in projects if p["id"] != project_id]
        if len(remaining) == len(projects):
            raise HTTPException(status_code=404, detail={"error": "project_not_found"})
        _write_projects(projects_path, remaining)
        return {"id": project_id, "status": "deleted"}

    return router
