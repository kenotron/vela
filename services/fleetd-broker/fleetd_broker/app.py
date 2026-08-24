"""FastAPI app for vela-fleetd (design doc 4.1) -- Lane F1.1 scope only.

Endpoints:
    POST /fleet/dispatch              Admission (D1/D2/D3): validate, select
                                       target, create ledger job, bind, return
                                       a handle. Must complete well under 1s.
    WS   /fleet/worker/{machine_id}   Worker session: register + heartbeat +
                                       job-event stream (design doc 4.2, 4.3).
                                       This is the "already-open connection"
                                       that makes D3 a lookup, not a probe.
    POST /fleet/jobs/{job_id}/decision  Decision relay (D4/D5 return path):
                                       record decision in the ledger, then
                                       push it down the owning worker's
                                       session.
    GET  /fleet/workers                Registry introspection (debugging/ops).
    GET  /healthz

Lane F1.2 (fleet-worker) is NOT implemented here -- see README.md for the
protocol this lane defines for that future consumer.
"""

from __future__ import annotations

import json
import logging
import os
from typing import Any

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect

from fleetd_broker.ledger_client import LedgerClient, ProgressCoalescer
from fleetd_broker.models import DecisionRelay, DispatchRequest, DispatchResponse
from fleetd_broker.registry import NoLiveWorkerError, WorkerRegistry
from fleetd_broker.sessions import SessionTable, WorkerUnavailableError
from fleetd_broker.worker_events import MalformedEventError, WorkerEventHandler

logger = logging.getLogger("fleetd_broker")


def default_ledger_url() -> str:
    return os.environ.get("FLEETD_LEDGER_URL", "http://127.0.0.1:8001")


def create_app(
    *, ledger_url: str | None = None, heartbeat_interval_s: float = 15.0
) -> FastAPI:
    app = FastAPI(title="vela-fleetd-broker", version="0.1.0")

    app.state.registry = WorkerRegistry(heartbeat_interval_s=heartbeat_interval_s)
    app.state.sessions = SessionTable()
    app.state.ledger = LedgerClient(ledger_url or default_ledger_url())
    app.state.coalescer = ProgressCoalescer()
    app.state.event_handler = WorkerEventHandler(
        registry=app.state.registry,
        sessions=app.state.sessions,
        ledger=app.state.ledger,
        coalescer=app.state.coalescer,
    )

    @app.post("/fleet/dispatch", response_model=DispatchResponse, status_code=202)
    async def dispatch(req: DispatchRequest) -> DispatchResponse:
        registry: WorkerRegistry = app.state.registry
        ledger: LedgerClient = app.state.ledger

        try:
            worker = registry.select_target(
                machine_id=req.spec.target.machine_id,
                labels=req.spec.target.labels,
            )
        except NoLiveWorkerError as exc:
            raise HTTPException(status_code=400, detail=f"UNREACHABLE: {exc}") from exc

        record = await ledger.create_job(
            origin=req.origin,
            spec=req.spec.model_dump(),
            status="accepted",
        )
        job_id = record["job_id"]

        app.state.sessions.bind_job(job_id, worker.machine_id)
        registry.increment_active_jobs(worker.machine_id, +1)

        return DispatchResponse(
            job_id=job_id,
            status="accepted",
            machine_id=worker.machine_id,
            last_heartbeat_age_ms=registry.heartbeat_age_ms(worker.machine_id),
        )

    @app.post("/fleet/jobs/{job_id}/decision")
    async def relay_decision(job_id: str, req: DecisionRelay) -> dict[str, Any]:
        if req.job_id != job_id:
            raise HTTPException(
                status_code=400, detail="job_id in path and body must match"
            )
        try:
            await app.state.sessions.relay_decision(job_id, req.text)
        except WorkerUnavailableError as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        return {"job_id": job_id, "relayed": True}

    @app.get("/fleet/workers")
    async def list_workers() -> list[dict[str, Any]]:
        registry: WorkerRegistry = app.state.registry
        return [
            {
                "machine_id": r.machine_id,
                "labels": sorted(r.labels),
                "runtimes": r.runtimes,
                "active_jobs": r.active_jobs,
                "live": registry.is_live(r),
                "last_heartbeat_age_ms": registry.heartbeat_age_ms(r.machine_id),
            }
            for r in registry.list_live()
        ]

    @app.websocket("/fleet/worker/{machine_id}")
    async def worker_session(websocket: WebSocket, machine_id: str) -> None:
        """One held connection per worker (design doc 4.2 #1).

        Protocol (newline-delimited JSON over the socket, mirroring the
        job-wrapper JSONL shape so the worker can forward events with
        minimal translation -- see README.md "Worker <-> broker protocol"):

            -> {"op": "register", "labels": [...], "runtimes": [...]}
            <- {"op": "registered"}
            -> {"op": "heartbeat"}
            -> {"op": "event", "ts": ..., "kind": "progress"|"attention"|...,
                "job_id": "...", "fields": {...}}
            <- {"op": "decision", "job_id": "...", "text": "..."}  (broker-initiated)
        """
        await websocket.accept()
        registry: WorkerRegistry = app.state.registry
        sessions: SessionTable = app.state.sessions
        event_handler: WorkerEventHandler = app.state.event_handler

        class WebSocketSender:
            async def send_decision(
                self, machine_id: str, job_id: str, text: str
            ) -> None:
                await websocket.send_text(
                    json.dumps({"op": "decision", "job_id": job_id, "text": text})
                )

        registered = False
        try:
            while True:
                raw = await websocket.receive_text()
                try:
                    msg = json.loads(raw)
                except json.JSONDecodeError:
                    logger.warning("worker %s sent invalid JSON", machine_id)
                    continue

                op = msg.get("op")
                if op == "register":
                    registry.register(
                        machine_id=machine_id,
                        labels=msg.get("labels", []),
                        runtimes=msg.get("runtimes", []),
                    )
                    sessions.attach_sender(machine_id, WebSocketSender())
                    registered = True
                    await websocket.send_text(json.dumps({"op": "registered"}))
                elif op == "heartbeat":
                    registry.heartbeat(machine_id)
                elif op == "event":
                    try:
                        await event_handler.handle(msg)
                    except MalformedEventError as exc:
                        logger.warning(
                            "worker %s sent malformed event: %s", machine_id, exc
                        )
                else:
                    logger.warning("worker %s sent unknown op %r", machine_id, op)
        except WebSocketDisconnect:
            pass
        finally:
            if registered:
                registry.disconnect(machine_id)
                sessions.detach_sender(machine_id)

    @app.get("/healthz")
    async def healthz() -> dict[str, str]:
        return {"status": "ok"}

    @app.on_event("shutdown")
    async def shutdown() -> None:
        await app.state.ledger.aclose()

    return app


app = create_app()
