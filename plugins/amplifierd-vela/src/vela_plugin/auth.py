"""FastAPI token-auth dependency for the Vela plugin.

Reads ``vela.auth_token`` from settings; validates the ``X-Amplifier-Token``
header on every request. Bypasses the check for localhost callers and for
``/health`` so the bootstrap verify step can probe without a token.

When no token is configured the server runs in open/dev mode and accepts all
requests (useful during local development or when behind a trusted network).
"""

from __future__ import annotations

import hmac
import logging
from typing import Awaitable, Callable

from fastapi import HTTPException, Request

from .settings import VelaPluginSettings


_LOCAL_HOSTS = frozenset({"127.0.0.1", "::1", "localhost"})
_log = logging.getLogger(__name__)


def make_require_token(
    settings: VelaPluginSettings,
) -> Callable[[Request], Awaitable[None]]:
    """Build the FastAPI dependency bound to a specific ``VelaPluginSettings`` instance."""
    expected = settings.auth_token

    async def require_token(request: Request) -> None:
        # Open mode: no token configured, accept all requests.
        if not expected:
            _log.info("auth: open mode — no VELA_AUTH_TOKEN configured, accepting request")
            return
        if request.url.path == "/health":
            return
        client = request.client
        if client is not None and client.host in _LOCAL_HOSTS:
            return
        provided = request.headers.get("X-Amplifier-Token", "")
        if not hmac.compare_digest(provided, expected):
            raise HTTPException(
                status_code=401,
                detail={"error": "invalid_token"},
            )

    return require_token
