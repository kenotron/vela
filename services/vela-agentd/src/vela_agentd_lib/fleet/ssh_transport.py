"""SSH transport for the F0 fleet plane.

No `asyncssh` dependency is available in this environment (not installed,
and this lane does not have network access to add it safely), so this
transport shells out to the system `ssh`/`ssh` CLI via `asyncio.create_subprocess_exec`
-- documented fallback per the goal file. This keeps the transport swappable
(§8.5 of the design doc): a future `asyncssh`-backed implementation only
needs to satisfy the same two methods below.
"""

from __future__ import annotations

import asyncio
import logging
import shlex
from collections.abc import AsyncIterator
from dataclasses import dataclass

from vela_agentd_lib.fleet.config import FleetSshConfig

logger = logging.getLogger(__name__)


@dataclass(frozen=True)
class SshCommandResult:
    exit_code: int
    stdout: str
    stderr: str


class SshTransport:
    """Thin wrapper over the `ssh` CLI: run-and-wait, and run-and-stream."""

    def __init__(self, config: FleetSshConfig) -> None:
        self._config = config

    def _base_ssh_args(self) -> list[str]:
        args = [
            "ssh",
            "-o",
            "BatchMode=yes",
            "-o",
            "StrictHostKeyChecking=accept-new",
            "-o",
            f"ConnectTimeout={int(self._config.connect_timeout_s)}",
            "-p",
            str(self._config.port),
        ]
        if self._config.key_path:
            args += ["-i", self._config.key_path]
        args.append(self._config.ssh_destination())
        return args

    async def run(
        self, remote_command: str, *, timeout_s: float | None = None
    ) -> SshCommandResult:
        """Run `remote_command` on the target host and wait for it to exit."""
        args = [*self._base_ssh_args(), remote_command]
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        try:
            stdout_b, stderr_b = await asyncio.wait_for(
                proc.communicate(), timeout=timeout_s
            )
        except TimeoutError:
            proc.kill()
            await proc.wait()
            raise
        return SshCommandResult(
            exit_code=proc.returncode if proc.returncode is not None else -1,
            stdout=stdout_b.decode("utf-8", errors="replace"),
            stderr=stderr_b.decode("utf-8", errors="replace"),
        )

    async def stream_lines(self, remote_command: str) -> AsyncIterator[str]:
        """Run `remote_command` on the target host, yielding stdout line by line.

        Intended for long-lived commands (`tail -f`). The subprocess is left
        running until the caller stops iterating (e.g. after seeing a
        terminal job event) or the remote command exits on its own.
        """
        args = [*self._base_ssh_args(), remote_command]
        proc = await asyncio.create_subprocess_exec(
            *args,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        assert proc.stdout is not None
        try:
            while True:
                raw = await proc.stdout.readline()
                if not raw:
                    break
                yield raw.decode("utf-8", errors="replace")
        finally:
            if proc.returncode is None:
                proc.kill()
                await proc.wait()

    def build_launch_command(self, job_id: str, runtime: str, argv: list[str]) -> str:
        """Build the remote shell command that launches `velafleet-run` under `nohup`.

        Survives the launching SSH session ending (design doc §9.1: "launches
        `velafleet-run` under `nohup`"). Output is redirected to a log file
        alongside the job's event directory rather than left attached to the
        (about-to-close) SSH session's stdio.
        """
        events_dir = self._config.events_path(job_id).rsplit("/", 1)[0]
        run_bin = shlex.quote(self._config.velafleet_run_path)
        job_id_q = shlex.quote(job_id)
        runtime_q = shlex.quote(runtime)
        argv_q = " ".join(shlex.quote(a) for a in argv)
        log_path = f"{events_dir}/launch.log"
        # mkdir -p first: velafleet-run itself creates the events dir, but we
        # need it to exist before redirecting the nohup log into it.
        return (
            f"mkdir -p {shlex.quote(events_dir)} && "
            f"nohup {run_bin} --job {job_id_q} --runtime {runtime_q} -- {argv_q} "
            f">> {shlex.quote(log_path)} 2>&1 < /dev/null & disown; echo LAUNCHED"
        )

    def build_tail_command(self, job_id: str) -> str:
        """Build the remote shell command that tails `events.jsonl` from its start."""
        return f"tail -f -n +1 {shlex.quote(self._config.events_path(job_id))}"
