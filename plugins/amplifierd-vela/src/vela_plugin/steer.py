"""
Steer endpoint: POST /sessions/{session_id}/steer

Enqueues a mid-loop user message into a running loop-vela orchestrator session.
The message is injected as a user turn at the next iteration boundary.

Requires loop-vela to be the active orchestrator for the session.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Body, HTTPException


def make_steer_router(state: Any) -> APIRouter:
    router = APIRouter(prefix="/sessions")

    @router.post("/{session_id}/steer")
    async def steer_session(
        session_id: str, body: dict = Body(default={})
    ) -> dict[str, str]:
        """
        Inject a steering message into a running loop-vela session.

        The message is inserted as a user turn at the next tool-call boundary,
        so the LLM receives it before its next iteration and can adjust course.

        Body: {"message": "stop and do X instead"}
        """
        message = (body.get("message") or "").strip()
        if not message:
            raise HTTPException(status_code=422, detail="message is required")

        try:
            from amplifier_module_loop_vela import _steer_queues  # type: ignore[import]
        except ImportError:
            raise HTTPException(
                status_code=503,
                detail="loop-vela orchestrator module is not installed",
            )

        queue = _steer_queues.get(session_id)
        if queue is None:
            raise HTTPException(
                status_code=404,
                detail=(
                    f"Session '{session_id}' not found or is not running "
                    "the loop-vela orchestrator"
                ),
            )

        await queue.put(message)
        return {"status": "queued", "session_id": session_id}

    return router
