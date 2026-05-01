"""Read VELA_AUTH_TOKEN from env or ~/.amplifierd/settings.json into typed dataclasses."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_PATH = Path.home() / ".amplifierd" / "settings.json"


@dataclass
class VelaPluginSettings:
    """Settings for the amplifierd-vela plugin."""

    auth_token: str = ""
    bundles: list[str] = field(default_factory=list)


def load_settings(path: Path | None = None) -> VelaPluginSettings:
    """Load vela plugin settings from env var (primary) or settings file (fallback)."""
    # Primary: environment variable set by launchd plist / systemd unit
    token_from_env = os.environ.get("VELA_AUTH_TOKEN", "")
    if token_from_env:
        return VelaPluginSettings(auth_token=token_from_env)

    # Fallback: read from ~/.amplifierd/settings.json vela section
    if path is None:
        path = DEFAULT_PATH
    if not Path(path).exists():
        return VelaPluginSettings()

    try:
        data = json.loads(Path(path).read_text() or "{}")
        vela_raw = data.get("vela") or {}
        bundles_raw = data.get("bundles") or []
        return VelaPluginSettings(
            auth_token=str(vela_raw.get("auth_token", "")),
            bundles=list(bundles_raw),
        )
    except Exception:
        pass

    return VelaPluginSettings()
