from fastapi import APIRouter, Depends, FastAPI
from fastapi.testclient import TestClient

from vela_plugin.auth import make_require_token
from vela_plugin.settings import VelaPluginSettings


def _build_app(settings: VelaPluginSettings) -> TestClient:
    app = FastAPI()
    require = make_require_token(settings)

    router = APIRouter()

    @router.get("/protected", dependencies=[Depends(require)])
    def protected():
        return {"ok": True}

    @router.get("/health", dependencies=[Depends(require)])
    def health():
        return {"status": "ok"}

    app.include_router(router)
    return TestClient(app)


def test_valid_token_passes():
    client = _build_app(VelaPluginSettings(auth_token="secret"))
    r = client.get("/protected", headers={"X-Amplifier-Token": "secret"})
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_missing_token_rejected():
    client = _build_app(VelaPluginSettings(auth_token="secret"))
    # TestClient defaults client.host to "testclient" — not a localhost bypass
    r = client.get("/protected")
    assert r.status_code == 401
    assert r.json() == {"detail": {"error": "invalid_token"}}


def test_wrong_token_rejected():
    client = _build_app(VelaPluginSettings(auth_token="secret"))
    r = client.get("/protected", headers={"X-Amplifier-Token": "wrong"})
    assert r.status_code == 401
    assert r.json() == {"detail": {"error": "invalid_token"}}


def test_health_bypasses_auth():
    client = _build_app(VelaPluginSettings(auth_token="secret"))
    r = client.get("/health")
    assert r.status_code == 200
    assert r.json() == {"status": "ok"}


def test_localhost_ipv4_bypasses_auth():
    client = _build_app(VelaPluginSettings(auth_token="secret"))
    # Override transport client host to 127.0.0.1
    r = client.get("/protected", headers={"host": "localhost"}, extensions={})
    # TestClient's client tuple is set via `client` kwarg on the constructor —
    # use a direct ASGI scope override instead:
    from starlette.testclient import TestClient as _TC  # type: ignore
    app = client.app
    tc = _TC(app, base_url="http://testserver", client=("127.0.0.1", 12345))
    r = tc.get("/protected")
    assert r.status_code == 200
    assert r.json() == {"ok": True}


def test_localhost_ipv6_bypasses_auth():
    from fastapi.testclient import TestClient as _TC
    settings = VelaPluginSettings(auth_token="secret")
    client = _build_app(settings)
    tc = _TC(client.app, base_url="http://testserver", client=("::1", 12345))
    r = tc.get("/protected")
    assert r.status_code == 200


def test_no_token_open_mode_accepts_all():
    """When no auth token is configured the server runs in open/dev mode and accepts all requests."""
    client = _build_app(VelaPluginSettings(auth_token=""))
    r = client.get("/protected")
    assert r.status_code == 200
    assert r.json() == {"ok": True}
