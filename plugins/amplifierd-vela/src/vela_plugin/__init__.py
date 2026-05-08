"""amplifierd-vela plugin entry point."""

from __future__ import annotations

import gc
import logging
import time
from typing import Any

import amplifierd
from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse
from fastapi.routing import APIRoute

__version__ = "0.1.0"

logger = logging.getLogger(__name__)


def create_router(state: Any) -> APIRouter:
    """Plugin entry point. Activates bundles and mounts auth + endpoints."""
    # Imported lazily so test fixtures can monkeypatch DEFAULT_PATH before load.
    from .auth import make_require_token
    from .bundles import activate_bundles
    from .capabilities import make_capabilities_router
    from .machine_id import get_machine_id
    from .mdns_service import AmplifierdMdnsService
    from .projects import make_projects_router
    from .settings import load_settings
    from .steer import make_steer_router

    settings = load_settings()
    activate_bundles(state, settings)

    # machine_id section
    machine_id = get_machine_id()
    state.machine_id = machine_id
    logger.info("amplifierd-vela: machine_id = %s", machine_id)
    # gc-based navigation from state→app.__dict__→app, route insertion at
    # position 0 takes priority because FastAPI iterates routes on each request,
    # first match wins.
    _inject_health_override(state, machine_id)

    # mDNS section
    import socket
    import threading
    label = socket.gethostname()
    mdns = AmplifierdMdnsService(machine_id=machine_id)
    # Run start() in a daemon thread: Zeroconf uses asyncio.run_coroutine_threadsafe
    # internally; calling it from within the asyncio lifespan context blocks the
    # event loop and causes a TimeoutError.  A background thread gets its own
    # scheduler context and lets the event loop stay free.
    threading.Thread(
        target=mdns.start,
        kwargs={"label": label},
        daemon=True,
        name="amplifierd-vela-mdns",
    ).start()
    state.mdns_service = mdns  # zeroconf registers atexit handler internally

    require_token = make_require_token(settings)

    router = APIRouter()
    router.include_router(
        make_projects_router(state),
        dependencies=[Depends(require_token)],
    )
    router.include_router(
        make_capabilities_router(state),
        dependencies=[Depends(require_token)],
    )
    router.include_router(
        make_steer_router(state),
        dependencies=[Depends(require_token)],
    )
    return router


def _inject_health_override(state: Any, machine_id: str) -> None:
    """Insert /health route at position 0 to add machine_id.

    Why gc: plugin API passes app.state, not FastAPI app; app.state stored in
    app.__dict__['state']; gc.get_referrers(state) → includes app.__dict__;
    gc.get_referrers(app.__dict__) → includes app. Route insertion safe
    post-startup because FastAPI iterates self.routes dynamically per request.
    """
    from fastapi import FastAPI

    app: FastAPI | None = None
    for ref in gc.get_referrers(state):
        if not isinstance(ref, dict):
            continue
        for owner in gc.get_referrers(ref):
            if isinstance(owner, FastAPI):
                app = owner
                break

    if app is None:
        logger.warning(
            "amplifierd-vela: could not locate FastAPI app from state; "
            "machine_id will NOT appear in /health"
        )
        return

    _machine_id = machine_id  # capture for closure

    async def _health_with_machine_id(request: Request) -> JSONResponse:
        """Replacement /health that includes machine_id."""
        start_time: float = getattr(request.app.state, "start_time", time.time())
        uptime = round(time.time() - start_time, 2)
        session_manager = getattr(request.app.state, "session_manager", None)
        active = len(session_manager.list_sessions()) if session_manager else 0
        try:
            import amplifier_core
            rust_engine = bool(getattr(amplifier_core, "rust_available", False))
        except Exception:
            rust_engine = False
        return JSONResponse({
            "status": "healthy",
            "version": amplifierd.__version__,
            "uptime_seconds": uptime,
            "active_sessions": active,
            "rust_engine": rust_engine,
            "machine_id": _machine_id,
        })

    health_route = APIRoute("/health", _health_with_machine_id, methods=["GET"])
    app.routes.insert(0, health_route)
    logger.info("amplifierd-vela: /health override installed (machine_id injected)")
