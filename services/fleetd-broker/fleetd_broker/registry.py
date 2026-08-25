"""Worker registry + admission target selection (design doc 4.1, 5.1).

The registry is the D3 source of truth: "reachability" is a lookup against an
already-open connection, never an on-demand probe (design doc section 1.2's
framing insight, and invariant FB1 -- the broker never dials a fleet machine).

A worker is "live" only if its last heartbeat is within
`2 * heartbeat_interval_s` (design doc 5.1's honest edge case). This bounds,
but does not eliminate, the dishonesty window between "TCP session open" and
"machine actually alive" -- callers can see the gap via
`last_heartbeat_age_ms` on the dispatch response.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass


class NoLiveWorkerError(Exception):
    """Raised when no live worker matches a dispatch target (D3 admission failure)."""


@dataclass
class WorkerRecord:
    machine_id: str
    labels: set[str]
    runtimes: list[str]
    last_heartbeat_ms: int
    active_jobs: int = 0
    connected: bool = True


def now_ms() -> int:
    return int(time.time() * 1000)


class WorkerRegistry:
    """Thread-safe in-process registry of live worker sessions.

    In-process by design (design doc FA7 -- fleet is order 1-20 machines,
    not 1000). If that assumption ever proves false, this class is the
    single component the design doc's section 8.5 transport swap replaces;
    it is kept behind this narrow interface for exactly that reason.
    """

    def __init__(self, *, heartbeat_interval_s: float = 15.0) -> None:
        self._heartbeat_interval_s = heartbeat_interval_s
        self._lock = threading.Lock()
        self._workers: dict[str, WorkerRecord] = {}

    @property
    def heartbeat_interval_s(self) -> float:
        return self._heartbeat_interval_s

    def register(
        self, *, machine_id: str, labels: list[str], runtimes: list[str]
    ) -> WorkerRecord:
        with self._lock:
            record = self._workers.get(machine_id)
            if record is None:
                record = WorkerRecord(
                    machine_id=machine_id,
                    labels=set(labels),
                    runtimes=list(runtimes),
                    last_heartbeat_ms=now_ms(),
                )
                self._workers[machine_id] = record
            else:
                record.labels = set(labels)
                record.runtimes = list(runtimes)
                record.last_heartbeat_ms = now_ms()
                record.connected = True
            return record

    def heartbeat(self, machine_id: str) -> None:
        with self._lock:
            record = self._workers.get(machine_id)
            if record is not None:
                record.last_heartbeat_ms = now_ms()
                record.connected = True

    def disconnect(self, machine_id: str) -> None:
        """Mark a worker's session as closed. The record (and its jobs) are
        retained for reconciliation on reconnect (design doc 4.1's
        "reconciliation" responsibility) -- it is not deleted.
        """
        with self._lock:
            record = self._workers.get(machine_id)
            if record is not None:
                record.connected = False

    def is_live(self, record: WorkerRecord) -> bool:
        if not record.connected:
            return False
        age_ms = now_ms() - record.last_heartbeat_ms
        return age_ms <= 2 * self._heartbeat_interval_s * 1000

    def get(self, machine_id: str) -> WorkerRecord | None:
        with self._lock:
            return self._workers.get(machine_id)

    def list_live(self) -> list[WorkerRecord]:
        with self._lock:
            records = list(self._workers.values())
        return [r for r in records if self.is_live(r)]

    def heartbeat_age_ms(self, machine_id: str) -> int | None:
        record = self.get(machine_id)
        if record is None:
            return None
        return now_ms() - record.last_heartbeat_ms

    def increment_active_jobs(self, machine_id: str, delta: int = 1) -> None:
        with self._lock:
            record = self._workers.get(machine_id)
            if record is not None:
                record.active_jobs = max(0, record.active_jobs + delta)

    def select_target(
        self, *, machine_id: str | None, labels: list[str]
    ) -> WorkerRecord:
        """Select a target worker for admission (design doc 5.1 step 3).

        Raises NoLiveWorkerError (mapped to 400 UNREACHABLE by the API layer)
        if no live worker matches -- this is the D3 admission-time failure
        mode, deliberately explicit rather than silently queuing (design doc
        section 2.1's "does dispatch to an offline target fail or queue"
        question -- this lane answers it: it fails, honestly, with a reason).
        """
        live = self.list_live()

        if machine_id is not None:
            for record in live:
                if record.machine_id == machine_id:
                    return record
            raise NoLiveWorkerError(
                f"pinned machine_id {machine_id!r} has no live worker session"
            )

        label_set = set(labels)
        matching = [r for r in live if label_set.issubset(r.labels)]
        if not matching:
            raise NoLiveWorkerError(
                f"no live worker matches labels {sorted(label_set)!r}"
            )

        # least_loaded: fewest active jobs first, stable tie-break on machine_id.
        matching.sort(key=lambda r: (r.active_jobs, r.machine_id))
        return matching[0]

    def select_targets_all(self, *, labels: list[str]) -> list[WorkerRecord]:
        """Resolve the fan-out primitive `strategy: "all"` (design doc 5.3)."""
        label_set = set(labels)
        return [r for r in self.list_live() if label_set.issubset(r.labels)]
