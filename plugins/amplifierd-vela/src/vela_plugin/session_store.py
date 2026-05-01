"""Persistent store for project→session mappings managed by the Vela app."""
from __future__ import annotations

import json
import os
from dataclasses import asdict, dataclass
from pathlib import Path


@dataclass
class VelaSession:
    session_id: str
    project_id: str
    created_at: float
    last_activity: float = 0.0
    status: str = "running"  # running | waiting | completed | error
    title: str = ""


def _store_path() -> Path:
    return Path(os.path.expanduser("~/.amplifierd")) / "vela-sessions.json"


def load_all() -> dict[str, list[VelaSession]]:
    """Returns {project_id: [VelaSession]} mapping."""
    path = _store_path()
    if not path.exists():
        return {}
    try:
        raw = json.loads(path.read_text())
        return {
            pid: [VelaSession(**s) for s in sessions]
            for pid, sessions in raw.items()
        }
    except Exception:
        return {}


def save_all(data: dict[str, list[VelaSession]]) -> None:
    path = _store_path()
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(
            {pid: [asdict(s) for s in sessions] for pid, sessions in data.items()},
            indent=2,
        )
    )


def get_sessions(project_id: str) -> list[VelaSession]:
    return load_all().get(project_id, [])


def add_session(session: VelaSession) -> None:
    data = load_all()
    project_sessions = data.get(session.project_id, [])
    project_sessions.insert(0, session)  # newest first
    data[session.project_id] = project_sessions
    save_all(data)


def update_session_status(session_id: str, status: str, last_activity: float) -> None:
    data = load_all()
    for sessions in data.values():
        for s in sessions:
            if s.session_id == session_id:
                s.status = status
                s.last_activity = last_activity
                save_all(data)
                return
