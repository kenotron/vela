"""Pydantic wire models for the C3 job resource (design doc §4.2)."""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

JobStatus = Literal[
    "accepted",
    "running",
    "needs_attention",
    "blocked",
    "done",
    "failed",
    "cancelled",
]


class Origin(BaseModel):
    session_id: str
    turn_id: str
    tool_call_id: str


class Attention(BaseModel):
    required: bool = False
    reason: str | None = None
    options: list[str] = Field(default_factory=list)
    deadline: int | None = None


class ProgressEntry(BaseModel):
    ts: int | None = None
    message: str
    percent: int | None = None
    source: str = "fleet"


class Cost(BaseModel):
    usd: float | None = None
    tokens: int | None = None


class JobCreateRequest(BaseModel):
    origin: Origin
    spec: dict[str, Any] = Field(default_factory=dict)
    status: JobStatus = "accepted"


class JobPatchRequest(BaseModel):
    status: JobStatus | None = None
    progress_entry: ProgressEntry | None = None
    attention: Attention | None = None
    result: dict[str, Any] | None = None
    cost: Cost | None = None


class DecisionRequest(BaseModel):
    new_status: JobStatus
    decided_at: int | None = None


class Job(BaseModel):
    job_id: str
    created_at: int
    updated_at: int
    origin: Origin
    spec: dict[str, Any]
    status: JobStatus
    attention: Attention
    progress: list[ProgressEntry]
    result: dict[str, Any] | None
    cost: Cost

    @classmethod
    def from_record(cls, record: dict[str, Any]) -> Job:
        clean = {k: v for k, v in record.items() if not k.startswith("_")}
        return cls.model_validate(clean)
