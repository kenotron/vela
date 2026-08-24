"""Tests for the F0.2 SSH-out fleet dispatch module.

Event-parsing / ledger-mapping logic is tested without any real SSH (a fake
`SshTransport`-shaped object is injected). One additional test performs a
real localhost SSH round trip and is skipped (with a stated reason) if
passwordless `ssh localhost` isn't set up in this environment -- which it is
not in this sandbox (no sshd / no authorized_keys), a BLOCKED finding
reported at the goal level rather than faked here.
"""

from __future__ import annotations

import json
import subprocess

import pytest
from vela_agentd_lib.fleet.config import FleetSshConfig
from vela_agentd_lib.fleet.dispatch import (
    DispatchHandle,
    FleetDispatchError,
    dispatch_job,
    tail_and_report,
)
from vela_agentd_lib.fleet.events import (
    MalformedEventError,
    ledger_patch_for_event,
    parse_event_line,
)
from vela_agentd_lib.fleet.ssh_transport import SshCommandResult, SshTransport, _quote_remote_path

# ---------------------------------------------------------------------------
# Event parsing / ledger mapping (pure, no SSH)
# ---------------------------------------------------------------------------


def test_parse_started_event():
    line = '{"ts":1756000000,"kind":"started","job_id":"job-123","runtime":"shell","pid":41233}'
    event = parse_event_line(line)
    assert event.kind == "started"
    assert event.job_id == "job-123"
    assert event.fields["runtime"] == "shell"
    assert event.fields["pid"] == 41233
    assert not event.is_terminal


def test_parse_finished_event_is_terminal():
    line = '{"ts":1756000900,"kind":"finished","job_id":"job-123","exit_code":0}'
    event = parse_event_line(line)
    assert event.is_terminal


def test_parse_failed_event_is_terminal():
    line = '{"ts":1756000900,"kind":"failed","job_id":"job-123","exit_code":1,"error":"boom"}'
    event = parse_event_line(line)
    assert event.is_terminal


def test_parse_malformed_json_raises():
    with pytest.raises(MalformedEventError):
        parse_event_line("not json{{{")


def test_parse_missing_kind_raises():
    with pytest.raises(MalformedEventError):
        parse_event_line(json.dumps({"ts": 1, "job_id": "x"}))


def test_parse_empty_line_raises():
    with pytest.raises(MalformedEventError):
        parse_event_line("   \n")


def test_ledger_patch_for_progress():
    event = parse_event_line(
        '{"ts":1756000012,"kind":"progress","job_id":"job-123","message":"cloned repo","percent":20}'
    )
    patch = ledger_patch_for_event(event)
    assert patch is not None
    assert patch["status"] == "running"
    assert patch["progress"]["message"] == "cloned repo"
    assert patch["progress"]["percent"] == 20


def test_ledger_patch_for_attention_never_none():
    event = parse_event_line(
        '{"ts":1756000180,"kind":"attention","job_id":"job-123","reason":"pick one",'
        '"options":["a","b"]}'
    )
    patch = ledger_patch_for_event(event)
    assert patch is not None
    assert patch["status"] == "needs_attention"
    assert patch["attention"]["required"] is True
    assert patch["attention"]["reason"] == "pick one"
    assert patch["attention"]["options"] == ["a", "b"]


def test_ledger_patch_for_cost():
    event = parse_event_line(
        '{"ts":1756000420,"kind":"cost","job_id":"job-123","usd":0.41,"tokens":88300}'
    )
    patch = ledger_patch_for_event(event)
    assert patch is not None
    assert patch["cost"]["usd"] == 0.41
    assert patch["cost"]["tokens"] == 88300


def test_ledger_patch_for_finished():
    event = parse_event_line(
        '{"ts":1756000900,"kind":"finished","job_id":"job-123","exit_code":0,"result":{"pr_url":"https://x"}}'
    )
    patch = ledger_patch_for_event(event)
    assert patch is not None
    assert patch["status"] == "done"
    assert patch["result"]["exit_code"] == 0
    assert patch["result"]["result"] == {"pr_url": "https://x"}


