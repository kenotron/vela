"""amplifierd-vela plugin entry point."""

from __future__ import annotations

from typing import Any

from fastapi import APIRouter, Depends

__version__ = "0.1.0"


def create_router(state: Any) -> APIRouter:
    """Plugin entry point. Activates bundles and mounts auth + endpoints."""
    # Imported lazily so test fixtures can monkeypatch DEFAULT_PATH before load.
    from .auth import make_require_token
    from .bundles import activate_bundles
    from .capabilities import make_capabilities_router
    from .projects import make_projects_router
    from .settings import load_settings

    settings = load_settings()
    activate_bundles(state, settings)

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
    return router
