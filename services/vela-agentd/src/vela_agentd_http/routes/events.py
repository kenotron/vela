"""F3: GET /v1/events -- C2 SSE stream of tee'd kernel events.

Broadcasts events published via ``app.state.c2_broadcaster`` (an
``EventBroadcaster``, see ``_c2_broadcaster.py``) to any number of connected
SSE clients. Payload shapes match design doc §4.2 (see ``_c2_shapes.py``);
``agentName`` is populated for delegated sub-agent events by
``vela_agentd_lib.bundle.hook_streaming`` upstream of this route.

Auth: same bearer-token dependency as C1 (``require_bearer``).
"""

from __future__ import annotations

import asyncio
import json
import logging
from collections.abc import AsyncGenerator
from typing import Any

from fastapi import APIRouter, Depends, Request
from fastapi.responses import StreamingResponse

from vela_agentd_http._auth import require_bearer

logger = logging.getLogger("vela_agentd_http.events")

router = APIRouter()

_KEEPALIVE_INTERVAL_SECONDS = 15.0


def _sse(event: dict[str, Any]) -> str:
    return f"data: {json.dumps(event, separators=(',', ':'))}\n\n"


async def _stream_events(request: Request) -> AsyncGenerator[str, None]:
    broadcaster = request.app.state.c2_broadcaster
    queue = broadcaster.subscribe()
    try:
        yield ": connected\n\n"
        while True:
            if await request.is_disconnected():
                break
            try:
                event = await asyncio.wait_for(queue.get(), timeout=_KEEPALIVE_INTERVAL_SECONDS)
            except TimeoutError:
                yield ": keepalive\n\n"
                continue
            if event is None:
                continue
            yield _sse(event)
    finally:
        broadcaster.unsubscribe(queue)


@router.get("/v1/events", dependencies=[Depends(require_bearer)])
async def c2_events(request: Request) -> StreamingResponse:
    """C2 event/control route: SSE stream of tee'd, wire-shaped kernel events."""
    return StreamingResponse(
        _stream_events(request),
        media_type="text/event-stream",
        headers={
            "X-Accel-Buffering": "no",
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
        },
    )
