"""Wire models for the broker's own surfaces.

Design doc references: docs/designs/2026-08-24-vela-fleet-execution-plane.md
  section 4.1 (broker responsibilities), 4.4 (job spec / D1), 5.1 (dispatch /
  D2 D3), 5.2 (progress+attention / D4 D5).

These are deliberately separate from `ledger_service.models` (frozen contract,
services/ledger/) even though several fields mirror it 1:1 -- the broker must
not import ledger internals; it only ever speaks the ledger's public REST API
over HTTP (see ledger_client.py).
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, Field

WorkerStatus = Literal["live", "stale", "gone"]


class Target(BaseModel):
    """Job targeting -- exact pin or capability-label match (design doc 4.4)."""

    machine_id: str | None = None
    labels: list[str] = Field(default_factory=list)
    strategy: Literal["least_loaded", "all", "any"] = "least_loaded"


class Limits(BaseModel):
    wall_clock_s: int | None = None
    usd: float | None = None


class JobSpec(BaseModel):
    """The wire form of D1 (design doc 4.4)."""

    title: str
    summary: str = ""
    runtime: str
    prompt: str
    target: Target = Field(default_factory=Target)
    cwd: str | None = None
    limits: Limits = Field(default_factory=Limits)


class DispatchRequest(BaseModel):
    """POST /fleet/dispatch body.

    `origin` matches the ledger's Origin shape exactly (session_id, turn_id,
    tool_call_id) so the broker can pass it straight through to
    POST /ledger/jobs unchanged -- this is what makes ledger job creation
    idempotent on tool_call_id (G2) end-to-end through the fleet plane.
    """

    origin: dict[str, str]
    spec: JobSpec


class DispatchResponse(BaseModel):
    job_id: str
    status: str
    machine_id: str | None = None
    last_heartbeat_age_ms: int | None = None


class RegisterRequest(BaseModel):
    """Worker -> broker registration/heartbeat payload (design doc 4.2 #2)."""

    machine_id: str
    labels: list[str] = Field(default_factory=list)
    runtimes: list[str] = Field(default_factory=list)


class WorkerInfo(BaseModel):
    machine_id: str
    labels: list[str]
    runtimes: list[str]
    last_heartbeat_ms: int
    active_jobs: int
    status: WorkerStatus


class WorkerEvent(BaseModel):
    """One line of the job wrapper's JSONL protocol (design doc 4.3), as
    relayed by a worker over its session. `job_id` and `machine_id` are
    stamped by the worker; everything else is free-form per `kind`.
    """

    ts: int
    kind: Literal["started", "progress", "attention", "cost", "finished", "failed"]
    job_id: str
    machine_id: str
    fields: dict[str, Any] = Field(default_factory=dict)


class DecisionRelay(BaseModel):
    """A human decision to push down to a running job (D4/D5 return path)."""

    job_id: str
    text: str
