"""F0.2: `POST /fleet/dispatch` -- SSH-out fleet dispatch (design doc §9.1, §10 Lane F0.2).

Kicks off the SSH launch of `velafleet-run` and, on success, starts a
background asyncio task that tails the job's `events.jsonl` over a second
SSH session and PATCHes the ledger as events arrive. The HTTP call itself
returns as soon as the launch completes (a single SSH round trip) -- it does
not wait for the job to finish.
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel, Field
from vela_agentd_lib.fleet.config import load_fleet_ssh_config
from vela_agentd_lib.fleet.dispatch import (
    FleetDispatchError,
    dispatch_job,
    tail_and_report,
)

from vela_agentd_http._auth import require_bearer

logger = logging.getLogger(__name__)

router = APIRouter()


class FleetDispatchRequest(BaseModel):
    job_id: str = Field(..., description="Ledger job id this dispatch corresponds to")
    runtime: str = Field(
        ..., description="Adapter/runtime name, e.g. 'shell', 'amplifier-agent'"
    )
    argv: list[str] = Field(
        default_factory=list, description="Command + args passed to the runtime"
    )


class FleetDispatchResponse(BaseModel):
    job_id: str
    reachable: bool
    machine_id: str | None = None
    detail: str


def _ledger_client(request: Request) -> Any:
    from vela_agentd_http._ledger_proxy import LedgerProxyClient

    client = getattr(request.app.state, "ledger_client", None)
    if client is None:
        client = LedgerProxyClient()
        request.app.state.ledger_client = client
    return client


@router.post(
    "/fleet/dispatch",
    dependencies=[Depends(require_bearer)],
    response_model=FleetDispatchResponse,
)
async def fleet_dispatch(
    body: FleetDispatchRequest, request: Request
) -> FleetDispatchResponse:
    """Launch a job on the F0 fleet target and start tailing its events.

    Returns quickly (a single SSH launch round trip); progress/attention/
    completion arrive at the ledger asynchronously via the background tail
    task, never via this response.
    """
    ledger = _ledger_client(request)
    config = load_fleet_ssh_config()

    try:
        handle = await dispatch_job(
            body.job_id, body.runtime, body.argv, ledger=ledger, config=config
        )
    except FleetDispatchError as exc:
        logger.warning("fleet dispatch failed for job %s: %s", body.job_id, exc)
        raise HTTPException(
            status_code=400, detail={"error": {"message": str(exc)}}
        ) from exc
    except TimeoutError as exc:
        raise HTTPException(
            status_code=400,
            detail={
                "error": {
                    "message": f"UNREACHABLE: SSH connect to fleet target timed out: {exc}"
                }
            },
        ) from exc

    # Fire-and-forget: the tail task outlives this request. Stashed on
    # app.state so it isn't garbage-collected mid-flight, and so tests /
    # shutdown can inspect/cancel it.
    task = asyncio.create_task(
        tail_and_report(body.job_id, ledger=ledger, config=config),
        name=f"fleet-tail-{body.job_id}",
    )
    tasks: dict[str, asyncio.Task[None]] = (
        getattr(request.app.state, "fleet_tail_tasks", None) or {}
    )
    tasks[body.job_id] = task
    request.app.state.fleet_tail_tasks = tasks

    return FleetDispatchResponse(
        job_id=handle.job_id,
        reachable=handle.launched,
        machine_id=handle.machine_id,
        detail=handle.detail,
    )
