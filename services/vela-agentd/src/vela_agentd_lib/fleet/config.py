"""Remote host configuration for the F0 SSH-out fleet plane.

Per design doc §9.1 ("SSHes to a hardcoded single machine"), this milestone
targets exactly one configured remote machine. Configuration comes from env
vars with a localhost-to-localhost fallback so the dispatch path is
exercisable even when no second real machine is reachable from this
environment -- an explicitly-allowed stand-in per the F0.2 goal file.
"""

from __future__ import annotations

import os
from dataclasses import dataclass

_DEFAULT_HOST = "localhost"
_DEFAULT_USER = os.environ.get("USER", "root")
_DEFAULT_EVENTS_ROOT = "~/.vela/jobs"


@dataclass(frozen=True)
class FleetSshConfig:
    """Connection details for the single F0 fleet target machine."""

    host: str
    user: str
    port: int = 22
    key_path: str | None = None
    events_root: str = _DEFAULT_EVENTS_ROOT
    connect_timeout_s: float = 10.0
    velafleet_run_path: str = "velafleet-run"

    def ssh_destination(self) -> str:
        return f"{self.user}@{self.host}"

    def events_path(self, job_id: str) -> str:
        # POSIX join by hand -- this path is evaluated on the *remote* shell,
        # so os.path (local-OS-flavored) would be wrong on a non-POSIX
        # dispatcher host.
        root = self.events_root.rstrip("/")
        return f"{root}/{job_id}/events.jsonl"


def load_fleet_ssh_config() -> FleetSshConfig:
    """Load the F0 fleet target from env vars, defaulting to a localhost stand-in.

    Env vars (all optional):
        VELA_FLEET_SSH_HOST      -- default "localhost"
        VELA_FLEET_SSH_USER      -- default $USER
        VELA_FLEET_SSH_PORT      -- default 22
        VELA_FLEET_SSH_KEY_PATH  -- default None (use ssh-agent/default identity)
        VELA_FLEET_EVENTS_ROOT   -- default "~/.vela/jobs"
        VELA_FLEET_SSH_CONNECT_TIMEOUT_S -- default 10.0
        VELA_FLEET_RUN_PATH      -- default "velafleet-run" (must be on remote PATH)
    """
    return FleetSshConfig(
        host=os.environ.get("VELA_FLEET_SSH_HOST", _DEFAULT_HOST),
        user=os.environ.get("VELA_FLEET_SSH_USER", _DEFAULT_USER),
        port=int(os.environ.get("VELA_FLEET_SSH_PORT", "22")),
        key_path=os.environ.get("VELA_FLEET_SSH_KEY_PATH") or None,
        events_root=os.environ.get("VELA_FLEET_EVENTS_ROOT", _DEFAULT_EVENTS_ROOT),
        connect_timeout_s=float(
            os.environ.get("VELA_FLEET_SSH_CONNECT_TIMEOUT_S", "10.0")
        ),
        velafleet_run_path=os.environ.get("VELA_FLEET_RUN_PATH", "velafleet-run"),
    )
