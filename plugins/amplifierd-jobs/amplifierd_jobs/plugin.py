"""amplifierd plugin entrypoint.

Registers the /api/jobs router and starts the background scheduler
when amplifierd loads this plugin.
"""

from __future__ import annotations

import logging
import os
from typing import Any

from fastapi import APIRouter

from .session_client import SessionClient
from .scheduler import JobScheduler
from .store import JobStore

logger = logging.getLogger(__name__)

_store: JobStore | None = None
_scheduler: JobScheduler | None = None


def create_router(state: Any) -> APIRouter:
    """amplifierd plugin entrypoint — called when the plugin is loaded."""
    global _store, _scheduler

    db_path = os.path.expanduser(
        os.environ.get("VELA_JOBS_DB", "~/.amplifierd/jobs.db")
    )
    base_url = os.environ.get("AMPLIFIERD_URL", "http://127.0.0.1:8410")
    token = os.environ.get("VELA_AUTH_TOKEN", "")
    max_parallel = int(os.environ.get("VELA_JOBS_MAX_PARALLEL", "4"))

    _store = JobStore(db_path)
    client = SessionClient(base_url=base_url, token=token)
    _scheduler = JobScheduler(store=_store, client=client, max_parallel=max_parallel)

    # Capture as local non-Optional for closures; guaranteed non-None at this point.
    store: JobStore = _store
    scheduler: JobScheduler = _scheduler

    app = getattr(state, "app", None)
    if app is not None:

        @app.on_event("startup")
        async def _jobs_startup() -> None:
            await store.init()
            await scheduler.load_and_start()
            logger.info("amplifierd-jobs: scheduler started")

        @app.on_event("shutdown")
        async def _jobs_shutdown() -> None:
            await scheduler.shutdown()
            logger.info("amplifierd-jobs: scheduler stopped")

    from .router import make_jobs_router

    return make_jobs_router(store=_store, scheduler=_scheduler)


def get_store() -> JobStore | None:
    return _store