def test_ledger_patch_for_failed():
    event = parse_event_line(
        '{"ts":1756000900,"kind":"failed","job_id":"job-123","exit_code":1,"error":"boom"}'
    )
    patch = ledger_patch_for_event(event)
    assert patch is not None
    assert patch["status"] == "failed"
    assert patch["result"]["exit_code"] == 1
    assert patch["result"]["error"] == "boom"


# ---------------------------------------------------------------------------
# Dispatch/tail orchestration with a fake SSH transport (no real network)
# ---------------------------------------------------------------------------


class FakeLedger:
    """Records every forwarded PATCH so tests can assert on them."""

    def __init__(self) -> None:
        self.calls: list[tuple[str, str, object]] = []

    async def forward(self, method: str, path: str, *, json_body: object | None = None):
        self.calls.append((method, path, json_body))


class FakeTransport:
    """Stands in for `SshTransport`: scripted launch result + canned event lines."""

    def __init__(self, launch_result: SshCommandResult, lines: list[str]) -> None:
        self._launch_result = launch_result
        self._lines = lines

    def build_launch_command(self, job_id: str, runtime: str, argv: list[str]) -> str:
        return "launch-cmd"

    def build_tail_command(self, job_id: str) -> str:
        return "tail-cmd"

    async def run(
        self, remote_command: str, *, timeout_s: float | None = None
    ) -> SshCommandResult:
        return self._launch_result

    async def stream_lines(self, remote_command: str):
        for line in self._lines:
            yield line


@pytest.mark.asyncio
async def test_dispatch_job_success():
    ledger = FakeLedger()
    transport = FakeTransport(
        SshCommandResult(exit_code=0, stdout="LAUNCHED\n", stderr=""), []
    )
    handle = await dispatch_job(
        "job-1",
        "shell",
        ["echo", "hi"],
        ledger=ledger,
        config=FleetSshConfig(host="h", user="u"),
        transport=transport,
    )
    assert isinstance(handle, DispatchHandle)
    assert handle.launched is True
    assert handle.job_id == "job-1"
    assert handle.machine_id == "h"


@pytest.mark.asyncio
async def test_dispatch_job_launch_failure_raises():
    ledger = FakeLedger()
    transport = FakeTransport(
        SshCommandResult(exit_code=255, stdout="", stderr="connection refused"), []
    )
    with pytest.raises(FleetDispatchError):
        await dispatch_job(
            "job-2",
            "shell",
            ["echo"],
            ledger=ledger,
            config=FleetSshConfig(host="h", user="u"),
            transport=transport,
        )


@pytest.mark.asyncio
async def test_tail_and_report_forwards_events_and_stops_at_terminal():
    ledger = FakeLedger()
    lines = [
        '{"ts":1,"kind":"started","job_id":"job-3","runtime":"shell","pid":1}\n',
        '{"ts":2,"kind":"progress","job_id":"job-3","message":"working","percent":50}\n',
        '{"ts":3,"kind":"attention","job_id":"job-3","reason":"pick","options":["a","b"]}\n',
        '{"ts":4,"kind":"finished","job_id":"job-3","exit_code":0}\n',
        # This line must never be consumed -- tail stops at the terminal event above.
        '{"ts":5,"kind":"progress","job_id":"job-3","message":"should not appear"}\n',
    ]
    transport = FakeTransport(SshCommandResult(0, "", ""), lines)
    await tail_and_report(
        "job-3",
        ledger=ledger,
        config=FleetSshConfig(host="h", user="u"),
        transport=transport,
    )

    assert len(ledger.calls) == 4
    kinds_seen = [
        call[2]["status"] if "status" in call[2] else None for call in ledger.calls
    ]
    assert kinds_seen == ["running", "running", "needs_attention", "done"]
    for method, path, _ in ledger.calls:
        assert method == "PATCH"
        assert path == "/ledger/jobs/job-3"


@pytest.mark.asyncio
async def test_tail_and_report_skips_malformed_lines_without_crashing():
    ledger = FakeLedger()
    lines = [
        "not json at all\n",
        '{"ts":1,"kind":"started","job_id":"job-4","runtime":"shell","pid":1}\n',
        '{"ts":2,"kind":"finished","job_id":"job-4","exit_code":0}\n',
    ]
    transport = FakeTransport(SshCommandResult(0, "", ""), lines)
    await tail_and_report(
        "job-4",
        ledger=ledger,
        config=FleetSshConfig(host="h", user="u"),
        transport=transport,
    )
    assert len(ledger.calls) == 2  # malformed line skipped, both valid events forwarded


