from __future__ import annotations

import asyncio
import inspect
import json
import logging
from typing import Literal

import httpx

logger = logging.getLogger(__name__)

RunStatus = Literal["success", "failed", "cancelled"]


class SessionClient:
    """Async HTTP client for the amplifierd session API."""

    def __init__(self, base_url: str, token: str, timeout: float = 30.0):
        self._base = base_url.rstrip("/")
        self._token = token
        self._http = httpx.AsyncClient(
            headers={"x-amplifier-token": token},
            timeout=timeout,
        )

    async def create_session(self, bundle_name: str) -> str:
        """Create a new amplifierd session. Returns session_id."""
        resp = await self._http.post(
            f"{self._base}/sessions",
            json={"bundle_name": bundle_name},
        )
        resp.raise_for_status()
        data = resp.json()
        if inspect.isawaitable(data):
            data = await data
        return data["session_id"]

    async def execute_prompt(self, session_id: str, prompt: str) -> None:
        """Submit a prompt to an existing session for execution."""
        resp = await self._http.post(
            f"{self._base}/sessions/{session_id}/execute/stream",
            json={"prompt": prompt},
        )
        resp.raise_for_status()

    async def wait_for_completion(
        self, session_id: str, timeout: float = 1800.0
    ) -> RunStatus:
        """Subscribe to the session's SSE stream and wait for orchestrator:complete.

        Returns "success" on orchestrator:complete, "failed" on timeout or error.
        """
        url = f"{self._base}/events?session={session_id}"
        try:
            async with asyncio.timeout(timeout):
                stream_ctx = self._http.stream("GET", url)
                if inspect.isawaitable(stream_ctx):
                    stream_ctx = await stream_ctx
                async with stream_ctx as resp:
                    current_event: str | None = None
                    async for line in resp.aiter_lines():
                        line = line.strip()
                        if line.startswith("event:"):
                            current_event = line[len("event:") :].strip()
                        elif line.startswith("data:") and current_event:
                            if current_event == "orchestrator:complete":
                                return "success"
                            elif current_event in ("execution:end",):
                                try:
                                    data = json.loads(line[len("data:") :].strip())
                                    inner = data.get("data", {})
                                    if inner.get("status") == "error":
                                        return "failed"
                                except Exception:
                                    pass
                            current_event = None
        except asyncio.TimeoutError:
            logger.warning("wait_for_completion timed out for session %s", session_id)
            return "failed"
        except Exception as exc:
            logger.error(
                "wait_for_completion error for session %s: %s", session_id, exc
            )
            return "failed"
        return "success"

    async def aclose(self) -> None:
        await self._http.aclose()
