import pytest
from unittest.mock import AsyncMock, patch
from amplifierd_jobs.session_client import SessionClient

BASE = "http://127.0.0.1:8410"
TOKEN = "test-token"


@pytest.mark.asyncio
async def test_create_session():
    client = SessionClient(base_url=BASE, token=TOKEN)
    response_data = {"session_id": "sess-abc123", "status": "idle"}
    with patch.object(client._http, "post", new_callable=AsyncMock) as mock_post:
        mock_resp = AsyncMock()
        mock_resp.raise_for_status = AsyncMock()
        mock_resp.json = AsyncMock(return_value=response_data)
        mock_post.return_value = mock_resp
        session_id = await client.create_session("vela")
    assert session_id == "sess-abc123"
    mock_post.assert_called_once()
    call_kwargs = mock_post.call_args
    assert "vela" in str(call_kwargs)


@pytest.mark.asyncio
async def test_execute_prompt():
    client = SessionClient(base_url=BASE, token=TOKEN)
    response_data = {"correlation_id": "corr-1", "status": "accepted"}
    with patch.object(client._http, "post", new_callable=AsyncMock) as mock_post:
        mock_resp = AsyncMock()
        mock_resp.raise_for_status = AsyncMock()
        mock_resp.json = AsyncMock(return_value=response_data)
        mock_post.return_value = mock_resp
        await client.execute_prompt("sess-abc123", "hello world")
    call_args = mock_post.call_args
    assert "sess-abc123" in str(call_args)
    # body must use "prompt" field not "message"
    assert "prompt" in str(call_args)


@pytest.mark.asyncio
async def test_wait_for_completion_success(monkeypatch):
    client = SessionClient(base_url=BASE, token=TOKEN)
    sse_lines = [
        b"event: orchestrator:complete\n",
        b'data: {"session_id": "sess-abc123", "data": {"orchestrator": "loop-vela"}}\n',
        b"\n",
    ]

    async def fake_stream(*args, **kwargs):
        class FakeResponse:
            async def aiter_lines(self):
                for line in sse_lines:
                    yield line.decode()

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                pass

        return FakeResponse()

    monkeypatch.setattr(client._http, "stream", fake_stream)
    status = await client.wait_for_completion("sess-abc123", timeout=10.0)
    assert status == "success"


@pytest.mark.asyncio
async def test_wait_for_completion_timeout(monkeypatch):
    client = SessionClient(base_url=BASE, token=TOKEN)
    import asyncio

    async def fake_stream(*args, **kwargs):
        class FakeResponse:
            async def aiter_lines(self):
                while True:
                    await asyncio.sleep(0.01)
                    yield ""

            async def __aenter__(self):
                return self

            async def __aexit__(self, *a):
                pass

        return FakeResponse()

    monkeypatch.setattr(client._http, "stream", fake_stream)
    status = await client.wait_for_completion("sess-abc123", timeout=0.05)
    assert status == "failed"
