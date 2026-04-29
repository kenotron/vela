"""GET /capabilities — what this node can do, for the Android Node Detail screen."""

from __future__ import annotations

import platform
import socket
from importlib import metadata
from typing import Any

from fastapi import APIRouter

from . import __version__ as PLUGIN_VERSION
from . import bundles


def _amplifierd_version() -> str:
    try:
        return metadata.version("amplifierd")
    except Exception:
        return "unknown"


def _platform_string() -> str:
    return f"{platform.system().lower()}/{platform.machine().lower()}"


def _list_tools(state: Any) -> list[str]:
    registry = getattr(state, "tool_registry", None)
    if registry is None:
        return []
    list_tools = getattr(registry, "list_tools", None)
    if list_tools is None:
        return []
    try:
        return list(list_tools())
    except Exception:
        return []


def _list_active_bundles(state: Any) -> list[str]:
    registry = getattr(state, "bundle_registry", None)
    if registry is None:
        return []
    return list(getattr(registry, "loaded", []))


def make_capabilities_router(state: Any) -> APIRouter:
    router = APIRouter()

    @router.get("/capabilities")
    def capabilities() -> dict[str, Any]:
        return {
            "hostname": socket.gethostname(),
            "platform": _platform_string(),
            "amplifierd_version": _amplifierd_version(),
            "vela_plugin_version": PLUGIN_VERSION,
            "active_bundles": _list_active_bundles(state),
            "available_tools": _list_tools(state),
            "errors": list(bundles._bundle_errors),
        }

    return router