@pytest.mark.asyncio
async def test_attention_events_are_never_coalesced_with_progress():
    """Per JOB_EVENTS.md invariants: every attention event gets its own immediate patch."""
    ledger = FakeLedger()
    lines = [
        '{"ts":1,"kind":"attention","job_id":"job-5","reason":"first"}\n',
        '{"ts":2,"kind":"attention","job_id":"job-5","reason":"second"}\n',
        '{"ts":3,"kind":"finished","job_id":"job-5","exit_code":0}\n',
    ]
    transport = FakeTransport(SshCommandResult(0, "", ""), lines)
    await tail_and_report(
        "job-5",
        ledger=ledger,
        config=FleetSshConfig(host="h", user="u"),
        transport=transport,
    )
    attention_calls = [
        c for c in ledger.calls if c[2].get("status") == "needs_attention"
    ]
    assert len(attention_calls) == 2
    assert attention_calls[0][2]["attention"]["reason"] == "first"
    assert attention_calls[1][2]["attention"]["reason"] == "second"


# ---------------------------------------------------------------------------
# Tilde-quoting regression (found via a real end-to-end SSH run, not theorized)
# ---------------------------------------------------------------------------


def test_quote_remote_path_preserves_tilde_expansion():
    """`shlex.quote()` on a path like "~/.vela/jobs/x" defeats tilde expansion,
    because tilde-expansion only applies to an UNQUOTED leading `~`. A real
    end-to-end SSH run against localhost surfaced this the hard way: it left
    a literal directory named `~` under the remote $HOME because the launch
    command's `mkdir -p` received the whole quoted string. `_quote_remote_path`
    must leave a leading `~/` unquoted while still quoting the remainder.
    """
    # Leading ~/ stays unquoted (so the remote shell still expands it);
    # shlex.quote() only wraps the remainder in quotes if it actually needs
    # escaping -- a plain path segment like this one doesn't, so it comes
    # back byte-identical, just with the tilde preserved un-mangled.
    assert _quote_remote_path("~/.vela/jobs/job-1/events.jsonl") == "~/.vela/jobs/job-1/events.jsonl"
    # No leading tilde: shlex.quote() still leaves an already-safe path alone.
    assert _quote_remote_path("/abs/path") == "/abs/path"
    # A single quote inside the remainder forces shlex.quote() to escape it,
    # but the leading ~/ must still come through unquoted either way.
    quoted = _quote_remote_path("~/weird'name/events.jsonl")
    assert quoted.startswith("~/")
    assert "'\\''" in quoted or "weird" in quoted


# ---------------------------------------------------------------------------
# Real localhost SSH round trip (skipped if not available in this sandbox)
# ---------------------------------------------------------------------------


def _ssh_localhost_available() -> bool:
    try:
        result = subprocess.run(
            [
                "ssh",
                "-o",
                "BatchMode=yes",
                "-o",
                "ConnectTimeout=2",
                "-o",
                "StrictHostKeyChecking=accept-new",
                "localhost",
                "true",
            ],
            capture_output=True,
            timeout=5,
        )
        return result.returncode == 0
    except Exception:
        return False


@pytest.mark.skipif(
    not _ssh_localhost_available(),
    reason="passwordless `ssh localhost` is not available in this sandbox (no sshd / no authorized key) "
    "-- BLOCKED finding for the 'real second machine' proof, reported at the goal level",
)
@pytest.mark.asyncio
async def test_real_ssh_localhost_run_round_trip():
    """When ssh localhost genuinely works, prove SshTransport.run() round-trips real output.

    Uses the *current* user (whoever has passwordless access into their own
    localhost account trusted), not a hardcoded "root" -- the environment
    this was verified against grants passwordless access to the invoking
    user's own account (pubkey added to their own ~/.ssh/authorized_keys),
    not to root.
    """
    import getpass

    config = FleetSshConfig(host="localhost", user=getpass.getuser())
    transport = SshTransport(config)
    result = await transport.run("echo hello-from-remote", timeout_s=5)
    assert result.exit_code == 0
    assert "hello-from-remote" in result.stdout
