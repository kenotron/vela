"""FastAPI app implementing the C3 ledger REST API (design doc §4.2).

Endpoints (minimum viable, per the design doc's table):
    POST   /ledger/jobs                Create (idempotent on origin.tool_call_id — G2)
    GET    /ledger/jobs                List / filter by status, attention, time
    GET    /ledger/jobs/{id}           Detail
    PATCH  /ledger/jobs/{id}           Status / progress append
    POST   /ledger/jobs/{id}/decision  Record a human decision
    GET    /ledger/attention           Attention queue (card deck backing query)
    GET    /ledger/events              SSE stream of ledger changes
"""

from __future__ import annotations

import asyncio
import os
from pathlib import Path
from typing import Any

from fastapi import FastAPI, HTTPException, Query
from sse_starlette.sse import EventSourceResponse

from ledger_service.db import DuplicateToolCallError, JobNotFoundError, LedgerDB
from ledger_service.events import EventBroadcaster
from ledger_service.models import (
    DecisionRequest,
    Job,
    JobCreateRequest,
    JobPatchRequest,
)


def default_db_path() -> Path:
    override = os.environ.get("LEDGER_DB_PATH")
    if override:
        return Path(override)
    return Path.home() / ".vela" / "ledger" / "ledger.db"


def create_app(db_path: Path | None = None) -> FastAPI:
    app = FastAPI(title="vela-ledger-service", version="0.1.0")
    app.state.db = LedgerDB(path=db_path or default_db_path())
    app.state.broadcaster = EventBroadcaster()

    @app.post("/ledger/jobs", response_model=Job, status_code=201)
    async def create_job(req: JobCreateRequest) -> Job:
        db: LedgerDB = app.state.db
        try:
            record = db.create_job(
                session_id=req.origin.session_id,
                turn_id=req.origin.turn_id,
                tool_call_id=req.origin.tool_call_id,
                spec=req.spec,
                status=req.status,
            )
        except DuplicateToolCallError as exc:  # pragma: no cover - race-only path
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        await app.state.broadcaster.publish("job.created", record)
        return Job.from_record(record)

    @app.get("/ledger/jobs", response_model=list[Job])
    async def list_jobs(
        status: str | None = Query(default=None),
        attention_required: bool | None = Query(default=None),
        since: int | None = Query(default=None),
        limit: int = Query(default=200, le=1000),
    ) -> list[Job]:
        db: LedgerDB = app.state.db
        records = db.list_jobs(
            status=status,
            attention_required=attention_required,
            since=since,
            limit=limit,
        )
        return [Job.from_record(r) for r in records]

    @app.get("/ledger/attention", response_model=list[Job])
    async def attention_queue() -> list[Job]:
        db: LedgerDB = app.state.db
        return [Job.from_record(r) for r in db.attention_queue()]

    @app.get("/ledger/jobs/{job_id}", response_model=Job)
    async def get_job(job_id: str) -> Job:
        db: LedgerDB = app.state.db
        record = db.get_job(job_id)
        if record is None:
            raise HTTPException(status_code=404, detail="job not found")
        return Job.from_record(record)

    @app.patch("/ledger/jobs/{job_id}", response_model=Job)
    async def patch_job(job_id: str, req: JobPatchRequest) -> Job:
        db: LedgerDB = app.state.db
        try:
            record = db.update_job(
                job_id,
                status=req.status,
                progress_entry=req.progress_entry.model_dump()
                if req.progress_entry
                else None,
                attention=req.attention.model_dump() if req.attention else None,
                result=req.result,
                cost=req.cost.model_dump() if req.cost else None,
            )
        except JobNotFoundError as exc:
            raise HTTPException(status_code=404, detail="job not found") from exc
        await app.state.broadcaster.publish("job.updated", record)
        return Job.from_record(record)

    @app.post("/ledger/jobs/{job_id}/decision", response_model=Job)
    async def decide(job_id: str, req: DecisionRequest) -> Job:
        db: LedgerDB = app.state.db
        try:
            record = db.record_decision(
                job_id, new_status=req.new_status, decided_at=req.decided_at
            )
        except JobNotFoundError as exc:
            raise HTTPException(status_code=404, detail="job not found") from exc
        await app.state.broadcaster.publish("job.decided", record)
        return Job.from_record(record)

    @app.get("/ledger/events")
    async def events():
        broadcaster: EventBroadcaster = app.state.broadcaster
        queue = await broadcaster.subscribe()

        async def event_generator():
            try:
                while True:
                    item: dict[str, Any] = await queue.get()
                    yield item
            except asyncio.CancelledError:
                pass
            finally:
                await broadcaster.unsubscribe(queue)

        return EventSourceResponse(event_generator())

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.on_event("shutdown")
    def shutdown() -> None:
        app.state.db.close()

    return app


app = create_app()
