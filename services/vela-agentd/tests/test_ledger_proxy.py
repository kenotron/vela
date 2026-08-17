"""F4 test: ledger proxy routes forward to a real running ledger service instance.

Requires a live `services/ledger` instance reachable at
``VELA_AGENTD_LEDGER_BASE_URL`` (or the default http://localhost:9199).
Skipped automatically if no ledger instance is reachable, so the rest of the
suite stays runnable without external services.
"""

from __future__ import annotations

from contextlib import asynccontextmanager
from unittest.mock import MagicMock

import httpx
import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from vela_agentd_http._ledger_proxy import LedgerProxyClient, ledger_base_url
from vela_agentd_http.routes import ledger as ledger_module

AUTH = {"Authorization": "Bearer test-key"}


def _ledger_reachable() -> bool:
    try:
        resp = httpx.get(f"{ledger_base_url()}/healthz", timeout=1.0)
        return resp.status_code == 200
    except Exception:
        return False


pytestmark = pytest.mark.skipif(
    not _ledger_reachable(),
    reason=f"no ledger service reachable at {ledger_base_url()!r}; start services/ledger to run this test",
)


def _make_test_app() -> FastAPI:
    @asynccontextmanager
    async def _noop_lifespan(application: FastAPI):
        application.state.config = MagicMock()
        application.state.config.api_key = "test-key"
        application.state.ledger_client = LedgerProxyClient()
        yield

    app = FastAPI(lifespan=_noop_lifespan)
    app.include_router(ledger_module.router)
    return app


def test_healthz_proxy_reaches_real_ledger() -> None:
    app = _make_test_app()
    with TestClient(app) as client:
        resp = client.get("/healthz/ledger", headers=AUTH)
        assert resp.status_code == 200
        assert resp.json() == {"status": "ok"}


def test_list_jobs_proxy_reaches_real_ledger() -> None:
    app = _make_test_app()
    with TestClient(app) as client:
        resp = client.get("/ledger/jobs", headers=AUTH)
        # Must reach the real ledger and get back a real (possibly empty) list --
        # not a locally-fabricated response. 200 + list confirms proxying, not
        # reimplementation.
        assert resp.status_code == 200
        assert isinstance(resp.json(), list)


def test_unauthenticated_ledger_proxy_rejected() -> None:
    app = _make_test_app()
    with TestClient(app) as client:
        resp = client.get("/ledger/jobs")
        assert resp.status_code == 401
