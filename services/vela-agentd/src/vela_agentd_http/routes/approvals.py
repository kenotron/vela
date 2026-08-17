"""F2: POST /v1/approvals/{id}/decision -- resolve a pending approval gate.

A connected C2 client observes an ``approval/requested`` event on
``GET /v1/events`` and posts its decision here. If the approval id is
unknown (already resolved, timed out, or never existed) this returns 404.
"""

from __future__ import annotations

from typing import Literal

from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel

from vela_agentd_http._auth import require_bearer

router = APIRouter()


class ApprovalDecision(BaseModel):
    action: Literal["accept", "decline"]


@router.post("/v1/approvals/{approval_id}/decision", dependencies=[Depends(require_bearer)])
async def post_approval_decision(approval_id: str, decision: ApprovalDecision, request: Request) -> dict[str, str]:
    gate = request.app.state.approval_gate
    resolved = gate.resolve(approval_id, decision.action)
    if not resolved:
        raise HTTPException(
            status_code=status.HTTP_404_NOT_FOUND,
            detail={"error": {"message": f"No pending approval with id {approval_id!r}", "type": "not_found"}},
        )
    return {"status": "ok", "approvalId": approval_id, "action": decision.action}
