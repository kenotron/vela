"""F0.2: SSH-out fleet dispatch (design doc 2026-08-24-vela-fleet-execution-plane.md §9.1).

Milestone F0 candidate B: `vela-agentd` SSHes to a single (hardcoded/configured)
machine, launches `velafleet-run` under `nohup`, and tails the resulting
`events.jsonl` over a second SSH session -- forwarding each event into the
ledger via `PATCH /ledger/jobs/{id}`.

This package intentionally has no broker, no worker registry, and no
transport abstraction -- that is Stage F1 (`vela-fleetd`). Everything here is
built so it survives unmodified into that stage per §9.1: "`velafleet-run`,
the JSONL protocol, the runtime adapters, the job spec, and the
ledger-writing code are all identical."
"""

from __future__ import annotations

from vela_agentd_lib.fleet.config import FleetSshConfig, load_fleet_ssh_config
from vela_agentd_lib.fleet.dispatch import FleetDispatchError, dispatch_job
from vela_agentd_lib.fleet.events import (
    JobEvent,
    ledger_patch_for_event,
    parse_event_line,
)

__all__ = [
    "FleetDispatchError",
    "FleetSshConfig",
    "JobEvent",
    "dispatch_job",
    "ledger_patch_for_event",
    "load_fleet_ssh_config",
    "parse_event_line",
]
