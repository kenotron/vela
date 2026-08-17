"""F4: thin reverse-proxy client to lane 2.1's ``services/ledger`` C3 API.

Does NOT reimplement any ledger logic -- every call is forwarded verbatim
(method, path, query, JSON body) to the running ledger service and the
response is passed back through. Base URL is configurable via
``VELA_AGENTD_LEDGER_BASE_URL`` (default ``http://localhost:9199``) so the
ledger can run on a different host/port without code changes.
"""

from __future__ import annotations

import logging
import os
from typing import Any

import httpx

logger = logging.getLogger(__name__)

_DEFAULT_LEDGER_BASE_URL = "http://localhost:9199"


def ledger_base_url() -> str:
    return os.environ.get("VELA_AGENTD_LEDGER_BASE_URL", _DEFAULT_LEDGER_BASE_URL).rstrip("/")


class LedgerProxyClient:
    """Thin httpx-based forwarder to the ledger service's REST API."""

    def __init__(self, base_url: str | None = None, *, timeout: float = 10.0) -> None:
        self._base_url = (base_url or ledger_base_url()).rstrip("/")
        self._timeout = timeout

    async def forward(
        self,
        method: str,
        path: str,
        *,
        params: dict[str, Any] | None = None,
        json_body: Any = None,
    ) -> httpx.Response:
        """Forward a request to ``{base_url}{path}`` and return the raw response."""
        url = f"{self._base_url}{path}"
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            return await client.request(method, url, params=params, json=json_body)
