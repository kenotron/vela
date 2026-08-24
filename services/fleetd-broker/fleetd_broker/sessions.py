"""Live worker session bookkeeping for decision relay (design doc 4.1, 5.2).

A "session" here is the broker-side handle for a worker's held connection
(design doc: "workers DIAL IN and hold the session (never dialed out to)").
This lane's transport is a WebSocket (`/fleet/worker/{machine_id}`); the
registry (registry.py) tracks liveness/labels, this module tracks the
job-id -> machine-id binding needed to relay a decision to the right open
connection, and the actual send is done through a pluggable sender so the
transport can be swapped later (design doc 8.5) without touching admission
or ledger-writing code.
"""

from __future__ import annotations

import threading
from typing import Protocol


class WorkerUnavailableError(Exception):
    """Raised when a decision cannot be relayed because the owning worker
    session is not currently connected."""


class DecisionSender(Protocol):
    async def send_decision(self, machine_id: str, job_id: str, text: str) -> None: ...


class SessionTable:
    """Maps job_id -> machine_id, and machine_id -> a connected sender.

    Thread-safe for the synchronous bookkeeping methods; `relay_decision` is
    async because it awaits the transport-specific send.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._job_machine: dict[str, str] = {}
        self._senders: dict[str, DecisionSender] = {}

    def bind_job(self, job_id: str, machine_id: str) -> None:
        with self._lock:
            self._job_machine[job_id] = machine_id

    def unbind_job(self, job_id: str) -> None:
        with self._lock:
            self._job_machine.pop(job_id, None)

    def machine_for_job(self, job_id: str) -> str | None:
        with self._lock:
            return self._job_machine.get(job_id)

    def attach_sender(self, machine_id: str, sender: DecisionSender) -> None:
        with self._lock:
            self._senders[machine_id] = sender

    def detach_sender(self, machine_id: str) -> None:
        with self._lock:
            self._senders.pop(machine_id, None)

    async def relay_decision(self, job_id: str, text: str) -> None:
        machine_id = self.machine_for_job(job_id)
        if machine_id is None:
            raise WorkerUnavailableError(f"no machine binding known for job {job_id!r}")
        with self._lock:
            sender = self._senders.get(machine_id)
        if sender is None:
            raise WorkerUnavailableError(
                f"worker {machine_id!r} for job {job_id!r} is not connected"
            )
        await sender.send_decision(machine_id, job_id, text)
