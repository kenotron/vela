"""Dispatch orchestration: launch `velafleet-run` over SSH, tail its events,
and PATCH the ledger as they arrive.

Per design doc §9.1 (Stage F0, Lane F0.2): "`vela-agentd` gets a `fleet`
module that SSHes to a hardcoded single machine, launches `velafleet-run`
under `nohup`, and returns the handle. ... Progress = the same JSONL, tailed
over the same SSH connection."
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from typing import Protocol

from vela_agentd_lib.fleet.config import FleetSshConfig
from vela_agentd_lib.fleet.events import (
    MalformedEventError,
    ledger_patch_for_event,
    parse_event_line,
)
from vela_agentd_lib.fleet.ssh_transport import SshTransport

logger = logging.getLogger(__name__)


class FleetDispatchError(RuntimeError):
    """Raised when the remote launch itself fails (before any events can flow)."""


class LedgerPatcher(Protocol):
    """The minimal shape this module needs from `LedgerProxyClient`."""

    async def forward(
        self, method: str, path: str, *, json_body: object | None = None
    ) -> object: ...


@dataclass(frozen=True)
class DispatchHandle:
    """Returned immediately after launch; the caller does not wait on the tail."""

    job_id: str
    machine_id: str
    launched: bool
    detail: str


async def dispatch_job(
    job_id: str,
    runtime: str,
    argv: list[str],
    *,
    ledger: LedgerPatcher,
    config: FleetSshConfig | None = None,
    transport: SshTransport | None = None,
) -> DispatchHandle:
    """Launch `velafleet-run` for `job_id` on the configured remote machine.

    Returns as soon as the launch command completes (fast: a single SSH round
    trip), without waiting for the job itself to finish. Callers are expected
    to run `tail_and_report(...)` as a background task to forward events.
    """
    cfg = config or FleetSshConfig(host="localhost", user="root")
    ssh = transport or SshTransport(cfg)

    launch_cmd = ssh.build_launch_command(job_id, runtime, argv)
    try:
        result = await ssh.run(launch_cmd, timeout_s=cfg.connect_timeout_s)
    except TimeoutError as exc:
        raise FleetDispatchError(
            f"SSH launch to {cfg.ssh_destination()} timed out: {exc}"
        ) from exc

    if result.exit_code != 0 or "LAUNCHED" not in result.stdout:
        raise FleetDispatchError(
            f"failed to launch velafleet-run on {cfg.ssh_destination()}: "
            f"exit_code={result.exit_code} stdout={result.stdout!r} stderr={result.stderr!r}"
        )

    return DispatchHandle(
        job_id=job_id,
        machine_id=cfg.host,
        launched=True,
        detail=f"launched on {cfg.ssh_destination()}",
    )


async def tail_and_report(
    job_id: str,
    *,
    ledger: LedgerPatcher,
    config: FleetSshConfig | None = None,
    transport: SshTransport | None = None,
) -> None:
    """Tail `events.jsonl` for `job_id` over SSH, PATCHing the ledger per event.

    Stops after a terminal event (`finished`/`failed`) or when the remote
    `tail -f` process ends on its own (e.g. connection drop). `attention`
    events are patched immediately and are never coalesced, per
    `docs/fleet/JOB_EVENTS.md`'s invariant.
    """
    cfg = config or FleetSshConfig(host="localhost", user="root")
    ssh = transport or SshTransport(cfg)
    tail_cmd = ssh.build_tail_command(job_id)

    async for line in ssh.stream_lines(tail_cmd):
        try:
            event = parse_event_line(line)
        except MalformedEventError as exc:
            logger.warning(
                "job %s: skipping malformed event line %r: %s", job_id, line, exc
            )
            continue

        patch_body = ledger_patch_for_event(event)
        if patch_body is not None:
            try:
                await ledger.forward(
                    "PATCH", f"/ledger/jobs/{job_id}", json_body=patch_body
                )
            except Exception:
                logger.exception(
                    "job %s: failed to PATCH ledger for kind=%s", job_id, event.kind
                )

        if event.is_terminal:
            logger.info(
                "job %s: terminal event kind=%s, stopping tail", job_id, event.kind
            )
            return
