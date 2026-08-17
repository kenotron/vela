"""C1 byte-identical verification for vela-agentd, adapted from S-1's harness.

Reuses the exact methodology proven in
``spikes/s1-event-tee/test_s1_event_tee_spike.py`` (100-turn comparison,
tee off vs tee on) but against the vendored ``vela_agentd_http`` package and
additionally verifies that F2 (real approval gate) and F3 (C2 broadcaster
wiring) do not alter the C1 SSE byte stream either, since both are new
per-request wiring in the same route handler that produces C1 output.

Run: uv run pytest tests/test_c1_identity.py -q -s
"""

from __future__ import annotations

import re
from contextlib import asynccontextmanager
from typing import Any
from unittest.mock import MagicMock

from fastapi import FastAPI
from fastapi.testclient import TestClient

from vela_agentd_http._approval_gate import ApprovalGate
from vela_agentd_http._c2_broadcaster import EventBroadcaster
from vela_agentd_http.routes import chat_completions as cc_module
from vela_agentd_http.routes import models as models_module

AUTH = {"Authorization": "Bearer test-key"}
N_TURNS = 100

# Normalize only id/created per the task brief -- everything else must be
# byte-identical across runs.
_ID_RE = re.compile(r'"id":"chatcmpl-[0-9a-f]+"')
_CREATED_RE = re.compile(r'"created":\d+')


def _normalize(body: str) -> str:
    body = _ID_RE.sub('"id":"NORMALIZED"', body)
    body = _CREATED_RE.sub('"created":0', body)
    return body


def _make_test_app(*, with_c2: bool) -> FastAPI:
    prepared_mock = MagicMock()
    prepared_mock.mount_plan = {}
    registry = {"amplifier": "anthropic"}

    @asynccontextmanager
    async def _noop_lifespan(application: FastAPI):
        application.state.config = MagicMock()
        application.state.config.model_id = "amplifier"
        application.state.config.api_key = "test-key"
        application.state.prepared = prepared_mock
        application.state.agent_configs = {}
        application.state.resolved_workspace = None
        application.state.host_config = {}
        application.state.available_models = []
        application.state.served_models_registry = registry
        if with_c2:
            application.state.c2_broadcaster = EventBroadcaster()
            application.state.approval_gate = ApprovalGate(broadcaster=application.state.c2_broadcaster)
        yield

    app = FastAPI(lifespan=_noop_lifespan)
    app.include_router(cc_module.router)
    app.include_router(models_module.router)
    return app


async def _fake_run_chat_turn(**kwargs: Any) -> str:
    display = kwargs["display"]
    await display.emit({"type": "thinking/delta", "text": "considering the request..."})
    await display.emit(
        {
            "type": "tool/started",
            "toolName": "delegate",
            "toolCallId": "call_1",
            "agentName": "foundation:explorer",
        }
    )
    await display.emit(
        {
            "type": "tool/completed",
            "toolName": "delegate",
            "toolCallId": "call_1",
            "agentName": "foundation:explorer",
            "result": "ok",
        }
    )
    await display.emit({"type": "result/delta", "text": "Hello "})
    await display.emit({"type": "result/delta", "text": "world."})
    await display.emit(
        {
            "type": "usage",
            "inputTokens": 12,
            "outputTokens": 4,
            "cacheReadTokens": 0,
            "cacheWriteTokens": 0,
            "cost": "0.0001",
        }
    )
    return "Hello world."


def _run_turns(app: FastAPI, n: int) -> list[str]:
    bodies: list[str] = []
    with TestClient(app) as client:
        for _ in range(n):
            resp = client.post(
                "/v1/chat/completions",
                headers=AUTH,
                json={
                    "model": "amplifier",
                    "messages": [{"role": "user", "content": "hi"}],
                    "stream": True,
                },
            )
            assert resp.status_code == 200
            bodies.append(_normalize(resp.text))
    return bodies


def test_c1_byte_identical_with_and_without_c2(monkeypatch) -> None:
    """100 turns without C2 state vs. 100 turns with F2/F3 wired -- identical C1 bytes."""
    monkeypatch.setattr(cc_module, "run_chat_turn", _fake_run_chat_turn)
    monkeypatch.delenv("AMPLIFIER_AGENT_EVENT_TEE_PATH", raising=False)

    app_without = _make_test_app(with_c2=False)
    app_with = _make_test_app(with_c2=True)

    bodies_without = _run_turns(app_without, N_TURNS)
    bodies_with = _run_turns(app_with, N_TURNS)

    assert len(bodies_without) == N_TURNS
    assert len(bodies_with) == N_TURNS
    for i, (a, b) in enumerate(zip(bodies_without, bodies_with, strict=True)):
        assert a == b, f"turn {i}: C1 output diverged between no-C2 and with-C2 runs"
    # All turns internally identical too (deterministic fake turn).
    assert len(set(bodies_without)) == 1
    assert len(set(bodies_with)) == 1
