"""Activate Amplifier bundles at plugin startup. Failures are logged, never raised."""

from __future__ import annotations

import logging
from typing import Any

from .settings import VelaPluginSettings


logger = logging.getLogger(__name__)

# Module-level error list read by capabilities.py. Populated by activate_bundles.
_bundle_errors: list[str] = []


def activate_bundles(state: Any, settings: VelaPluginSettings) -> None:
    """Load every bundle named in settings via ``state.bundle_registry.load``."""
    registry = getattr(state, "bundle_registry", None)
    if registry is None:
        return

    for name in settings.bundles:
        try:
            registry.load(name)
        except Exception as exc:  # noqa: BLE001 — by design: never break startup
            msg = f"{name}: {exc}"
            _bundle_errors.append(msg)
            logger.warning("[vela] failed to activate bundle: %s", msg)
            continue
        logger.info("[vela] activated bundle: %s", name)
