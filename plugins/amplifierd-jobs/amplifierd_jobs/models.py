from __future__ import annotations

import json
import uuid
from dataclasses import dataclass, field, asdict
from datetime import datetime, timezone
from typing import Any, Literal


def _now() -> datetime:
    return datetime.now(timezone.utc)


def _uuid() -> str:
    return str(uuid.uuid4())


@dataclass
class Job:
    id: str
    name: str
    trigger_type: Literal["loop", "cron", "once"]
    schedule: str
    prompt: str
    session_mode: Literal["fresh", "persistent"]

    description: str = ""
    bundle_name: str = "vela"
    persistent_session_id: str | None = None
    enabled: bool = True
    created_at: datetime = field(default_factory=_now)
    updated_at: datetime = field(default_factory=_now)
    next_run_at: datetime | None = None
    last_run_at: datetime | None = None
    last_run_status: str | None = None

    @classmethod
    def new(cls, **kwargs: Any) -> "Job":
        return cls(id=_uuid(), **kwargs)

    def to_json(self) -> str:
        d = asdict(self)
        for k in ("created_at", "updated_at", "next_run_at", "last_run_at"):
            if d[k] is not None:
                d[k] = d[k].isoformat() if isinstance(d[k], datetime) else d[k]
        return json.dumps(d)

    def to_api_dict(self) -> dict:
        d = json.loads(self.to_json())
        return d

    @classmethod
    def from_dict(cls, d: dict) -> "Job":
        def _dt(v: str | None) -> datetime | None:
            if v is None:
                return None
            return datetime.fromisoformat(v)

        return cls(
            id=d["id"],
            name=d["name"],
            trigger_type=d["trigger_type"],
            schedule=d["schedule"],
            prompt=d["prompt"],
            session_mode=d["session_mode"],
            description=d.get("description", ""),
            bundle_name=d.get("bundle_name", "vela"),
            persistent_session_id=d.get("persistent_session_id"),
            enabled=d.get("enabled", True),
            created_at=_dt(d.get("created_at")) or _now(),
            updated_at=_dt(d.get("updated_at")) or _now(),
            next_run_at=_dt(d.get("next_run_at")),
            last_run_at=_dt(d.get("last_run_at")),
            last_run_status=d.get("last_run_status"),
        )


@dataclass
class JobRun:
    id: str
    job_id: str
    job_name: str
    session_id: str
    status: Literal["running", "success", "failed", "cancelled"]
    source: Literal["scheduled", "manual"]

    started_at: datetime = field(default_factory=_now)
    ended_at: datetime | None = None

    @classmethod
    def new(cls, **kwargs: Any) -> "JobRun":
        return cls(id=_uuid(), **kwargs)

    def to_json(self) -> str:
        d = asdict(self)
        for k in ("started_at", "ended_at"):
            if d[k] is not None:
                d[k] = d[k].isoformat() if isinstance(d[k], datetime) else d[k]
        return json.dumps(d)

    def to_api_dict(self) -> dict:
        return json.loads(self.to_json())

    @classmethod
    def from_dict(cls, d: dict) -> "JobRun":
        def _dt(v: str | None) -> datetime | None:
            return datetime.fromisoformat(v) if v else None

        return cls(
            id=d["id"],
            job_id=d["job_id"],
            job_name=d["job_name"],
            session_id=d["session_id"],
            status=d["status"],
            source=d["source"],
            started_at=_dt(d.get("started_at")) or _now(),
            ended_at=_dt(d.get("ended_at")),
        )
