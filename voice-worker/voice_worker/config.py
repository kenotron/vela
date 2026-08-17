"""Sandbox/stub LiveKit configuration for this worker.

No real production LiveKit vendor account is available in this environment.
Per the lane's SCOPE-OUTS ("No real production voice-vendor account/billing
setup - a stub/sandbox LiveKit config is sufficient if a real account isn't
available; name that substitution explicitly if used"), this worker is
configured against placeholder values, sourced from environment variables
with clearly-fake fallback defaults. Do not use these defaults against a real
LiveKit deployment - see README.md.
"""

from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True)
class WorkerConfig:
    livekit_url: str
    livekit_api_key: str
    livekit_api_secret: str
    stock_llm_model: str


def load_config() -> WorkerConfig:
    """Load worker configuration from environment, falling back to sandbox
    stub values that are explicitly non-functional against any real LiveKit
    server (see README.md 'Sandbox substitution' section)."""
    return WorkerConfig(
        livekit_url=os.environ.get("LIVEKIT_URL", "wss://sandbox.invalid.local"),
        livekit_api_key=os.environ.get("LIVEKIT_API_KEY", "sandbox-stub-key"),
        livekit_api_secret=os.environ.get("LIVEKIT_API_SECRET", "sandbox-stub-secret"),
        stock_llm_model=os.environ.get("VELA_STOCK_LLM_MODEL", "gpt-4o-mini"),
    )
