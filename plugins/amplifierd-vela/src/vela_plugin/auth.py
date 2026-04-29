"""FastAPI token-auth dependency for the Vela plugin.

Reads ``vela.auth_token`` from settings; validates the ``X-Amplifier-Token``
header on every request. Bypasses the check for localhost callers and for
``/health`` so the bootstrap verify step can probe without a token.
"""

from __future__ import annotations

import hmac
from typing import Awaitable, Callable

from fastapi import HTTPException, Request

from .settings import Settings


_LOCAL_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})


def make_require_token(settings: Settings) -> Callable[[Request], Awaitable[None]]:
    """Build the FastAPI dependency bound to a specific ``Settings`` instance."""
    expected = settings.vela.auth_token

    async def require_token(request: Request) -> None:
        if request.url.path == "/health":
            return
        client = request.client
        if client is not None and client.host in _LOCAL_HOSTS:
            return
        provided = request.headers.get("X-Amplifier-Token", "")
        if not expected or not hmac.compare_digest(provided, expected):
            raise HTTPException(
                status_code=401,
                detail={"error": "invalid_token"},
            )

    return require_token
