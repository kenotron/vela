"""F4: C3 ledger routes -- proxy to lane 2.1's services/ledger REST API.

Every route below forwards verbatim to the ledger service
(``VELA_AGENTD_LEDGER_BASE_URL``, default http://localhost:9199) via
``LedgerProxyClient``. No ledger logic is reimplemented here.
"""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import JSONResponse, StreamingResponse

from vela_agentd_http._auth import require_bearer
from vela_agentd_http._ledger_proxy import LedgerProxyClient

router = APIRouter()


def _client(request: Request) -> LedgerProxyClient:
    client = getattr(request.app.state, "ledger_client", None)
    if client is None:
        client = LedgerProxyClient()
        request.app.state.ledger_client = client
    return client


async def _proxy_json(request: Request, method: str, path: str) -> JSONResponse:
    client = _client(request)
    body: Any = None
    if method in ("POST", "PATCH", "PUT"):
        try:
            body = await request.json()
        except Exception:
            body = None
    try:
        resp = await client.forward(method, path, params=dict(request.query_params), json_body=body)
    except Exception as exc:
        raise HTTPException(
            status_code=502,
            detail={"error": {"message": f"ledger service unreachable: {type(exc).__name__}: {exc}"}},
        ) from exc
    try:
        content = resp.json()
    except Exception:
        content = {"raw": resp.text}
    return JSONResponse(status_code=resp.status_code, content=content)


@router.get("/ledger/jobs", dependencies=[Depends(require_bearer)])
async def list_jobs(request: Request) -> JSONResponse:
    return await _proxy_json(request, "GET", "/ledger/jobs")


@router.post("/ledger/jobs", dependencies=[Depends(require_bearer)])
async def create_job(request: Request) -> JSONResponse:
    return await _proxy_json(request, "POST", "/ledger/jobs")


@router.get("/ledger/attention", dependencies=[Depends(require_bearer)])
async def list_attention(request: Request) -> JSONResponse:
    return await _proxy_json(request, "GET", "/ledger/attention")


@router.get("/ledger/jobs/{job_id}", dependencies=[Depends(require_bearer)])
async def get_job(job_id: str, request: Request) -> JSONResponse:
    return await _proxy_json(request, "GET", f"/ledger/jobs/{job_id}")


@router.patch("/ledger/jobs/{job_id}", dependencies=[Depends(require_bearer)])
async def patch_job(job_id: str, request: Request) -> JSONResponse:
    return await _proxy_json(request, "PATCH", f"/ledger/jobs/{job_id}")


@router.post("/ledger/jobs/{job_id}/decision", dependencies=[Depends(require_bearer)])
async def post_job_decision(job_id: str, request: Request) -> JSONResponse:
    return await _proxy_json(request, "POST", f"/ledger/jobs/{job_id}/decision")


@router.get("/ledger/events", dependencies=[Depends(require_bearer)])
async def ledger_events(request: Request) -> StreamingResponse:
    """Proxy the ledger's own SSE stream through to the C2 client."""
    client = _client(request)
    base_url = client._base_url

    async def _relay():
        import httpx

        async with httpx.AsyncClient(timeout=None) as http_client:
            async with http_client.stream("GET", f"{base_url}/ledger/events") as resp:
                async for chunk in resp.aiter_bytes():
                    yield chunk

    return StreamingResponse(_relay(), media_type="text/event-stream")


@router.get("/healthz/ledger", dependencies=[Depends(require_bearer)])
async def ledger_healthz(request: Request) -> JSONResponse:
    return await _proxy_json(request, "GET", "/healthz")
