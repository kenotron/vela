"""Client for the C3 ledger REST API (services/ledger/, frozen contract).

The broker is the sole ledger writer for fleet jobs (invariant FB2, design
doc 4.1). Workers never see a ledger URL or credential (FA6/FA9). This
module is the only place in the broker that talks to the ledger, so the
ledger's REST shape is isolated behind a narrow interface here.

Progress events are coalesced with a bounded flush interval; attention
events are never coalesced and are written immediately (design doc 5.2).
"""

from __future__ import annotations

import time
from typing import Any

import httpx


class LedgerClientError(Exception):
    pass


class LedgerClient:
    def __init__(self, base_url: str, *, timeout_s: float = 5.0) -> None:
        self._base_url = base_url.rstrip("/")
        self._client = httpx.AsyncClient(base_url=self._base_url, timeout=timeout_s)

    async def aclose(self) -> None:
        await self._client.aclose()

    async def create_job(
        self, *, origin: dict[str, str], spec: dict[str, Any], status: str = "accepted"
    ) -> dict[str, Any]:
        resp = await self._client.post(
            "/ledger/jobs", json={"origin": origin, "spec": spec, "status": status}
        )
        if resp.status_code not in (200, 201):
            raise LedgerClientError(
                f"POST /ledger/jobs failed: {resp.status_code} {resp.text}"
            )
        return resp.json()

    async def patch_job(self, job_id: str, **patch: Any) -> dict[str, Any]:
        body = {k: v for k, v in patch.items() if v is not None}
        resp = await self._client.patch(f"/ledger/jobs/{job_id}", json=body)
        if resp.status_code != 200:
            raise LedgerClientError(
                f"PATCH /ledger/jobs/{job_id} failed: {resp.status_code} {resp.text}"
            )
        return resp.json()

    async def get_job(self, job_id: str) -> dict[str, Any] | None:
        resp = await self._client.get(f"/ledger/jobs/{job_id}")
        if resp.status_code == 404:
            return None
        if resp.status_code != 200:
            raise LedgerClientError(
                f"GET /ledger/jobs/{job_id} failed: {resp.status_code} {resp.text}"
            )
        return resp.json()


class ProgressCoalescer:
    """Bounded-interval flush for `kind=progress` events (design doc 5.2).

    A job that emits hundreds of progress lines must not produce hundreds of
    ledger writes. This holds the latest progress entry per job and only
    flushes it once the interval has elapsed since the last flush for that
    job. `attention`/`cost`/terminal events bypass this entirely (the caller
    should write those directly via LedgerClient.patch_job).
    """

    def __init__(self, *, interval_s: float = 2.0) -> None:
        self._interval_s = interval_s
        self._last_flush_ms: dict[str, float] = {}
        self._pending: dict[str, dict[str, Any]] = {}

    def offer(
        self, job_id: str, progress_entry: dict[str, Any]
    ) -> dict[str, Any] | None:
        """Record a progress entry. Returns the entry to flush now, or None
        if it should be held (already flushed within the interval)."""
        self._pending[job_id] = progress_entry
        now = time.monotonic()
        last = self._last_flush_ms.get(job_id, 0.0)
        if now - last >= self._interval_s:
            self._last_flush_ms[job_id] = now
            entry = self._pending.pop(job_id)
            return entry
        return None

    def pending(self, job_id: str) -> dict[str, Any] | None:
        return self._pending.get(job_id)

    def drain(self, job_id: str) -> dict[str, Any] | None:
        """Force-flush whatever is pending for a job (e.g. on job terminal state)."""
        entry = self._pending.pop(job_id, None)
        self._last_flush_ms[job_id] = time.monotonic()
        return entry
