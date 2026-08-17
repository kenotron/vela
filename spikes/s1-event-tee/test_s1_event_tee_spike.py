"""Spike S-1 verification harness: event tee vs. C1 (chat-completions) parity.

Not part of the permanent suite -- this is spike evidence. Run directly:

    uv run pytest tests/http/test_s1_event_tee_spike.py -q -s

Exercises 100 turns WITHOUT the tee and 100 turns WITH the tee attached
(``AMPLIFIER_AGENT_EVENT_TEE_PATH`` set), each turn emitting a realistic
mix of display events (result/delta, thinking/delta, tool/started,
tool/completed with agentName for a delegated sub-agent, usage, error-free
path) through the SAME fake ``run_chat_turn``. Asserts the collected SSE
byte streams are identical across the two runs, and that the tee'd JSONL
file captured the tool/started + tool/completed events with agentName.
"""

from __future__ import annotations

import json
import os
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any
from unittest.mock import MagicMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from amplifier_agent_http.routes import chat_completions as cc_module
from amplifier_agent_http.routes import models as models_module

AUTH = {"Authorization": "Bearer test-key"}
N_TURNS = 100


def _make_test_app() -> FastAPI:
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
        yield

    app = FastAPI(lifespan=_noop_lifespan)
    app.include_router(cc_module.router)
    app.include_router(models_module.router)
    return app


async def _fake_run_chat_turn(**kwargs: Any) -> str:
    """Emit a realistic mixed event stream through ``display``, like a real turn.

    Includes: thinking/delta, tool/started + tool/completed (with an
    ``agentName`` field simulating a delegated sub-agent turn), result/delta
    (the visible text), and a usage event -- covering every event family the
    spike brief cares about (F1 items 1, 3, 4).
    """
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


def _normalize(sse_text: str) -> str:
    """Strip the per-request random ``id`` and ``created`` fields.

    Each turn's ``chunk_id`` and ``created`` timestamp are freshly generated
    (see ``new_chunk_id()`` / ``time.time()`` in chat_completions.py) and
    differ run-to-run by construction -- that is expected non-determinism,
    not perturbation from the tee. Normalize them out before comparing so
    the diff isolates only tee-caused differences.
    """
    import re

    text = re.sub(r'"id":"chatcmpl-[0-9a-f]+"', '"id":"<ID>"', sse_text)
    text = re.sub(r'"created":\d+', '"created":<TS>', text)
    return text


def _run_n_turns(client: TestClient, n: int) -> list[str]:
    bodies: list[str] = []
    for i in range(n):
        resp = client.post(
            "/v1/chat/completions",
            headers=AUTH,
            json={
                "model": "amplifier",
                "messages": [{"role": "user", "content": f"turn {i}"}],
                "stream": True,
            },
        )
        assert resp.status_code == 200, resp.text
        bodies.append(_normalize(resp.text))
    return bodies


def test_tee_does_not_perturb_c1_output(tmp_path: Path) -> None:
    """100 turns without tee vs. 100 turns with tee -> byte-identical C1 output."""
    app = _make_test_app()

    with (
        patch(
            "amplifier_agent_http.routes.chat_completions.run_chat_turn",
            side_effect=_fake_run_chat_turn,
        ),
        TestClient(app, raise_server_exceptions=False) as client,
    ):
        os.environ.pop("AMPLIFIER_AGENT_EVENT_TEE_PATH", None)
        without_tee = _run_n_turns(client, N_TURNS)

        tee_path = tmp_path / "tee-events.jsonl"
        os.environ["AMPLIFIER_AGENT_EVENT_TEE_PATH"] = str(tee_path)
        try:
            with_tee = _run_n_turns(client, N_TURNS)
        finally:
            os.environ.pop("AMPLIFIER_AGENT_EVENT_TEE_PATH", None)

    assert without_tee == with_tee, "tee attachment perturbed the C1 (chat-completions) output stream"

    # Confirm the tee actually captured events, including tool/started and
    # tool/completed with a populated agentName for the delegated sub-agent.
    assert tee_path.exists(), "tee JSONL file was not created"
    lines = [json.loads(line) for line in tee_path.read_text().splitlines() if line.strip()]
    assert len(lines) > 0, "tee captured no events"

    started = [e for e in lines if e.get("type") == "tool/started"]
    completed = [e for e in lines if e.get("type") == "tool/completed"]
    assert len(started) == N_TURNS, f"expected {N_TURNS} tool/started events, got {len(started)}"
    assert len(completed) == N_TURNS, f"expected {N_TURNS} tool/completed events, got {len(completed)}"
    assert all(e.get("agentName") == "foundation:explorer" for e in started)
    assert all(e.get("agentName") == "foundation:explorer" for e in completed)

    # Sanity: dropped/discarded-from-C1 event types (tool/started, tool/completed,
    # usage, thinking/final) DID reach the tee even though C1 output above never
    # contains them -- confirming the tee sees the pre-filter internal stream.
    without_tee_blob = "\n".join(without_tee)
    assert "tool/started" not in without_tee_blob
    assert "\"toolName\":\"delegate\"" not in without_tee_blob.replace(" ", "")
