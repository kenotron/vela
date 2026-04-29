"""Read ~/.amplifierd/settings.json into typed dataclasses."""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path


DEFAULT_PATH = Path.home() / ".amplifierd" / "settings.json"


@dataclass(frozen=True)
class VelaSettings:
    auth_token: str = ""


@dataclass(frozen=True)
class Settings:
    vela: VelaSettings = field(default_factory=VelaSettings)
    bundles: list[str] = field(default_factory=list)


def load_settings(path: Path = DEFAULT_PATH) -> Settings:
    """Load settings from ``path``. Returns defaults if file is missing or empty."""
    if not Path(path).exists():
        return Settings()

    data = json.loads(Path(path).read_text() or "{}")
    vela_raw = data.get("vela") or {}
    bundles_raw = data.get("bundles") or []

    return Settings(
        vela=VelaSettings(auth_token=str(vela_raw.get("auth_token", ""))),
        bundles=list(bundles_raw),
    )
